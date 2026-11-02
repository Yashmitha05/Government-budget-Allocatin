"""FastAPI ML service. Internal API consumed by the Spring Boot backend.

Run:  uvicorn app.main:app --port 8000   (from ml-service/)

Production settings (environment):
  ML_API_KEY  when set, every endpoint except /health requires the header X-ML-Api-Key with this value,
              so only the backend can call the service.
  R2_*        model registry persisted in Cloudflare R2 (see budget_ml/artifact_store.py).
"""
from __future__ import annotations

import hmac
import logging
import math
import os
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from budget_ml import feedback, registry
from budget_ml.artifact_store import ArtifactStore
from budget_ml.budget import baseline
from budget_ml.config import MODEL_DIR, PROCESSED_DIR
from budget_ml.errors import BudgetMLError, NotFound
from budget_ml.forecasting.service import forecast
from budget_ml.optimize import optimize_allocation
from budget_ml.preprocessing import FORECAST_TARGETS
from budget_ml.scenario import run_scenario
from budget_ml.stress import run_stress_test

from . import schemas as S
from .state import ServiceState

log = logging.getLogger("ml-service")


def clean(obj):
    """Make results JSON-safe: NaN/inf -> None, numpy scalars -> Python."""
    if isinstance(obj, dict):
        return {str(k): clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [clean(v) for v in obj]
    if isinstance(obj, (np.floating, float)):
        f = float(obj)
        return None if math.isnan(f) or math.isinf(f) else f
    if isinstance(obj, np.integer):
        return int(obj)
    if isinstance(obj, np.bool_):
        return bool(obj)
    return obj


def _error(status: int, code: str, message: str, details: list | None = None) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": code, "message": message, "details": details or []})


PUBLIC_PATHS = {"/health"}   # liveness only; reveals no budget data
_FROM_ENV = object()


def create_app(processed_dir: Path = PROCESSED_DIR, model_root: Path = MODEL_DIR,
               api_key: str | None | object = _FROM_ENV, artifact_store: ArtifactStore | None | object = _FROM_ENV) -> FastAPI:
    key = (os.environ.get("ML_API_KEY") or None) if api_key is _FROM_ENV else api_key

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        store = ArtifactStore.from_env(Path(model_root)) if artifact_store is _FROM_ENV else artifact_store
        if store is not None:
            store.sync_on_startup()      # restore persisted model versions before anything reads the registry
        app.state.store = store
        app.state.svc = ServiceState(processed_dir, model_root)
        yield

    app = FastAPI(title="Budget Intelligence ML Service", version="1.0.0", lifespan=lifespan,
                  description="Forecasting, scenario, optimisation, stress-testing and feedback endpoints. "
                              "Internal service; called only by the Spring Boot backend.")
    err = {"model": S.ErrorResponse}
    errors = {400: err, 404: err, 409: err, 422: err, 500: err}

    if key:
        @app.middleware("http")
        async def _require_service_key(request: Request, call_next):
            if request.url.path not in PUBLIC_PATHS:
                given = request.headers.get("x-ml-api-key", "")
                if not hmac.compare_digest(given.encode(), key.encode()):
                    return _error(401, "UNAUTHORIZED", "This service only accepts requests from the backend")
            return await call_next(request)

    def svc(request: Request) -> ServiceState:
        return request.app.state.svc

    @contextmanager
    def _persisted(request: Request, target: str):
        """Around a registry write: refresh from R2 first (another instance may have added versions),
        then upload the target's folder so the new version outlives this container."""
        store = request.app.state.store
        if store is not None:
            store.pull()
        yield
        if store is not None:
            store.push(target)

    @app.exception_handler(BudgetMLError)
    async def _domain(_, exc: BudgetMLError):
        return _error(exc.status, exc.code, exc.message, [str(d) for d in exc.details])

    @app.exception_handler(registry.ModelNotFound)
    async def _no_model(_, exc):
        return _error(404, "MODEL_NOT_FOUND", str(exc))

    @app.exception_handler(RequestValidationError)
    async def _validation(_, exc: RequestValidationError):
        details = [f"{'.'.join(str(p) for p in e['loc'] if p != 'body')}: {e['msg']}" for e in exc.errors()]
        return _error(422, "VALIDATION_ERROR", "Request validation failed", details)

    @app.exception_handler(Exception)
    async def _unexpected(_, exc: Exception):
        log.exception("unhandled error")
        return _error(500, "INTERNAL_ERROR", "Unexpected error in the ML service")

    # ------------------------------------------------------------------------------------- reference data
    @app.get("/health", response_model=S.HealthResponse)
    def health(request: Request):
        s = svc(request)
        return {"status": "ok", "dataset_label": s.metadata["label"], "panel_rows": len(s.panel),
                "active_models": s.active_models()}

    @app.get("/states", response_model=list[S.StateOut])
    def states(request: Request):
        return svc(request).state_list()

    @app.get("/indicators", response_model=list[S.IndicatorOut])
    def indicators(request: Request):
        return svc(request).indicators()

    @app.get("/dataset", response_model=S.DatasetResponse)
    def dataset(request: Request):
        s = svc(request)
        q = s.quality
        return clean({**s.metadata, "coverage": q["coverage"], "aggregate_check": q["aggregate_check"],
                      "outlier_flag_count": len(q["outlier_flags"]), "population_sum_vs_wdi": q["population_sum_vs_wdi"],
                      "structural_breaks": q["structural_breaks"]})

    @app.get("/dataset/observations", response_model=S.ObservationsResponse)
    def observations(request: Request, state_code: Optional[str] = Query(None), indicator: Optional[str] = Query(None)):
        s = svc(request)
        df = s.observations
        if state_code:
            df = df[df.state_code == state_code]
        if indicator:
            df = df[df.indicator == indicator]
        cols = ["state_code", "indicator", "fiscal_year", "value", "data_status", "footnote_flag", "source_table"]
        recs = df[cols].astype(object).where(pd.notna(df[cols]), None).to_dict(orient="records")
        return {"dataset_label": s.metadata["label"], "count": len(recs), "observations": recs}

    @app.post("/dataset/reload", response_model=S.HealthResponse)
    def reload(request: Request):
        s = svc(request)
        with s.write_lock:
            s.reload()
        return health(request)

    @app.get("/baseline", response_model=S.BaselineOut, responses=errors)
    def get_baseline(request: Request, state_code: str, fiscal_year: Optional[str] = None):
        return clean(baseline(svc(request).panel, state_code, fiscal_year).as_dict())

    @app.get("/impact", response_model=list[S.ImpactOut])
    def impact(request: Request):
        return clean(svc(request).impact_results)

    # ---------------------------------------------------------------------------------------- models
    @app.get("/models", response_model=list[S.ModelVersionOut])
    def models(request: Request, target: Optional[str] = None):
        root = svc(request).model_root
        targets = [target] if target else FORECAST_TARGETS
        if target and target not in FORECAST_TARGETS:
            raise NotFound(f"Unknown forecast target '{target}'")
        return clean([m for t in targets for m in registry.list_versions(t, root)])

    @app.get("/backtest", response_model=S.BacktestResponse, responses=errors)
    def get_backtest(request: Request, target: str, model_type: str, horizon: Optional[int] = Query(None, ge=1, le=3)):
        """Out-of-time backtest predictions (scripts/train_models.py) for one target and model type."""
        bt = svc(request).backtest
        if target not in FORECAST_TARGETS:
            raise NotFound(f"Unknown forecast target '{target}'")
        if bt.empty:
            raise NotFound("No backtest predictions available; run scripts/train_models.py")
        rows = bt[(bt.target == target) & (bt.model == model_type)]
        if horizon:
            rows = rows[rows.horizon == horizon]
        if rows.empty:
            raise NotFound(f"No backtest predictions for {target} / {model_type}")
        cols = ["target", "model", "state_code", "origin", "horizon", "target_year", "actual", "predicted"]
        return {"target": target, "model_type": model_type, "count": len(rows),
                "rows": clean(rows[cols].to_dict(orient="records"))}

    @app.get("/models/{target}/{version}", response_model=S.ModelVersionOut, responses=errors)
    def model_version(request: Request, target: str, version: int):
        _, meta = registry.load(target, version, svc(request).model_root)
        return clean(meta)

    # ------------------------------------------------------------------------------------ computations
    @app.post("/forecast", response_model=S.ForecastResponse, responses=errors)
    def post_forecast(request: Request, body: S.ForecastRequest):
        s = svc(request)
        return clean(forecast(s.panel, body.state_code, body.target, body.horizon, body.origin_fiscal_year,
                              body.model_version, s.model_root))

    @app.post("/scenario", response_model=S.ScenarioResponse, responses=errors)
    def post_scenario(request: Request, body: S.ScenarioRequest):
        s = svc(request)
        return clean(run_scenario(s.panel, body.state_code, body.fiscal_year, body.scenario_type, s.impact_results,
                                  body.allocations, body.adjustments_pct))

    @app.post("/optimize", response_model=S.OptimizeResponse, responses=errors)
    def post_optimize(request: Request, body: S.OptimizeRequest):
        s = svc(request)
        bounds = {k: v.model_dump() for k, v in (body.bounds or {}).items()}
        return clean(optimize_allocation(s.panel, body.state_code, body.fiscal_year, body.objective, s.impact_results,
                                         body.total_budget, bounds, body.social_floor, body.enforce_deficit_limit,
                                         body.weights))

    @app.post("/stress-test", response_model=S.StressTestResponse, responses=errors)
    def post_stress(request: Request, body: S.StressTestRequest):
        s = svc(request)
        return clean(run_stress_test(s.panel, body.state_code, body.fiscal_year, body.allocation,
                                     body.overrides.model_dump() if body.overrides else None, body.scenarios))

    @app.post("/evaluate", response_model=S.EvaluateResponse, responses=errors)
    def post_evaluate(body: S.EvaluateRequest):
        return clean(feedback.evaluate([r.model_dump() for r in body.records], list(body.group_by)))

    @app.post("/calibrate", response_model=S.CalibrateResponse, responses=errors)
    def post_calibrate(request: Request, body: S.CalibrateRequest):
        s = svc(request)
        cal = pd.DataFrame([r.model_dump() for r in body.calibration_records])
        ev = pd.DataFrame([r.model_dump() for r in body.evaluation_records])
        with s.write_lock, _persisted(request, body.target):
            return clean(feedback.calibrate_version(body.target, cal, ev, s.model_root, body.model_version))

    @app.post("/retrain", response_model=S.RetrainResponse, responses=errors)
    def post_retrain(request: Request, body: S.RetrainRequest):
        s = svc(request)
        with s.write_lock, _persisted(request, body.target):
            return clean(feedback.retrain(s.panel, body.target, s.metadata, s.model_root, body.activate_if_better))

    return app


app = create_app()
