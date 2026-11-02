# Dataset Selection

Research date: 2026-10-23. Facts marked **[verified]** were confirmed directly: page fetched, API queried, or the downloaded file opened and parsed. Raw files are in `data/raw/rbi_hsis_2024_25/` (24 XLSX files, listed in `MANIFEST.csv`).

---

## 1. Candidate datasets

### C1. RBI, *Handbook of Statistics on Indian States 2024-25*: state fiscal tables (PRIMARY)

| Field | Detail |
|---|---|
| Publisher | Reserve Bank of India, Regional Economy Monitoring Division. Underlying source: RBI *State Finances: A Study of Budgets* and *Handbook of Statistics on State Government Finances 2010* |
| URL | https://www.rbi.org.in/Scripts/AnnualPublications.aspx?head=Handbook%20of%20Statistics%20on%20Indian%20States |
| Released | 11 Dec 2025 [verified] |
| Tables | T164 Gross Fiscal Deficit, T165 Revenue Deficit, T166 Revenue Expenditure, T168 Own Tax Revenue, T169 Own Non-Tax Revenue, T170 Interest Payments, T171 Pension, T172 Capital Receipts, T173 Capital Expenditure, T174 Capital Outlay, T175 Social Sector Expenditure |
| Shape | Each table has two sheets: 2004-05→2013-14 and 2014-15→2024-25, so **21 fiscal years** in all. Rows are states; values are in ₹ crore [verified] |
| Records | ~31 states plus an "All States and UTs" total. NCT Delhi and Puducherry appear in most tables but not in T166 or T172. About **650 state-year cells per table**, with 309 + 352 numeric cells in a typical table [verified] |
| Data status | 2004-05 → 2021-22 are Accounts; **2022-23 (A) = Actual, 2023-24 (RE) = Revised Estimate, 2024-25 (BE) = Budget Estimate** [verified, from column headers] |
| Missing values | `-` = not available: 11 cells per table, mostly Telangana before 2014-15 [verified]. Footnotes: J&K figures are RE for 2004-05→2009-10, Manipur RE for →2008-09, Jharkhand RE for →2010-11 [verified] |
| Targets | Revenue expenditure, own tax revenue, own non-tax revenue, capital expenditure, social sector expenditure |
| Suitable? | **YES (primary)** |

### C2. RBI, *Handbook of Statistics on Indian States 2024-25*: economy and outcome tables (SUPPORTING)

All are from the same publication, so they share the C1 state naming (minor spelling variants are handled by a mapping table) [verified].

| Table | Coverage [verified] | Unit | Role |
|---|---|---|---|
| T21 / T22 GSDP, current / constant prices | 2011-12 → 2024-25 (base 2011-12), 36 states/UTs | ₹ lakh | Feature, deficit ceiling (3% of GSDP) |
| T23 NSDP, current | 2011-12 → 2024-25 | ₹ lakh | Population derivation |
| T19 Per-capita NSDP, current | 2011-12 → 2024-25 | ₹ | Population derivation, feature |
| T108 Average CPI inflation (general) | 2014-15 → 2024-25 (396 cells, no gaps) | % | Feature, stress calibration |
| T18 Public expenditure on health | 2012-13 → 2019-20 only (NHP/CBHI source), 22 `-` cells | ₹ crore | Health allocation (shorter window) |
| T4 Infant Mortality Rate (SRS) | 2004 → 2023 (calendar years), 36 states/UTs | per 1,000 | Health outcome indicator |
| T1 Gross Enrolment Ratio (UDISE+) | 3 years only: 2022-23, 2023-24, 2024-25; by school level and sex | % | Education indicator (descriptive only, too short to model) |
| T8 / T9 Unemployment, rural / urban (PLFS) | 1993-94 … 2011-12 (irregular), then annual 2017-18 → 2023-24 | per 1,000 | Employment indicator |
| T146 Road length | 2005 → 2020 (end-March) | km | Infrastructure indicator |
| T16 Doctors and specialists at PHCs/CHCs | Snapshots: 2005, 2015, 2020–2023 | count | Health-service indicator |
| T17 Government hospitals and beds | Single snapshot, 31-12-2022 | count | Descriptive only |

**Suitable?** **YES (supporting)**

### C3. World Bank, World Development Indicators (WDI) API: India (SUPPORTING)

| Field | Detail |
|---|---|
| URL | `https://api.worldbank.org/v2/country/IND/indicator/<code>?format=json` (no API key needed) [verified] |
| Coverage [verified] | CPI inflation `FP.CPI.TOTL.ZG` 1960–2025; GDP (LCU) `NY.GDP.MKTP.CN` 1960–2025; population `SP.POP.TOTL` 1960–2025; revenue excl. grants (% GDP) `GC.REV.XGRT.GD.ZS` 1974–2022; government health expenditure (% GDP) `SH.XPD.GHED.GD.ZS` 2000–2023; government education expenditure (% GDP) `SE.XPD.TOTL.GD.ZS` 1997–2022 (gaps); unemployment `SL.UEM.TOTL.ZS` 1991–2025 |
| Use | Long-run national shock history to size the stress scenarios (e.g. worst real-GDP year, inflation peaks); national population growth |
| Suitable? | **YES (supporting, national context)** |

### C4. Open Budgets India (CBGA / CivicDataLab): **NO**
The CSV listed on the CKAN mirror covers 1980-81 → 2016-17 BE and was last modified 2017-11-27. Its openbudgetsindia.org download URL returns **404**, and the domain now hosts an unrelated casino site [verified]. The data is out of date and its provenance can no longer be confirmed.

### C5. Dataful.in RBI mirrors: **NO**
A secondary copy of C1/C2. It is kept only as a cross-check source.

### C6. World Bank Worldwide Governance Indicators: **NO**
These are national-level, perception-based estimates, and the `v2` API with `source=3` returned no data [verified]. The same value would apply to every state, so it cannot be tied to state allocations. **The system does not compute a governance score.**

### C7. RBI, *State Finances: A Study of Budgets 2025-26*: appendix tables: **OPTIONAL (deferred)**
This is the only official state-wise **education** split (https://rbi.org.in/Scripts/PublicationsView.aspx?id=23689). It is deferred and not part of the first build.

---

## 2. Selected dataset architecture

**Primary:** C1. **Supporting:** C2 (same publisher) and C3 (national).

**Unit of analysis:** one state in one fiscal year.
**Join key:** `(state_code, fiscal_year)`. State names are normalised via `state_mapping.csv` (e.g. `Andaman & Nicobar Islands` / `Andaman and Nicobar Islands`, `NCT Delhi` / `Delhi`). Calendar-year series (IMR, roads at end-March) map to fiscal years as follows:
- IMR for year *Y* → fiscal year *Y-(Y+1)*.
- Road length at end-March *Y* → fiscal year *(Y-1)-Y*.

**Modelling window:** 2011-12 → 2024-25 (14 years), set by where GSDP is available. The fiscal-only forecaster can use the full 2004-05 → 2024-25 series.

**Panel scope:** the 28 states plus NCT Delhi and Puducherry where present. Small UTs without legislatures are excluded, because they do not appear in the fiscal tables. "All States and UTs" rows are dropped from training and used only for validation checks (the sum of states should match the total).

**Derived variables** (computed from published values only):
- `population ≈ NSDP (₹ lakh) × 1e5 / per-capita NSDP (₹)`. Per-capita NSDP is defined as NSDP ÷ population, so this recovers the population estimate RBI/MoSPI used.
- `real_gsdp_growth` from T22.
- `committed_exp = interest (T170) + pension (T171)`.
- `social_ex_health = T175 − T18` (only where T18 exists).
- Per-capita and %-of-GSDP versions of each fiscal variable.

| Role | Variables |
|---|---|
| **Forecast targets** | T166 revenue expenditure, T168 own tax revenue, T169 own non-tax revenue, T173 capital expenditure, T175 social sector expenditure |
| **Features** | Lags of the targets, GSDP (current and real growth), CPI inflation, derived population, committed expenditure |
| **Allocation sectors** | (1) Social sector (T175); (2) Capital outlay / infrastructure (T174); (3) Health (T18), for 2012-13 → 2019-20 only, nested inside social sector |
| **Constraints** | GFD ≤ 3% of GSDP (FRBM norm; T164 ÷ T21); committed expenditure (T170 + T171) as a fixed floor; sector shares bounded by each state's own observed historical min/max share of total expenditure |
| **Impact indicators** | IMR (T4) against per-capita health / social spending; unemployment (T8/T9) against capital outlay; road length (T146) against capital outlay. Each is modelled only on the years where the outcome exists, with no imputation of outcomes |
| **Stress calibration** | State CPI (T108), state real-GSDP growth (T22; the 2020-21 COVID contraction is the observed worst case), own-tax-revenue shocks (T168 year-on-year changes), national long-run history (C3) |
| **Feedback-loop actuals** | (a) Rolling-origin backtests: train on years ≤ *t*, predict *t+1*, compare with the published value. (b) **BE/RE vs Actual:** the 2023-24 RE and 2024-25 BE columns are the state government's own forecasts, so the system's predictions can be compared with them now and with Actuals once the next Handbook edition is released. (c) New editions are ingested as a new dataset version, and logged predictions are then scored automatically |

## 3. Limitations

1. **Small panel:** ~30 states × 14–21 years, i.e. roughly 400–630 rows per target. Models will be regularised linear models, gradient boosting with strong limits, and naive/trend baselines, all evaluated out of time.
2. **Spending→outcome effects are associational, not causal.** The UI will present them as "expected change based on historical relationship" with intervals.
3. **Health expenditure covers only 2012-13 → 2019-20**, and GER has only 3 years, so the health-impact model has ~240 observations and GER is shown descriptively only.
4. **No separate education spending** in the first build (C7 deferred). "Education priority" scenarios therefore act on the social-sector line, labelled as such.
5. **The latest two years are estimates (RE/BE), not actuals.** They are flagged in the data (`data_status` column) and excluded from training targets by default.
6. **No defensible state-level governance index exists**, so none is computed.

## 4. Validation results (Phase 3 pipeline)

- **State sums:** in 9 of 11 fiscal tables, the states add up to RBI's published "All States and UTs" total within 0.4%; in the other two, within 2.2%.
- **Derived population:** the state total is 96.5–97% of the World Bank India population for 2011-12 → 2022-23.
- **Anomalies:** kept as published and flagged, not deleted. For example, Bihar's capital receipts in 2022-23 look anomalous.

Details are in `ML_PIPELINE.md` §2.

## 5. Acquisition record

- **RBI files:** `rbidocs.rbi.org.in` serves a human-verification page to scripted clients. The 24 files were fetched on 2026-10-23 inside a browser session, with the user's approval, and saved unchanged. Each file was checked as a valid XLSX (ZIP integrity) [verified].
- **World Bank data:** fetched by script at pipeline run time.
