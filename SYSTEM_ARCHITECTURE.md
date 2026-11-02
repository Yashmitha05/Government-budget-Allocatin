# System Architecture

Status legend: **[implemented]** = built and tested; **[designed]** = specified here, not yet built.

## 1. Components

```
┌──────────────────────┐   REST/JSON   ┌───────────────────────────┐   REST/JSON   ┌──────────────────────────┐
│  React frontend      │ ────────────▶ │  Spring Boot backend      │ ────────────▶ │  FastAPI ML service       │
│  (Vite, Recharts)    │ ◀──────────── │  (Java 17, JPA/Hibernate) │ ◀──────────── │  (Python, scikit-learn,   │
│  frontend/           │               │  backend/                 │               │   SciPy)  ml-service/     │
└──────────────────────┘               └─────────────┬─────────────┘               └────────────┬─────────────┘
                                                     │ JDBC                                     │ files
                                              ┌──────▼───────┐                     ┌────────────▼─────────────┐
                                              │ PostgreSQL   │                     │ data/raw  (RBI XLSX, WB)  │
                                              │ system of    │                     │ data/processed (panel)    │
                                              │ record       │                     │ ml-service/models/ (vN)   │
                                              └──────────────┘                     └──────────────────────────┘
```

| Component | Responsibility | Does NOT do |
|---|---|---|
| **React** | Pages, forms, charts, client-side validation | Business rules or calculations shown as results |
| **Spring Boot** | REST API for the UI; validation; persistence of records, forecasts, scenarios, optimisation runs, stress tests, deviations and model versions; orchestrates ML calls; error translation | ML computation |
| **FastAPI ML service** | Data pipeline (parse → clean → panel); model training, prediction, scenario impact, optimisation, stress testing, evaluation, retraining; model artefact registry on disk | Long-term storage of user objects; serving the UI directly |
| **PostgreSQL** | Durable state: observations, user scenarios, predictions and their deviations, model-version metadata | — |

The browser never calls the ML service directly. Spring Boot is the only client of FastAPI.

**Status:**
- **ML service [implemented]:** `ml-service/app/`.
- **Spring Boot backend [implemented]:** `backend/`, packages `controller`, `service`, `repository`, `entity`, `dto`, `client`, `exception` and `config`.
- **React frontend [implemented]:** `frontend/`, built with Vite, React 18, React Router and Recharts.
  - `src/pages/`: one file per page.
  - `src/components/`: shared UI, charts, and the deficit ruler used on every page that changes or tests a budget.
  - `src/api.js`: the backend client.
  - **Visual identity, "treasury paper":** each page opens with an indigo masthead overlaid with guilloche linework (the security pattern of banknotes and stamp paper) and a rosette watermark, on a blue-grey paper background.
    - Gold labels name sections; navigation is grouped Understand / Plan / Review.
    - Headline figures carry a coloured top rule keyed to what they measure.
    - Source Serif 4 is used for titles and figures, Noto Sans for text.
  - Charts use a three-colour palette validated for colour-vision deficiency: actual = blue solid line, model forecast = ochre dashed line with a shaded range, state estimate = teal hollow marker. Red and green are reserved for the 3% limit status.

All JSON between components is snake_case.

## 2. Data ownership and flow

1. **Ingestion** (ML service, offline script). `data/raw/*.xlsx` + the World Bank API → `data/processed/observations.csv` (long format) and `panel.csv` (state × fiscal year), plus `quality_report.json` and a SHA-256 hash for each raw file. A **dataset version** is the combination of the raw-file hashes.
2. **Import into PostgreSQL** (Spring Boot). `POST /api/admin/datasets/import` does five things:
   - reads ML `/dataset`, `/states`, `/indicators` and `/dataset/observations`;
   - inserts the observations under a new `dataset_version` (skipped if the content hash already exists);
   - mirrors the model registry (`/models`);
   - stores each root model version's out-of-time backtest predictions (`/backtest`) as BACKTEST forecasts;
   - scores every stored prediction that now has a matching observation.
3. **Training** (ML service). Trains on the processed panel and writes `models/<target>/v<N>/` (model file + `metadata.json`). Spring records each version in `model_version`.
4. **Usage.** UI → Spring → ML (`/forecast`, `/scenario`, `/optimize`, `/stress-test`) → Spring persists the inputs and results → UI.

## 3. Closed feedback loop

```
 observations (Accounts) ──train──▶ model vN ──predict──▶ forecast rows (persisted, tagged vN)
        ▲                                                       │
        │                                   new dataset version │ brings actuals for target years
        │                                                       ▼
 retrain / recalibrate ◀── deviation analysis ◀── prediction_deviation (pred vs actual, abs/% error)
   (model vN+1)            (bias by state & target,
                            error trend by version)
```

There are three sources of "actuals":

- **Backtest:** rolling-origin predictions for past years are scored immediately against the published Accounts.
- **Government estimates:** forecasts are compared with the states' own 2023-24 RE and 2024-25 BE, labelled as estimate comparisons, not actuals.
- **Future editions:** when a new Handbook edition is imported, every stored forecast whose target year now has an Actual/Accounts value is scored automatically.

**Recalibration** applies a shrunk per-state, per-target bias correction estimated from those deviations. It is kept only if it lowers out-of-time error on a hold-out; otherwise the uncalibrated model stays active. Details are in `ML_PIPELINE.md` §7.

## 4. Repository layout

```
JavaProject/
├── data/raw/rbi_hsis_2024_25/      RBI XLSX + MANIFEST.csv (immutable inputs)
├── data/raw/world_bank/            cached WDI JSON
├── data/processed/                 pipeline outputs (regenerable)
├── ml-service/                     Python: budget_ml/ (library), app/ (FastAPI), scripts/, tests/
├── backend/                        Spring Boot (Maven)
├── frontend/                       React (npm, Vite)
└── *.md                            documentation
```

## 5. Configuration

All configuration comes from environment variables, with defaults for local development only:

| Variable | Used by | Default |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | backend | `jdbc:postgresql://localhost:5432/budget_intel`, `budget`, *(no default for the password)* |
| `ML_SERVICE_URL` | backend | `http://localhost:8000` |
| `ML_TIMEOUT_MS` | backend | `30000` |
| `CORS_ALLOWED_ORIGINS` | backend | `http://localhost:5173` |
| `BUDGET_DATA_DIR`, `BUDGET_MODEL_DIR` | ml-service | `../data`, `./models` |
| `VITE_API_BASE_URL` | frontend | `http://localhost:8080/api` |

## 6. Error handling

- **ML service:** returns `{"error": code, "message": text, "details": [...]}` with status 400 (invalid input), 404 (unknown state/target), 422 (schema validation) or 409 (insufficient history).
- **Spring Boot:** translates errors through a single `@RestControllerAdvice` into `{timestamp, status, error, message, path, details}`. The cases are:

  | Situation | Status | Error |
  |---|---|---|
  | ML service unreachable or timed out | 503 | `ML_SERVICE_UNAVAILABLE` |
  | Database failure | 500 | `DATABASE_ERROR` (generic message) |
  | Bean Validation failure | 400 | `VALIDATION_ERROR` |

  Stack traces are logged on the server only.
- **React:** each page has explicit loading, empty and error states, and shows the `message` field.

## 7. Technology versions

Java 17, Spring Boot 3.x, Maven 3.9; Python 3.13, FastAPI, pandas, scikit-learn, SciPy; Node 22, React 18, Vite, Recharts; PostgreSQL ≥ 15.
