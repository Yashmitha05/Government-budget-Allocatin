# ML Pipeline

Everything described here is implemented in `ml-service/budget_ml/` and covered by `ml-service/tests/` (105 tests). Measured results are in `MODEL_EVALUATION.md`.

| Module | Purpose |
|---|---|
| `ingest/rbi_hsis.py`, `ingest/world_bank.py` | Parse RBI XLSX tables; fetch and cache World Bank series |
| `preprocessing.py` | Validation, derived variables, the state × year panel |
| `forecasting/` | Features, models, backtest, selection/training, live forecasts |
| `impact.py` | Spending → indicator elasticities |
| `budget.py` | Budget baseline and fiscal identities |
| `scenario.py` | Allocation scenarios |
| `optimize.py` | Constrained allocation |
| `stress.py` | Stress tests |
| `feedback.py` | Deviations, calibration, retraining |
| `registry.py` | Model versions |

## 1. Ingestion

`ingest/rbi_hsis.py` parses every RBI Handbook XLSX listed in `data/raw/rbi_hsis_2024_25/MANIFEST.csv`.

- **Sheets:** each sheet is a block of year columns (e.g. `T_166(i)` covers 2004-05 → 2013-14). Blocks are concatenated.
- **Header row:** the first row whose first cell starts with `State`. If the cells after it are not years (as in the GSDP tables, which show `Base: 2011-12`), the year row is searched in the next 1–2 rows.
- **Year labels:**
  - `2014.0` → `2014`.
  - `2022-23 (A)` → `ACTUAL`; `(RE)` → `RE`; `(BE)` → `BE`. Untagged fiscal years in the fiscal tables → `ACCOUNTS`.
  - Calendar years (IMR, roads) are mapped to fiscal years (§2).
- **Cells:**
  - `-` = not available → dropped (never zero-filled).
  - `.` = not applicable → dropped.
  - `16*` → value 16 with footnote flag `*`.
- **Rows:** a row is kept only if its label resolves through `budget_ml/data/state_mapping.csv`. Matching strips footnote markers (`@ # * ^`, trailing digits), normalises `&`/`and`, and ignores case. Unresolved rows (notes, sources) are logged.
- **Multi-sheet variants:**
  - Unemployment tables (T8/T9): only the "Overall" sheet is used.
  - Gross Enrolment Ratio (T1): one sheet per year; the "Total" columns are kept.
  - T16 and T17 (health-infrastructure snapshots) are not ingested.

`ingest/world_bank.py` fetches the WDI series for India and caches the raw JSON in `data/raw/world_bank/`.

## 2. Preprocessing and validation

`preprocessing.py`:

1. **Long format.** Records are `(state_code, indicator, fy_start, fiscal_year, value, data_status, footnote_flag, source_*)`.
2. **Duplicates.** An exact duplicate key is collapsed. A key repeated with conflicting values raises `DataQualityError`.
3. **Calendar → fiscal mapping.** IMR for calendar year *Y* → `Y-(Y+1)`. Road length at end-March *Y* → `(Y-1)-Y`. WDI calendar year *Y* → `Y-(Y+1)`.
4. **Validation (`quality_report.json`):**
   - Sum of states vs the published "All States and UTs" row.
   - Missing cells per state.
   - Coverage per indicator.
   - Year-on-year jumps beyond ×4.5, **flagged, not removed**.
   - Derived population compared with the World Bank figure.
5. **Structural breaks** (recorded, not corrected):
   - AP 2014-15 (bifurcation).
   - J&K 2019-20 and 2020-21 (reorganisation).
   No growth rate or feature window is computed across a break.
6. **Panel.** `panel.csv`: a wide state × fiscal-year table, with aggregate rows excluded and national WDI series joined by year.
7. **Derived variables.** GSDP and NSDP are converted from ₹ lakh to ₹ crore at ingestion.
   - `population = NSDP (₹ crore) × 1e7 / per-capita NSDP (₹)`.
   - `committed = interest + pension`.
   - `total_expenditure = revenue_expenditure + capital_expenditure`.
   - `social_ex_health`, plus %-of-GSDP and per-capita versions of the fiscal variables.

**Results on the current dataset:**

- **Scale:** 14,730 observations; 829 panel rows.
- **Duplicates and parsing:** 0 duplicates; 0 unparseable cells.
- **State sums:** 9 of 11 fiscal tables match RBI's published totals within 0.4%; the other two (revenue expenditure, social sector) within 2.2%.
- **Derived population:** 96.5–97% of the World Bank India population for 2011-12 → 2022-23.
- **Outliers:** 78 jumps flagged. One example is Bihar's capital receipts in 2022-23, which are kept as published.

## 3. Forecasting

**Targets:** revenue expenditure, own tax revenue, own non-tax revenue, capital expenditure, social sector expenditure.

**Formulation:** predict the log growth `g(s,t+1) = ln(y(s,t+1)/y(s,t))`. Growth rates are comparable across states of very different size, so one model can be pooled over the panel.

**Training data:** targets with status `ACCOUNTS`/`ACTUAL` only. RE and BE are never used as targets.

**Features** (`forecasting/features.py`):
- Lagged growth g1 and g2, and the 3-year mean growth.
- National CPI (WDI).
- State nominal GSDP growth, and target/GSDP. Both are available from 2012-13.

**Candidate models** (`forecasting/models.py`):

| Model | Description |
|---|---|
| `naive_last` | g = 0 |
| `drift_3y` | g = 3-year mean growth |
| `ridge_core` | Standardised ridge regression (α = 1) on lagged growth and CPI |
| `ridge_macro` | `ridge_core` plus GSDP growth and ratio |
| `hgb_macro` | Gradient boosting (depth 3, 150 iterations, learning rate 0.05, min leaf 20, L2 = 1) |

A learned model needs at least **4 distinct training origin years** with complete features; otherwise it is not eligible for that origin.

**Multi-step forecasting:** recursive, up to 3 years. Exogenous inputs are frozen at origin-year values: CPI held, GSDP growing at its origin-year rate. No information after the origin is used, and a test checks this.

**Protocol** (`forecasting/train.py`), fixed before results were seen:
1. **Backtest.** Rolling-origin, expanding window, horizons 1–3. Every model is scored on the same rows.
2. **Selection.** Origins 2014-15 → 2019-20, one-year-ahead MAPE. A learned model is chosen only if it beats the better of `naive_last` and `drift_3y`; otherwise that baseline is chosen.
3. **Intervals.** The 10th/90th percentiles of the chosen model's selection-window errors ln(actual/predicted), per horizon (80% nominal). Coverage is then measured on the hold-out.
4. **Hold-out.** Origins 2020-21 → 2021-22 (target years 2021-22 and 2022-23), reported for every model and not used for selection.
5. **Final fit.** The chosen model is refitted on all labelled rows (targets 2008-09 → 2022-23) and saved to the registry.

**Live forecasting** (`forecasting/service.py`):
- Origin = the last Actual year (2022-23), so forecasts cover 2023-24 → 2025-26.
- The response carries the state's own RE/BE figures as `government_estimates` for comparison.
- When the active version is calibrated, the point estimate is bias-corrected (§8). The interval stays based on the uncalibrated prediction, because that is what its quantiles describe.

## 4. Impact models (spending → public-service indicators)

`impact.py` estimates two-way fixed-effects panel regressions:

> ln y(s, t+1) = a_s + d_t + b · ln(x_pc(s,t)) + c · ln(NSDP_pc(s,t)) + e

- *x_pc* is per-capita sector spending.
- State effects remove permanent differences between states. Year effects remove common shocks and the common price level.
- *b* is an elasticity.
- **Sample:** states and UTs with legislature only; structural-break years removed.
- **Uncertainty:** a **state-clustered bootstrap** (999 draws, whole states resampled). A relationship is *supported* only if the 90% interval excludes zero **and** has the expected sign.

| Key | Spending | Indicator | Expected sign |
|---|---|---|---|
| `health_imr` | Health per capita | Infant mortality rate | − |
| `social_imr` | Social sector per capita | Infant mortality rate | − |
| `capital_roads` | Capital outlay per capita | Road length | + |
| `capital_unemp_urban` | Capital outlay per capita | Urban unemployment | − |

**None is supported by the current data** (`MODEL_EVALUATION.md` §3). These are associations, not causal effects.

## 5. Budget baseline and scenarios

**Budget structure** (`budget.py`, ₹ crore). The sector split is exact by construction:

- Deficit-relevant expenditure: E = revenue expenditure + capital outlay.
- Committed: interest + pension. Held fixed.
- Sectors:
  - social_sector (T175);
  - capital_outlay (T174);
  - other_revenue = revenue expenditure − committed − social_sector.
- Discretionary total: D = sum of the three sectors = E − committed.

RBI's social-sector figure includes its own capital outlay, so the social and capital lines overlap and `other_revenue` is a residual. The total D is still exact.

From the identity *GFD = revenue expenditure + capital outlay + (net lending − non-debt capital receipts) − revenue receipts*, any change in D moves GFD one-for-one, with revenue receipts held fixed. Revenue receipts are taken as revenue expenditure − revenue deficit.

**Health:** published (T18) only for 2012-13 → 2019-20. For later years it is *estimated* as social_sector × the state's last published health share, and flagged in every response.

**Scenarios** (`scenario.py`). The baseline is any year in the data; the default is the latest, 2024-25 BE.

| Type | Allocation |
|---|---|
| `BALANCED` | Each sector at the state's average share over its last five actual years |
| `HEALTH_PRIORITY` | Health +10% (inside social sector), funded pro rata from the other two sectors |
| `SOCIAL_PRIORITY` | Social sector +10%, funded pro rata from the others |
| `INFRASTRUCTURE_PRIORITY` | Capital outlay +10%, funded pro rata from the others |
| `CUSTOM` | User amounts or % changes. The total may change, which changes the deficit |

**Outputs:**
- Sector amounts, shares and per-capita values, against the all-state median per capita that year.
- Fiscal position: GFD, revenue deficit, GFD/GSDP, and whether the 3% FRBM limit is breached. Social and other changes are treated as revenue spending, so the revenue-deficit effect is an upper bound.
- Indicator effects from §4. When a relationship is not supported, the elasticity and its interval are shown, with `predicted_value = null`.

## 6. Constrained optimisation

`optimize.py`. The variables are the three sector amounts.

**Constraints:**
- **Total:** Σx = D (the user total, else the current total).
- **Deficit limit:** optional, on by default. D ≤ D₀ + (3% × GSDP − GFD₀), which can force a cut.
- **Bounds:** each sector's share is bounded by the range spanned by the state's actual years (2011-12 → 2022-23) **and its current budget**, so the current split is always feasible. Users can override bounds as shares or amounts.
  - Years where the published components exceed revenue expenditure, so no valid split exists (e.g. PB 2016-17), are excluded from the range. Without this, bounds could go negative.
- **Social floor (optional):** `CURRENT` (no per-capita cut) or `MEDIAN` (the all-state median per-capita social spending).

Infeasible constraint sets are detected before solving and reported with the sums that conflict.

**Objectives.** None of these is an invented scoring formula:

| Objective | Definition | Solver |
|---|---|---|
| `MIN_ADJUSTMENT` (default) | Minimise Σ((x−x₀)/x₀)²: the smallest proportional reallocation that satisfies the fiscal rule, bounds and floors | SLSQP (strictly convex, unique) |
| `MAX_CAPITAL_OUTLAY` | Maximise capital outlay subject to the constraints; the remainder is split between the other sectors by least proportional change | HiGHS LP, then SLSQP |
| `OUTCOME` | Maximise Σ w·s·b·ln(x/x₀) using supported elasticities from §4. **Refused while no elasticity is supported** | SLSQP over budget shares |

## 7. Stress testing

`stress.py`. Spending is held at the tested allocation (the baseline, or one from a scenario or optimisation). Default shocks are calibrated from the data:

| Scenario | Default shock |
|---|---|
| `REVENUE_SHOCK` | Own tax revenue changes by the state's worst observed year-on-year fall in actual years (panel 5th percentile if it never fell) |
| `INFLATION` | CPI at the 95th percentile of state CPI 2014-15 → 2024-25. Real spending factor = (1+π_base)/(1+π_stress) |
| `POPULATION` | Population higher by (95th percentile of state population growth − the state's own mean growth) |
| `DOWNTURN` | GSDP lower by exp(g_worst − g_mean) − 1, using the state's real GSDP history. Own tax revenue falls by **tax buoyancy** × that fall |
| `COMBINED` | Downturn + inflation + population, with the tax loss equal to the larger of the revenue-shock and downturn losses (not added, to avoid double counting) |

**Tax buoyancy:** the pooled OLS slope of Δln(own tax revenue) on Δln(nominal GSDP) over actual years, with a state-clustered bootstrap 90% interval.

**Metrics, normal vs stressed:**
- Own tax revenue, revenue receipts, revenue deficit, GFD, GSDP, GFD/GSDP (breach flag).
- Interest as % of revenue receipts.
- Real per-capita social and capital spending.
- The adjustment needed to return to 3%.

## 8. Feedback loop, calibration and versioning

**Deviation records** (`feedback.py`), for each prediction and actual: absolute error |p − a|, percentage error (p − a)/a × 100 (signed), and log error ln(p/a).

**Calibration:**
- Per-state bias b_s = (n/(n+3)) · median_s + (3/(n+3)) · median_panel, using each state's most recent ≤5 deviation records.
- Calibrated prediction = p · exp(−b_s).
- A calibrated child version is **activated only if it lowers MAPE on records for later years than those used to estimate the bias**; otherwise it is stored inactive.
- The correction is estimated from one-year-ahead errors and applied to every horizon. For multi-year horizons this is an approximation.

**Real-data run** (`scripts/calibrate_models.py`): bias is estimated from target years ≤ 2020-21 and evaluated on 2021-22 and 2022-23. The results are in `MODEL_EVALUATION.md` §4.

**Retraining:** `feedback.retrain` re-runs the full §3 protocol on the current dataset and saves a child version. It is activated if hold-out MAPE is no worse than the parent's, or always when the dataset hash has changed (new data).

**Registry** (`registry.py`):
- Each version is stored in `models/<target>/v<N>/model.joblib` with `metadata.json`.
- The metadata records the model type, features, parameters, dataset label and hash, training window, selection and hold-out metrics, interval quantiles and coverage, calibration, and parent version.
- `active.json` names the active version.

**Scripts:** `build_dataset.py` → `train_models.py` → `calibrate_models.py`.
