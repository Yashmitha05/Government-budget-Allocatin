# API Documentation

Every body is JSON with snake_case field names. Money is in ₹ crore; `fiscal_year` is written like `"2023-24"`.

## 1. ML service (FastAPI, internal; called only by Spring Boot)

Status: **[implemented]**. The code is in `ml-service/app/` (`main.py` for routes, `schemas.py` for Pydantic request/response models). It is covered by `tests/test_api.py` (24 tests) and was smoke-tested over HTTP. The machine-readable contract is at `GET /openapi.json`, and an interactive version at `/docs`.

Run:
```bash
cd ml-service && .venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
```

Base URL: `${ML_SERVICE_URL}` (default `http://localhost:8000`).

**Errors** all use one shape: `{"error": CODE, "message": text, "details": [text]}`.

| Status | Code | Meaning |
|---|---|---|
| 400 | `INVALID_INPUT` | Values that pass the schema but are invalid for the data, e.g. a −150% adjustment |
| 404 | `NOT_FOUND`, `MODEL_NOT_FOUND` | Unknown state, target or model version |
| 409 | `INSUFFICIENT_HISTORY` | Not enough consecutive actual years |
| 422 | `VALIDATION_ERROR` | Schema violation or malformed JSON; `details` lists the field paths |
| 422 | `INFEASIBLE_CONSTRAINTS` | No allocation satisfies the constraints |
| 500 | `INTERNAL_ERROR` | Unexpected error; generic message only, the stack trace is logged on the server |

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness, dataset label, active model version per target |
| GET | `/states` | States/UTs: code, name, category, formation year, `has_budget_data` |
| GET | `/indicators` | Indicator catalogue (code, name, unit, category, source, forecast target) |
| GET | `/dataset` | Dataset metadata: label, SHA-256 hashes, coverage, validation summary, structural breaks |
| GET | `/dataset/observations?state_code=&indicator=` | Observations in long format (used by the backend import) |
| POST | `/dataset/reload` | Re-read `data/processed/` after a rebuild |
| GET | `/baseline?state_code=&fiscal_year=` | Budget baseline (sector split, deficits, GSDP, population) |
| GET | `/impact` | Spending → indicator elasticities, intervals, `supported` flags |
| GET | `/models?target=` | All model versions with metrics |
| GET | `/backtest?target=&model_type=&horizon=` | Out-of-time backtest predictions vs actuals for one target and model type |
| GET | `/models/{target}/{version}` | One model version |
| POST | `/forecast` | Multi-step forecast with intervals and government RE/BE |
| POST | `/scenario` | Allocation scenario → fiscal and indicator effects |
| POST | `/optimize` | Constrained allocation |
| POST | `/stress-test` | Normal vs stressed comparison |
| POST | `/evaluate` | Deviation records and grouped accuracy metrics |
| POST | `/calibrate` | Bias-calibrate a model version from deviation records → new version, activated only if better |
| POST | `/retrain` | Re-run selection and training on the current dataset → new version |

### POST `/forecast`

Request:
```json
{"state_code": "KA", "target": "revenue_expenditure", "horizon": 3, "origin_fiscal_year": null}
```
`origin_fiscal_year: null` means "use the latest year with Accounts/Actual data".

Response:
```json
{"state_code": "KA", "target": "revenue_expenditure", "unit": "INR_CRORE",
 "model": {"target": "revenue_expenditure", "version": 1, "model_type": "ridge_growth"},
 "history": [{"fiscal_year": "2021-22", "value": 0.0, "data_status": "ACCOUNTS"}],
 "government_estimates": [{"fiscal_year": "2023-24", "value": 0.0, "data_status": "RE"}],
 "predictions": [{"fiscal_year": "2023-24", "horizon": 1, "value": 0.0, "lower": 0.0, "upper": 0.0}],
 "backtest_metrics": {"mae": 0.0, "rmse": 0.0, "mape": 0.0, "r2": 0.0, "n": 0}}
```
Errors: 404 (unknown state or target); 409 (fewer than 4 usable years for the state).

Sectors are always `social_sector`, `capital_outlay` and `other_revenue`. `health` is accepted in scenarios as a sub-line of `social_sector`. `fiscal_year: null` means the latest budget year (2024-25 BE).

### POST `/scenario`

Request:
```json
{"state_code": "KA", "fiscal_year": "2024-25", "scenario_type": "CUSTOM",
 "allocations": {"social_sector": 160000, "capital_outlay": 55000}}
```
- `scenario_type`: `BALANCED` | `HEALTH_PRIORITY` | `SOCIAL_PRIORITY` | `INFRASTRUCTURE_PRIORITY` | `CUSTOM`.
- For `CUSTOM`, `allocations` can be replaced by `{"adjustments_pct": {"health": 10, "capital_outlay": -5}}`.

Response:
- `baseline{}`
- `sectors[]`: baseline, scenario, change, change_pct, shares, per_capita_*, per_capita_all_state_median
- `fiscal_effects{baseline, scenario}`: GFD, revenue deficit, gfd_pct_gsdp, breaches_frbm_gfd_limit
- `indicator_effects[]`: indicator, current_value, predicted_value (`null` when not supported), lower, upper, supported, elasticity, elasticity_ci, method_note
- `notes[]`

### POST `/optimize`

Request:
```json
{"state_code": "KA", "fiscal_year": null, "objective": "MIN_ADJUSTMENT", "total_budget": null,
 "bounds": {"capital_outlay": {"min_share": 0.2, "max": null}},
 "social_floor": "NONE", "enforce_deficit_limit": true, "weights": null}
```
- `objective`: `MIN_ADJUSTMENT` | `MAX_CAPITAL_OUTLAY` | `OUTCOME`. `OUTCOME` returns 400 while no impact relationship is supported.
- `social_floor`: `NONE` | `CURRENT` | `MEDIAN`.
- `total_budget: null` means the current discretionary total, reduced if necessary to meet the 3% GFD/GSDP limit.

Response: `status` (`OPTIMAL`), `objective_value`, `total_budget`, `current_total`, `deficit_room`, `allocation[]` (sector, current, recommended, change, change_pct, min, max, per-capita), `binding_constraints[]`, `fiscal_effects{current, recommended}`, `indicator_effects[]`.

Errors: 422 `INFEASIBLE_CONSTRAINTS`, with `details` giving the conflicting sums.

### POST `/stress-test`

Request:
```json
{"state_code": "KA", "fiscal_year": null, "allocation": null,
 "overrides": {"revenue_shock_pct": null, "inflation_pct": null,
               "population_excess_growth_pct": null, "gsdp_shortfall_pct": null},
 "scenarios": ["REVENUE_SHOCK", "INFLATION", "POPULATION", "DOWNTURN", "COMBINED"]}
```
- `allocation: null` tests the baseline budget; otherwise pass sector amounts, e.g. from a scenario or optimisation.
- A `null` override means "use the data-calibrated default".

Response:
- `calibration{}`: each shock's value and historical basis, plus tax buoyancy with its interval.
- `results[]`: scenario, metric, normal, stressed, change_pct, breaches_limit.

Metrics: own_tax_revenue, revenue_receipts, revenue_deficit, gross_fiscal_deficit, gsdp, gfd_pct_gsdp, interest_pct_revenue_receipts, real_per_capita_social_sector, real_per_capita_capital_outlay, adjustment_needed_for_frbm.

### POST `/evaluate`

Request:
```json
{"records": [{"prediction_id": 1, "state_code": "KA", "target": "revenue_expenditure",
              "target_year": "2023-24", "model_version": 1, "predicted": 247150, "actual": 240731}],
 "group_by": ["state_code"]}
```
`predicted` and `actual` must be > 0. `group_by` accepts any of `state_code`, `target`, `model_version`, `target_year`.

Response:
- `records[]`: the inputs plus `absolute_error` and signed `percentage_error`.
- `groups[]`: n, MAE, RMSE, MAPE, median APE, R², `bias_pct` (mean signed % error).

### POST `/calibrate`

Request:
```json
{"target": "social_sector_expenditure", "model_version": null,
 "calibration_records": [{"state_code": "KA", "target_year": "2019-20", "predicted": 1.0, "actual": 1.0}],
 "evaluation_records": [{"state_code": "KA", "target_year": "2021-22", "predicted": 1.0, "actual": 1.0}]}
```
Evaluation years must all be later than the calibration years; otherwise 400.

Response: the new model version (metadata as in `/models`), including `calibration` and `calibration_evaluation` (MAPE before and after, `improved`), plus `is_active` and `previous_active_version`.

### POST `/retrain`

Request: `{"target": "revenue_expenditure", "activate_if_better": true}`.

Response: the new version's metadata plus `comparison` (`parent_version`, `parent_mape`, `new_mape`, `new_dataset`). The new version is activated if it is no worse than the parent, or always when the dataset has changed.

## 2. Backend REST API (Spring Boot; consumed by React)

Status: **[implemented]**. The code is in `backend/` (controllers in `com.budgetintel.controller`). It is tested by web-layer, database and live end-to-end tests (`TESTING.md`).

Run:
```bash
cd backend && mvn spring-boot:run
```
It listens on port 8080 and reads `DB_*`, `ML_SERVICE_URL` and `CORS_ALLOWED_ORIGINS` from the environment or the project-root `.env`.

**Conventions:**
- JSON uses **snake_case**, the same as the ML service; DTOs and stored result snapshots share one convention.
- Money is in ₹ crore; years are written like `2024-25`.
- Validation is Bean Validation on every request body and query parameter.

**Errors** all use `{timestamp, status, error, message, path, details[]}`. Field names in `details` use the JSON names (`state_code: …`).

| Status | `error` | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bean Validation failure, malformed JSON, bad or missing parameter; also ML schema errors |
| 400 | `INVALID_INPUT` | Valid shape but invalid meaning (e.g. scenario from another state, non-target indicator) |
| 404 | `NOT_FOUND` | Unknown state, indicator, record id or route |
| 409 | `NO_DATASET` | Nothing imported yet |
| 409 | `INSUFFICIENT_HISTORY` | Passed through from the ML service |
| 502 | `ML_SERVICE_ERROR` | The ML service failed internally (no internals exposed) |
| 503 | `ML_SERVICE_UNAVAILABLE` | The ML service is unreachable or timed out |
| 500 | `DATABASE_ERROR`, `INTERNAL_ERROR` | Generic message only; details are logged on the server |

ML client errors (400, 404, 409, 422 `INFEASIBLE_CONSTRAINTS`) pass through with their code and details.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/states` | States/UTs (code, name, category, `has_budget_data`) |
| GET | `/api/indicators` | Indicator catalogue |
| GET | `/api/datasets/current` | Imported dataset: label, hash, sources, validation summary |
| POST | `/api/admin/datasets/import` | Import the ML service's dataset; sync models; load backtests; score predictions. Idempotent by content hash |
| GET | `/api/budget-records?state=&indicator=&from_year=&to_year=` | Observations with their status (ACCOUNTS/ACTUAL/RE/BE/…) |
| GET | `/api/budget-records/compare?state=&year=&previous_year=` | Year-on-year change per fiscal/economic indicator |
| GET | `/api/budget-records/baseline?state=&year=` | Sector split and fiscal position of a budget year |
| GET | `/api/dashboard/summary?state=` | Latest-year totals, sector shares, GFD/GSDP and "deficit limit used", revenue-expenditure and own-tax-revenue forecasts |
| GET | `/api/impact-models` | Spending → indicator elasticities and their `supported` flags |
| POST | `/api/forecasts` | `{state_code, indicator, horizon}`. Calls the ML service; stores LIVE predictions (repeat requests reuse the same Prediction IDs); compares them with the state's RE/BE |
| GET | `/api/forecasts?state=&indicator=&kind=LIVE\|BACKTEST` | Stored predictions |
| POST | `/api/scenarios` | `{name, state_code, fiscal_year?, scenario_type, allocations? \| adjustments_pct?, notes?}` → 201 with the stored scenario and its `result` snapshot |
| GET | `/api/scenarios?state=`, `/api/scenarios/{id}`, `/api/scenarios/compare?ids=1,2` | List, fetch, compare (2–6 scenarios) |
| DELETE | `/api/scenarios/{id}` | Delete → 204 |
| POST | `/api/optimizations` | `{state_code, fiscal_year?, objective?, total_budget?, bounds?, social_floor?, enforce_deficit_limit?}` → 201. `status` is `OPTIMAL`, or `INFEASIBLE` with `details` (stored, not an error) |
| GET | `/api/optimizations?state=`, `/api/optimizations/{id}` | List, fetch |
| POST | `/api/stress-tests` | `{state_code, fiscal_year?, source_type: CURRENT\|SCENARIO\|OPTIMIZATION, source_id?, overrides?, scenarios?}` → 201. For SCENARIO/OPTIMIZATION, the stored allocation is tested; the source must belong to the same state and year |
| GET | `/api/stress-tests?state=`, `/api/stress-tests/{id}` | List, fetch |
| GET | `/api/feedback/deviations?state=&indicator=&model_version=&basis=ACTUAL\|GOVT_RE\|GOVT_BE&kind=` | Prediction-accuracy records (Prediction ID, years, horizon, model version, predicted, actual, absolute and % error) |
| GET | `/api/feedback/summary` | MAE, MAPE and mean signed % error by indicator, model version, basis, kind and horizon |
| POST | `/api/feedback/evaluate` | Score stored predictions that now have matching observations |
| GET | `/api/models?indicator=` | Model versions (metrics, configuration, parent, active, calibrated) |
| POST | `/api/models/sync` | Re-read the ML registry |
| POST | `/api/models/{indicator}/retrain` | Retrain via the ML service, then sync and score |
| POST | `/api/models/{indicator}/calibrate` | Calibrate from stored one-year-ahead ACTUAL deviations (latest 2 target years held out) |
| GET | `/actuator/health` | Liveness |

Deviation bases: `ACTUAL` is scored against Accounts/Actual. `GOVT_RE` and `GOVT_BE` compare a forecast with the state government's own Revised or Budget Estimate. Those are comparisons with official estimates, not accuracy measures.

The ML service also gained `GET /backtest?target=&model_type=&horizon=` (§1). The backend uses it to load real out-of-time prediction-vs-actual records at import.

Base URL: `/api`.

All errors use this shape: `{timestamp, status, error, message, path, details[]}`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/states` | List states and UTs |
| GET | `/indicators` | List indicators (code, unit, category) |
| GET | `/budget-records?state=&indicator=&fromYear=&toYear=` | Observations |
| GET | `/budget-records/compare?state=&year=&previousYear=` | Year-over-year comparison by indicator |
| GET | `/dashboard/summary?state=` | Totals, forecasts, utilisation, sector shares |
| POST | `/forecasts` | Run a forecast via ML and persist it as LIVE predictions |
| GET | `/forecasts?state=&indicator=` | Stored predictions |
| POST / GET | `/scenarios`, `/scenarios/{id}` | Create (ML `/scenario`) and list |
| GET | `/scenarios/compare?ids=1,2,3` | Side-by-side comparison |
| POST / GET | `/optimizations`, `/optimizations/{id}` | Run and fetch |
| POST / GET | `/stress-tests`, `/stress-tests/{id}` | Run and fetch |
| GET | `/feedback/deviations?state=&indicator=&modelVersion=` | Prediction accuracy records |
| POST | `/feedback/evaluate` | Score all predictions that now have actuals |
| GET | `/models` | Model versions |
| POST | `/models/{target}/retrain` | Retrain or calibrate via ML |
| POST | `/admin/datasets/import` | Import the current processed dataset from ML |
