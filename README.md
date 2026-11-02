# Government Budget Intelligence and Decision Support System

A decision-support system for Indian state budget planning. It forecasts fiscal indicators, simulates allocation scenarios, optimises allocations under fiscal constraints, stress-tests them, and keeps a closed feedback loop that compares predictions with published actuals in order to recalibrate the models.

Data: RBI *Handbook of Statistics on Indian States 2024-25* (state fiscal, economic and outcome tables) and World Bank WDI (national series). See `DATASET_SELECTION.md`. No synthetic data is used.

> **New to the project? Start with [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md).** It explains in plain language what the system does, the folder structure, how each project objective is met, and how the ML models are trained on the dataset.

## Status

| Phase | Status |
|---|---|
| 1. Dataset research and selection | Done |
| 2. Architecture, database, ML pipeline and API design | Done (design documents) |
| 3. Data pipeline + baseline forecasting backtest | Done |
| 4. ML: forecasting, impact models, scenarios, optimisation, stress tests, feedback loop, model registry | Done, 105 tests passing |
| 5. FastAPI ML service | Done: 17 endpoints, 129 tests passing in total |
| 6. Spring Boot backend (PostgreSQL, Flyway, JPA, ML client) | Done: 23 default and 6 live end-to-end tests passing; real dataset imported into `budget_intel` |
| 7. React dashboard (7 pages) | Done: 10 unit tests; full browser check against the live stack (see TESTING.md) |
| 8–10. Integration hardening, final testing, refinement | Not started |

## Running the data pipeline

```bash
cd ml-service
python -m venv .venv && .venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe scripts/build_dataset.py        # raw XLSX -> data/processed/
.venv/Scripts/python.exe scripts/backtest_forecasts.py   # Phase 3 one-step comparison -> reports/
.venv/Scripts/python.exe scripts/train_models.py         # select, train, register models -> models/
.venv/Scripts/python.exe scripts/calibrate_models.py     # feedback-loop calibration -> new versions
.venv/Scripts/python.exe -m pytest -q
.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000   # ML API; docs at http://localhost:8000/docs
```

## Running the backend

Needs PostgreSQL with a database named `budget_intel`, and the ML service running.

```bash
cd backend
mvn spring-boot:run                                    # http://localhost:8080; Flyway creates the schema
curl -X POST localhost:8080/api/admin/datasets/import   # first run: load data, models, backtests
mvn test                                               # needs database budget_intel_test
mvn test -Dgroups=live -DexcludedTestGroups=none       # end-to-end, needs the ML service
```

## Running the frontend

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173; talks to the backend at VITE_API_BASE_URL (default http://localhost:8080/api)
npm test
```

**Pages:**
- **Dashboard:** latest budget, deficit against the 3% limit, sector split, forecasts.
- **Budget Analysis:** any year, year-on-year change, any indicator over time.
- **Scenario Simulator:** presets or sliders; saved and compared.
- **Optimization:** goal and limits → recommended allocation.
- **Stress Testing:** the current budget, a scenario or an optimisation, under data-sized shocks.
- **Prediction Accuracy:** the feedback loop, prediction records and model versions.
- **Data & Methods:** sources, methods and limitations.

Start order: PostgreSQL → ML service → backend → frontend. On first use, select **Load RBI dataset** in the top bar (or `POST /api/admin/datasets/import`).

Local configuration (database credentials, service URLs) lives in `.env` at the project root (git-ignored); see `.env.example`.

The raw RBI files must be in `data/raw/rbi_hsis_2024_25/` (see `MANIFEST.csv`). World Bank series are cached in `data/raw/world_bank/`; pass `--refresh-wdi` to re-download them.

## Documentation

`PROJECT_GUIDE.md` (start here) · `DATASET_SELECTION.md` · `SYSTEM_ARCHITECTURE.md` · `DATABASE_DESIGN.md` · `API_DOCUMENTATION.md` · `ML_PIPELINE.md` · `MODEL_EVALUATION.md` · `TESTING.md`. `RESEARCH_GAP.md` will be written in the final phase.
