# Testing

## ML library: `ml-service/tests/`

Run:

```bash
cd ml-service && .venv/Scripts/python.exe -m pytest -q
```

Last result: **129 passed** (2026-10-23, about 45 s): 105 library tests and 24 API tests.

The tests run on the real RBI files and the cached World Bank data. Models are trained into temporary registries, so the real `models/` folder is never touched. Hand-made inputs appear only in small unit tests of formulas, as noted below.

| File | What it checks |
|---|---|
| `test_parsing.py` | Year and cell parsing; state-name resolution; real table T164: year span, Accounts/Actual/RE/BE status, Telangana missing cells not imputed, regression values |
| `test_preprocessing.py` | Duplicate handling; state sums match RBI's published totals; panel shape and status; derived population formula and its ratio to the World Bank figure; outliers kept; AP break marked |
| `test_forecasting.py` | Metric formulas; targets are actuals only; no window crosses a break; feature arithmetic; recursive forecast matches a hand calculation; **no future values used** (truncated history gives an identical forecast); **no look-ahead in the backtest** (every fit uses only earlier origins); minimum training history; selection rule and interval coverage (small hand-made tables); trained model metadata; live forecast starts at the last Actual and returns RE/BE separately; error cases (unknown state or target, horizon > 3, insufficient history) |
| `test_decision_support.py` | Sector split is exact; latest year is 2024-25 BE with estimated health; deficit moves one-for-one with spending; impact sample excludes UTs and break years; bootstrap is reproducible; **current data supports no impact relationship**; presets keep the total constant; health priority amount; custom +20% capital breaches the 3% limit; invalid scenarios rejected; optimiser: feasible budget unchanged, Punjab cut to exactly 3%, bounds respected, UP median floor infeasible, OUTCOME refused without a supported elasticity; OUTCOME solver check with an *assumed* elasticity; invalid bounds; stress calibration from data, normal case = baseline, stress worsens GFD/GSDP, overrides, buoyancy range |
| `test_feedback.py` | Deviation formulas; non-positive values rejected; grouped evaluation; calibration shrinkage formula (hand-made records); calibrated child version on real backtest deviations, activated only if it improves; overlapping calibration and evaluation years rejected; retraining creates a child version |

| `test_api.py` | FastAPI over HTTP via `TestClient`, on the real processed dataset and a **temporary copy** of the model registry: every endpoint's success path; the error shape `{error, message, details}` for 404/409/422/400 cases and malformed JSON; schema validation (horizon > 3, lower-case state code, unknown sector or scenario, negative budget, share > 1); optimiser infeasibility (422) and OUTCOME refusal (400); calibrate and retrain create new versions; an unexpected exception returns a generic 500 without internal details or a traceback |

**Manual HTTP smoke test** (uvicorn on port 8000): `/health`, `/forecast` (MH own tax revenue), `/optimize` (KA, deficit-capped), a validation error, and `/openapi.json` all returned the expected results.

The ML API suite now has 25 tests, including `/backtest`; 130 ML tests in total.

## Backend: `backend/src/test/`

Run:

```bash
cd backend && mvn test
```

**Default run: 23 passed** (2026-10-23). It needs PostgreSQL with the `budget_intel_test` database.

| Class | Type | What it checks |
|---|---|---|
| `MlServiceClientTest` (5) | HTTP client against `MockRestServiceServer` | snake_case request and response mapping; ML error body → `MlServiceException` with code and details; non-JSON 500 → generic error; unreachable host → `MlServiceUnavailableException`; null fields dropped from requests |
| `ErrorHandlingWebTest` (11) | `@WebMvcTest`, services mocked | snake_case JSON; Bean Validation reports JSON field names; query-parameter and missing-parameter validation; malformed JSON; ML 4xx passed through; ML 422 validation → 400; ML 500 → 502 with no internals; ML down → 503; database error → generic 500; unexpected error → generic 500 with no stack trace; bad path or enum parameter → 400 |
| `ServiceDatabaseTest` (7) | `@SpringBootTest` on the **real PostgreSQL test database** (Flyway schema), ML client mocked | Import loads observations, syncs versions (parent and active), loads and scores backtests, and is idempotent; deviation arithmetic and accuracy summary; LIVE forecasts reuse Prediction IDs and are compared with RE/BE; rejection before import; calibration holds out the latest two target years; INFEASIBLE optimisation stored with reasons; stress test uses the saved scenario's amounts and rejects a source from another state or a missing source id; unknown state or indicator |

Stub ML responses in `ServiceDatabaseTest` use Karnataka revenue-expenditure magnitudes. They test the mechanics only; no result in the system or the documentation comes from them.

**Live end-to-end run: 6 passed**:

```bash
mvn test -Dgroups=live -DexcludedTestGroups=none
```

`LiveIntegrationTest` goes HTTP → Spring Boot → PostgreSQL (test database) → the running FastAPI service → real models, on the real dataset:
- import of 14,730 observations, 10 model versions and >2,000 scored backtests, and an idempotent re-import;
- published values (KA GFD 2024-25 BE = ₹82,981 crore) and year comparison;
- dashboard and forecast, with RE/BE comparisons;
- scenario breaching the 3% limit; Punjab optimised to exactly 3%; Uttar Pradesh infeasible with a median floor; combined stress test on the optimised allocation;
- accuracy summary and the five active models;
- validation error from the ML service.

It does not call retrain or calibrate, because those add versions to the real model registry. They are covered by `ServiceDatabaseTest` and the ML API tests on a copy of the registry.

**Manual run against the main database:**
- Imported the dataset into `budget_intel` in 49 s.
- Exercised every endpoint group with curl (results in this phase's log): forecasts, government comparisons, summary, records, compare, scenarios, compare, optimisations (feasible and infeasible), stress tests from a scenario and from an optimisation, dashboard, and the error cases.

## Frontend: `frontend/src/test/`

Run:

```bash
cd frontend && npm test
```

**10 passed** (Vitest + Testing Library):
- Indian number formatting (₹2,90,531 cr, lakh crore, axis units) and missing-value handling.
- The 3% limit rule: exactly 3.00% is within the limit.
- The deficit ruler's labels.
- API client: backend error bodies become `ApiError` with code and details; an unreachable backend is reported; query strings drop empty parameters.
- The "analytics service down" hint.
- Unsupported indicator effects never show a predicted value.

`npm run build` completes with no errors.

## Browser end-to-end check (2026-10-23)

Run in the desktop app's browser pane, React (5173) → Spring Boot (8080) → PostgreSQL → FastAPI (8000), on the real dataset. Checked at 1440×900 and at phone width (375×812).

| Page | What was exercised | Result |
|---|---|---|
| Dashboard | Karnataka and Punjab; KPIs, deficit ruler, sector shares, two forecast charts with RE/BE markers | Values match the backend; Punjab shown above the limit with a link to Optimization |
| Budget Analysis | Year comparison, baseline split, indicator trend; switched to revenue deficit (negative values) | Axis switches to automatic range for negative values |
| Scenario Simulator | Custom +12% capital outlay → saved, 2.88% → 3.10% "Above limit"; compared two saved scenarios | Saved list and side-by-side comparison correct |
| Optimization | Punjab smallest change → exactly 3.00%; median social floor → "Not possible" with reasons; outcome goal disabled with explanation | Correct |
| Stress Testing | Opened from an optimization via link (source pre-selected); all five conditions | Shock sizes and their data basis shown |
| Prediction Accuracy | Overview, predicted-vs-actual chart, records paging (447), "Forecast now", "Check predictions" | Numbers match MODEL_EVALUATION.md (e.g. revenue expenditure 4.6% vs 10.4% simple rule) |
| Data & Methods | Dataset metadata, sources, impact-model table | Correct |
| Failure path | Stopped the ML service | Dashboard shows "analytics service unavailable" with a fix hint and retry; Budget Analysis still shows database-backed tables and charts |

**Issues found and fixed during this check:**
- **Negative optimiser bounds (ML service):** Punjab's "Other spending" lower bound was −₹29,206 crore, caused by PB 2016-17, a year whose published components exceed revenue expenditure. `historical_shares` now skips years without a valid split. Regression test added; ML suite **131 passed**.
- **Exactly 3% shown as a breach:** a deficit of exactly 3.00% was labelled "Above limit" (floating point). Fixed with a shared `aboveLimit` rule.
- **Overflow at phone width:** Budget Analysis and Prediction Accuracy overflowed horizontally at 375px. Fixed; all pages now have 0px overflow.
- **Chart axis units:** axes mixed units (40k, 1.2L). Now round ticks in one unit per axis.
- Number formatting of large indicator values, tiny residues shown as "₹0 cr", cramped stress-test cards, and React Router v7 warnings.

After the fixes, a click-through of all pages logs no console errors or warnings.

## Not yet tested

Retrain and calibrate were not clicked in the browser, because they add versions to the real model registry. They are covered by backend and ML tests on copies. Phases 8–10 remain: full integration hardening, the final test pass and refinement.
