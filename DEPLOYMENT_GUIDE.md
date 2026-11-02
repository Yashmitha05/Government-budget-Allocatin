# Deployment Guide (free-tier hosting)

This guide deploys the system to free-tier services. Nothing in the application logic, ML models, datasets or UI changes. Only configuration and packaging were added for production.

| Part | Host | Public? |
|---|---|---|
| React + Vite website | **Cloudflare Pages** | Yes |
| Spring Boot backend (Java 17) | **Google Cloud Run**, service `budget-api` | Yes. This is the **only** public API |
| FastAPI ML service (Python) | **Google Cloud Run**, service `budget-ml` | **No.** Private; callable only by the backend |
| PostgreSQL | **Neon** | Reached only by the backend, over SSL |
| Model versions (registry) | **Cloudflare R2** bucket | Reached only by the ML service |

```
 Browser ──HTTPS──▶ Cloudflare Pages (static site)
    │
    └──HTTPS /api──▶ Cloud Run: budget-api (Spring Boot) ──JDBC + SSL──▶ Neon PostgreSQL
                          │
                          └─ HTTPS + Google ID token + X-ML-Api-Key ─▶ Cloud Run: budget-ml (FastAPI, private)
                                                                          │
                                                                          └─ S3 API ─▶ Cloudflare R2 (model versions)
```

---

## 0. What was prepared in the repository

| File | Purpose |
|---|---|
| `backend/Dockerfile` | Two-stage build: Maven + JDK 17 builds the JAR; a JRE 17 image runs it as a non-root user |
| `backend/.dockerignore`, `backend/.gcloudignore` | Keep `target/` out of builds and uploads |
| `ml-service/Dockerfile` | Python 3.13 slim image containing the code, the RBI data (`data/`), the seed models and the backtest records. It **must be built from the project root** |
| `ml-service/cloudbuild.yaml` | Cloud Build recipe that builds the ML image from the project root |
| `.dockerignore`, `.gcloudignore` (root) | Upload and build only what the ML image needs |
| `ml-service/budget_ml/artifact_store.py` | Persists the model registry in Cloudflare R2 (see section 5) |
| `ml-service/requirements.txt` / `requirements-dev.txt` | Runtime packages (now including `boto3` for R2) / test packages |
| `frontend/.env.example`, `frontend/.nvmrc` | The production API URL setting; Node 22 for the build |

**Behaviour added for production:**
- **Backend:**
  - Listens on `$PORT`.
  - Reads every setting from environment variables.
  - Uses a small connection pool tuned for Neon.
  - Accepts CORS origin *patterns*, so Pages preview URLs work.
  - Sends `X-ML-Api-Key` and, on Cloud Run, a Google ID token to the ML service.
- **ML service:**
  - Listens on `0.0.0.0:$PORT`.
  - When `ML_API_KEY` is set, rejects every request except `/health` that lacks a valid `X-ML-Api-Key` (HTTP 401).
  - Restores and persists model versions through R2.
- **Frontend:** in production builds, error hints no longer mention local ports.

**Not changed:** algorithms, forecasting logic, datasets, database schema, data-import logic and the UI layout.

---

## 1. Before you start

**Accounts (all have free tiers):**
- **Google Cloud.** Cloud Run needs a project with a **billing account attached**, even when usage stays within the free tier.
- **Cloudflare.** Pages, plus R2. Enabling R2 asks for a payment method; usage within the free tier is not charged.
- **Neon.**
- **GitHub.** Cloudflare Pages deploys from a Git repository.

Free-tier limits change over time. Check the current Cloud Run, Artifact Registry, Cloud Build, Secret Manager, Neon and R2 pricing pages before relying on them. The settings below (scale to zero, one ML instance, small pool) are chosen to stay within them for a student project.

**Tools:**
- The easiest option is **Google Cloud Shell**. It runs in the browser from console.cloud.google.com and already has `gcloud`, `git` and `docker`. All commands below are bash and work there as-is.
- If you deploy from Windows instead, install the Google Cloud CLI and use Git Bash.

**Push the project to GitHub:**
- The `.gitignore` excludes `.env` (your local password), `node_modules`, `.venv`, `backend/target` and `frontend/dist`.
- It **includes** `data/` and `ml-service/models/`; the ML image needs both.

```bash
cd JavaProject
git init
git add .
git commit -m "Budget Intelligence system"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Then, in Cloud Shell:

```bash
git clone https://github.com/<you>/<repo>.git
cd <repo>
```

---

## 2. Neon PostgreSQL

1. Create a Neon project, for example **budget-intel**, with **PostgreSQL 17**. Pick a region close to the Cloud Run region you'll use (section 4); the two should be in the same geography.
2. In the project, create a database named **`budget_intel`**, or use the default `neondb` and adjust the name in the URL.
3. Under **Connection details**, turn **connection pooling OFF** to get the **direct** host, which has no `-pooler` in its name. Flyway takes a session lock while migrating, so use the direct connection.
4. Build the JDBC URL. Neon requires SSL:

   ```
   jdbc:postgresql://<direct-host>/budget_intel?sslmode=require
   ```

   Keep the **role (user)** and **password** from the same page; they go in `DB_USER` and `DB_PASSWORD`.
5. Don't create tables yourself. On first start, the backend runs `backend/src/main/resources/db/migration/V1__initial_schema.sql` with Flyway against the empty database.

---

## 3. Cloudflare R2 (model versions)

1. In the Cloudflare dashboard, go to **R2 → Create bucket** and name it **`budget-intel-models`**.
2. Go to **R2 → Manage R2 API tokens → Create API token**:
   - Permission: **Object Read & Write**
   - Scope: **this bucket only**
   Copy the **Access Key ID** and **Secret Access Key**. They are shown once.
3. Note your **Account ID**, shown on the R2 overview page. The S3 endpoint is `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`, and the service builds it from `R2_ACCOUNT_ID`.
4. Leave the bucket empty. On its first start, the ML service uploads the seed models that ship in its image.

---

## 4. Google Cloud Run (both services)

Run everything below in Cloud Shell, from the repository root.

### 4.1 Project, region, APIs, image repository

```bash
export PROJECT_ID=<your-project-id>
export REGION=<cloud-run-region>          # e.g. us-east4, close to your Neon region
gcloud config set project "$PROJECT_ID"
gcloud config set run/region "$REGION"

gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com secretmanager.googleapis.com

gcloud artifacts repositories create budget --repository-format=docker --location="$REGION"
```

### 4.2 Service accounts (one per service)

```bash
gcloud iam service-accounts create budget-ml-sa  --display-name="Budget ML service"
gcloud iam service-accounts create budget-api-sa --display-name="Budget backend"
export ML_SA="budget-ml-sa@${PROJECT_ID}.iam.gserviceaccount.com"
export API_SA="budget-api-sa@${PROJECT_ID}.iam.gserviceaccount.com"
```

### 4.3 Secrets (Secret Manager)

```bash
# a long random shared secret between backend and ML service
export ML_API_KEY_VALUE=$(openssl rand -hex 32)

printf '%s' "$ML_API_KEY_VALUE"          | gcloud secrets create ML_API_KEY --data-file=-
printf '%s' '<neon-password>'            | gcloud secrets create DB_PASSWORD --data-file=-
printf '%s' '<r2-access-key-id>'         | gcloud secrets create R2_ACCESS_KEY_ID --data-file=-
printf '%s' '<r2-secret-access-key>'     | gcloud secrets create R2_SECRET_ACCESS_KEY --data-file=-

for s in ML_API_KEY R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY; do
  gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:${ML_SA}" --role=roles/secretmanager.secretAccessor
done
for s in ML_API_KEY DB_PASSWORD; do
  gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:${API_SA}" --role=roles/secretmanager.secretAccessor
done
```

### 4.4 Build and deploy the ML service (private)

The ML image includes `../data`, so it is built from the **project root** with `ml-service/cloudbuild.yaml`:

```bash
export ML_IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/budget/budget-ml:v1"

gcloud builds submit --config ml-service/cloudbuild.yaml --substitutions=_IMAGE="$ML_IMAGE" .

gcloud run deploy budget-ml \
  --image="$ML_IMAGE" \
  --service-account="$ML_SA" \
  --no-allow-unauthenticated \
  --cpu=1 --memory=1Gi --cpu-boost \
  --min-instances=0 --max-instances=1 --concurrency=20 --timeout=300 \
  --set-env-vars="R2_ACCOUNT_ID=<cloudflare-account-id>,R2_BUCKET=budget-intel-models,R2_PREFIX=models/" \
  --set-secrets="ML_API_KEY=ML_API_KEY:latest,R2_ACCESS_KEY_ID=R2_ACCESS_KEY_ID:latest,R2_SECRET_ACCESS_KEY=R2_SECRET_ACCESS_KEY:latest"

export ML_URL=$(gcloud run services describe budget-ml --format='value(status.url)')
echo "$ML_URL"

# only the backend's service account may call the ML service
gcloud run services add-iam-policy-binding budget-ml \
  --member="serviceAccount:${API_SA}" --role=roles/run.invoker
```

**Why these settings:**
- `--no-allow-unauthenticated`: Google rejects any caller without a valid identity before the request reaches the container. The `X-ML-Api-Key` check inside the app is a second layer.
- `--max-instances=1`: the model registry is written by one process at a time, so a retrain on one instance can never race with another. It also keeps usage low.
- `--cpu-boost`: the service fits the spending-to-outcome impact models at startup, which takes CPU for several seconds.

### 4.5 Deploy the backend (public API gateway)

`--source=backend` builds `backend/Dockerfile` with Cloud Build.

CORS origins contain commas, so `--set-env-vars` uses `|` as its separator; the `^|^` prefix does that (`gcloud topic escaping`). Replace `budget-intel` with your Pages project name from section 6.

```bash
gcloud run deploy budget-api \
  --source=backend \
  --service-account="$API_SA" \
  --allow-unauthenticated \
  --cpu=1 --memory=1Gi --cpu-boost \
  --min-instances=0 --max-instances=2 --timeout=900 \
  --set-env-vars="^|^DB_URL=jdbc:postgresql://<neon-direct-host>/budget_intel?sslmode=require|DB_USER=<neon-role>|ML_SERVICE_URL=${ML_URL}|ML_AUTH_GCP_ID_TOKEN=true|CORS_ALLOWED_ORIGINS=https://budget-intel.pages.dev,https://*.budget-intel.pages.dev" \
  --set-secrets="DB_PASSWORD=DB_PASSWORD:latest,ML_API_KEY=ML_API_KEY:latest"

export API_URL=$(gcloud run services describe budget-api --format='value(status.url)')
echo "$API_URL"
```

The first `--source` deploy offers to create an Artifact Registry repository named `cloud-run-source-deploy`; answer **Y**.

**Why these settings:**
- `--timeout=900`: the one-time dataset import (section 7) writes about 17,000 rows across the internet to Neon, which takes longer than locally (about 50 s).
- `ML_AUTH_GCP_ID_TOKEN=true`: the backend fetches a Google ID token from the Cloud Run metadata server, with audience `ML_SERVICE_URL`, and sends it with every ML call.

---

## 5. How datasets and model files are stored

| What | Where in production | Changes at runtime? |
|---|---|---|
| RBI tables (`data/raw/`) and the processed panel (`data/processed/`) | Inside the ML image at `/srv/data` (`BUDGET_DATA_DIR`) | No |
| Backtest records (`ml-service/reports/`) | Inside the ML image | No |
| Model registry (`models/<target>/v<N>/model.joblib`, `metadata.json`, `active.json`) | **Working copy** in the container at `/srv/ml-service/models` (`BUDGET_MODEL_DIR`); **persistent copy** in R2 under `budget-intel-models/models/` | Yes: retrain and calibrate add versions |
| Imported observations, scenarios, predictions, errors | Neon PostgreSQL | Yes |

**The ML service's registry lifecycle** (`budget_ml/artifact_store.py`):
1. **Start-up:**
   - If the bucket prefix is **empty** (first deploy), it uploads the image's seed models.
   - Otherwise it **downloads every stored file**, so versions created before a restart or redeploy come back.
2. **Retrain or calibrate** (Prediction Accuracy page):
   - Before writing, it refreshes from R2.
   - After writing, it uploads that target's folder, including `active.json`.

Cloud Run's own filesystem is temporary; R2 is the copy that persists.

**To reset the registry to the shipped seed models:** delete the `models/` objects in the bucket, then deploy a new ML revision.

**To change the dataset** (a new RBI edition): rebuild `data/processed` and the models locally (`scripts/build_dataset.py`, `scripts/train_models.py`, `scripts/calibrate_models.py`), commit, rebuild the ML image (section 4.4), then empty the R2 prefix so the new seed is uploaded.

---

## 6. Cloudflare Pages (website)

In the Cloudflare dashboard, go to **Workers & Pages → Create → Pages → Connect to Git**, select the repository, and use exactly these settings:

| Setting | Value |
|---|---|
| Project name | `budget-intel` (gives `https://budget-intel.pages.dev`) |
| Production branch | `main` |
| Framework preset | `Vite` (or None) |
| Root directory (advanced) | `frontend` |
| Build command | `npm run build` |
| Build output directory | `dist` |
| Environment variable | `VITE_API_BASE_URL` = `<API_URL from 4.5>/api`, e.g. `https://budget-api-xxxxxxxxxx-uk.a.run.app/api` |
| Environment variable | `NODE_VERSION` = `22` (also set by `frontend/.nvmrc`) |

`VITE_API_BASE_URL` is compiled into the site **at build time**. If you change it, start a new deployment in Pages (**Deployments → Retry deployment**).

**Page routes** such as `/scenarios`: the build has no `404.html`, so Pages serves `index.html` for unknown paths and the React router takes over. No `_redirects` file is needed.

**If your Pages project name is not `budget-intel`,** update the backend's CORS setting:

```bash
gcloud run services update budget-api \
  --update-env-vars="^|^CORS_ALLOWED_ORIGINS=https://<name>.pages.dev,https://*.<name>.pages.dev"
```

For a custom domain, add its origin to the same list, for example `https://budget.example.org`.

---

## 7. Import the RBI dataset (once)

1. Open `https://budget-intel.pages.dev`.
2. The top bar shows **Load RBI dataset**. Click it once.

   The backend then:
   - asks the ML service for the processed dataset;
   - inserts about 14,730 observations into Neon;
   - mirrors the model versions;
   - stores about 2,235 backtest predictions;
   - scores every prediction that has an actual.

   Allow a few minutes, because Neon is remote.

Without the browser:

```bash
curl -X POST "$API_URL/api/admin/datasets/import"
```

It is safe to repeat: a dataset whose content hash is already stored is not imported again.

---

## 8. Environment variables

### Backend: `budget-api`

| Variable | Required | Example / default | Notes |
|---|---|---|---|
| `PORT` | set by Cloud Run | `8080` | `server.port=${PORT:${SERVER_PORT:8080}}` |
| `DB_URL` | **yes** | `jdbc:postgresql://<neon-direct-host>/budget_intel?sslmode=require` | Direct, not pooled, host |
| `DB_USER` | **yes** | Neon role | |
| `DB_PASSWORD` | **yes** (secret) | | |
| `DB_POOL_SIZE` | no | `5` | Hikari maximum pool size |
| `ML_SERVICE_URL` | **yes** | `https://budget-ml-xxxx.run.app` | Exactly the ML service URL; also the ID-token audience |
| `ML_API_KEY` | **yes** (secret) | | Must equal the ML service's `ML_API_KEY` |
| `ML_AUTH_GCP_ID_TOKEN` | yes on Cloud Run | `true` | Adds a Google ID token for the private ML service |
| `ML_TIMEOUT_MS` | no | `120000` | Read timeout for ML calls |
| `CORS_ALLOWED_ORIGINS` | **yes** | `https://budget-intel.pages.dev,https://*.budget-intel.pages.dev` | Comma-separated origins or patterns |

### ML service: `budget-ml`

| Variable | Required | Example / default | Notes |
|---|---|---|---|
| `PORT` | set by Cloud Run | `8080` | Uvicorn binds `0.0.0.0:$PORT` |
| `ML_API_KEY` | **yes** (secret) | | Every endpoint except `/health` requires header `X-ML-Api-Key` |
| `R2_ACCOUNT_ID` | **yes** | Cloudflare account ID | Or set `R2_ENDPOINT_URL` instead |
| `R2_BUCKET` | **yes** | `budget-intel-models` | Unset = no persistence (local development) |
| `R2_ACCESS_KEY_ID` | **yes** (secret) | | R2 API token |
| `R2_SECRET_ACCESS_KEY` | **yes** (secret) | | R2 API token |
| `R2_PREFIX` | no | `models/` | Folder inside the bucket |
| `BUDGET_DATA_DIR`, `BUDGET_MODEL_DIR` | set in image | `/srv/data`, `/srv/ml-service/models` | No Windows paths are used anywhere |

### Website: Cloudflare Pages (build time)

| Variable | Value |
|---|---|
| `VITE_API_BASE_URL` | `<backend URL>/api` |
| `NODE_VERSION` | `22` |

---

## 9. Build and start commands (reference)

| Component | Build | Start (inside the container) |
|---|---|---|
| Backend | `docker build -t budget-api backend` | `java -jar /app/app.jar` (listens on `$PORT`) |
| ML service | `docker build -f ml-service/Dockerfile -t budget-ml .` (from project root) | `uvicorn app.main:app --host 0.0.0.0 --port $PORT` |
| Website | `npm ci && npm run build` (in `frontend/`) | Static files in `frontend/dist` |

To run the two containers locally against a local database (optional):

```bash
docker build -f ml-service/Dockerfile -t budget-ml .
docker build -t budget-api backend
docker network create budget
docker run -d --name ml  --network budget -e PORT=8080 -e ML_API_KEY=devkey budget-ml
docker run -d --name api --network budget -p 8080:8080 -e PORT=8080 -e ML_API_KEY=devkey \
  -e ML_SERVICE_URL=http://ml:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/budget_intel -e DB_USER=postgres -e DB_PASSWORD=<pw> \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5173 budget-api
```

---

## 10. Production URLs and security summary

| URL | Who can call it |
|---|---|
| `https://budget-intel.pages.dev` | Everyone (the website) |
| `https://budget-api-…run.app/api/...` | Everyone, but browsers only from the allowed CORS origins. This is the only public API |
| `https://budget-api-…run.app/actuator/health` | Everyone (liveness only) |
| `https://budget-ml-…run.app/...` | **Only `budget-api-sa`**, through Cloud Run IAM, and every endpoint except `/health` also needs `X-ML-Api-Key` |

The browser never calls the ML service; `frontend/src/api.js` only uses `VITE_API_BASE_URL`.

---

## 11. Production testing checklist

Run these after deploying. Expected results are shown.

**Backend and database**
- [ ] `curl "$API_URL/actuator/health"` → `{"status":"UP"}`. This confirms Neon over SSL and the Flyway migration.
- [ ] Neon console → Tables: `flyway_schema_history` has version 1, and the 14 application tables exist.
- [ ] `curl "$API_URL/api/states"` → `[]` before the import; about 40 states after.

**ML service is private**
- [ ] `curl -i "$ML_URL/models"` → **403 Forbidden** from Google (no identity).
- [ ] `curl -i -H "Authorization: Bearer $(gcloud auth print-identity-token)" "$ML_URL/models"` → **401 UNAUTHORIZED** from the app (no service key). Only the backend has both.

**CORS**
- [ ] `curl -i -X OPTIONS "$API_URL/api/states" -H "Origin: https://budget-intel.pages.dev" -H "Access-Control-Request-Method: GET"` → 200, with `Access-Control-Allow-Origin: https://budget-intel.pages.dev`.
- [ ] The same request with `-H "Origin: https://example.com"` → 403.

**Website and import**
- [ ] The website loads; refreshing on `/scenarios` still shows the page.
- [ ] **Load RBI dataset** completes; the top bar shows `Data: hsis-2024-25+wdi-…`.
- [ ] Dashboard (Karnataka): headline figures, deficit gauge, two forecast charts.
- [ ] Budget Analysis: year comparison table and indicator chart.
- [ ] Scenario Simulator: run a custom +12% capital outlay scenario; it is saved and listed.
- [ ] Optimization: Punjab → recommended deficit exactly 3.00%; a median social floor for Uttar Pradesh → "Not possible".
- [ ] Stress Testing: run on the current budget and on a saved scenario.
- [ ] Prediction Accuracy: the overview table shows 5 items; the records list has about 2,235 entries.

**Model persistence (R2)**
- [ ] After the first ML start, the R2 bucket contains `models/<target>/v1/…` and `v2/…` for the five targets.
- [ ] On Prediction Accuracy → Model versions, click **Retrain** for one item; a new version appears.
- [ ] R2 now holds that `v<N>` folder.
- [ ] Force a new ML revision: `gcloud run services update budget-ml --update-env-vars=RESTART=$(date +%s)`. Reload the page: the new version is still listed.

**Cold start**
- [ ] After 15+ minutes idle, the first page load may take 20–60 s while the services start. The site shows "may be starting up… wait a minute" and works on retry.

---

## 12. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `gcloud builds submit` fails with *permission denied* pushing the image | The Cloud Build service account lacks Artifact Registry access. Grant `roles/artifactregistry.writer` to the account named in the error |
| Backend logs: `FATAL: password authentication failed` | Wrong `DB_USER`, or the `DB_PASSWORD` secret has a trailing newline. Recreate it with `printf '%s'` |
| Backend logs: Flyway lock or migration timeout | You used the pooled (`-pooler`) Neon host. Switch `DB_URL` to the direct host |
| Website shows "analytics service unavailable" every time | Check `ML_SERVICE_URL` (exact URL, no `/api`), that `ML_AUTH_GCP_ID_TOKEN=true`, the `run.invoker` binding for `budget-api-sa`, and that both services use the same `ML_API_KEY` |
| Browser console: CORS error | The Pages origin is missing from `CORS_ALLOWED_ORIGINS` (section 6) |
| Retrained model version disappears after a restart | R2 variables or secrets missing on `budget-ml`. Check its logs for "restored … model files from r2://" at startup |
| Import stops part-way with a 504 | Increase the backend `--timeout` (maximum 3600). The import is safe to repeat |

---

## 13. Redeploying after code changes

```bash
git pull
# backend
gcloud run deploy budget-api --source=backend
# ML service (new tag each time)
export ML_IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/budget/budget-ml:v2"
gcloud builds submit --config ml-service/cloudbuild.yaml --substitutions=_IMAGE="$ML_IMAGE" .
gcloud run deploy budget-ml --image="$ML_IMAGE"
# website: Cloudflare Pages rebuilds automatically on every push to main
```

To stay within the Artifact Registry free storage, delete old images (console → Artifact Registry → repositories `budget` and `cloud-run-source-deploy`), keeping only the ones currently deployed.
