# ML service (FastAPI) image, built with the REPOSITORY ROOT as the build context.
#
# This root-level copy exists for Google Cloud Run's GitHub (Cloud Build) deployment, which uses the
# Dockerfile's own directory as the build context. The image copies from both ml-service/ and the
# root-level data/ folder, so the Dockerfile must sit at the root. It is identical in behaviour to
# ml-service/Dockerfile (kept for `docker build -f ml-service/Dockerfile .` and ml-service/cloudbuild.yaml).
#   docker build -t budget-ml .
#
# Layout inside the image mirrors the repository: /srv/ml-service (code, seed models, reports) and /srv/data.
# No secrets are baked in: ML_API_KEY and the R2_* credentials are supplied by Cloud Run at runtime.
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /srv/ml-service

COPY ml-service/requirements.txt ./
RUN pip install -r requirements.txt

COPY ml-service/budget_ml ./budget_ml
COPY ml-service/app ./app
COPY ml-service/scripts ./scripts
# seed artefacts: the trained model registry (restored from / persisted to R2 at runtime) and backtest records
COPY ml-service/models ./models
COPY ml-service/reports ./reports
COPY data /srv/data

RUN useradd --create-home --uid 10001 app && chown -R app /srv
USER app

ENV BUDGET_DATA_DIR=/srv/data \
    BUDGET_MODEL_DIR=/srv/ml-service/models \
    PORT=8080
EXPOSE 8080

# Cloud Run sets PORT; shell form so it is expanded at start-up.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
