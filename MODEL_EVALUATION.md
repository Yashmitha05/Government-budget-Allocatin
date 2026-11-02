# Model Evaluation

All numbers come from code in `ml-service/` run on dataset `hsis-2024-25+wdi-69898ad3` (RBI Handbook 2024-25 + World Bank WDI). Raw outputs are in `ml-service/reports/` and `ml-service/models/*/v*/metadata.json`. The protocols are described in `ML_PIPELINE.md`.

## 1. Forecasting: protocol

- **Backtest:** rolling-origin, expanding window, horizons 1–3, scored against published Accounts/Actual values.
- **Selection window:** origins 2014-15 → 2019-20, i.e. one-year-ahead targets 2015-16 → 2020-21. In practice the common evaluation rows start at target year 2017-18, because the GSDP-based models need 4 years of feature history.
- **Hold-out:** origins 2020-21 → 2021-22 (targets 2021-22 and 2022-23; 60 state-years at h=1, 30 at h=2). It was not used for any choice.
- **Selection rule:** fixed in advance. Lowest selection MAPE at h=1, but a learned model must beat the better baseline.

## 2. Forecasting: results (version 1 of each target)

### Selection-window MAPE (h=1, %)

| Target | naive_last | drift_3y | ridge_core | ridge_macro | hgb_macro | **Chosen** |
|---|---|---|---|---|---|---|
| Revenue expenditure | 8.94 | 6.23 | 5.80 | **5.45** | 5.92 | ridge_macro |
| Own tax revenue | **9.20** | 14.55 | 13.38 | 16.73 | 10.24 | naive_last |
| Own non-tax revenue | **25.74** | 34.10 | 29.89 | 26.63 | 29.83 | naive_last |
| Capital expenditure | **21.34** | 31.07 | 24.04 | 24.81 | 24.31 | naive_last |
| Social sector expenditure | **10.69** | 12.01 | 11.77 | 10.83 | 10.78 | naive_last |

### Hold-out MAPE (%; h=1, then h=2 in brackets)

| Target | naive_last | drift_3y | ridge_core | ridge_macro | hgb_macro |
|---|---|---|---|---|---|
| Revenue expenditure | 10.42 (19.67) | 5.59 (8.33) | 4.74 (6.07) | **4.56 (4.98)** | 5.17 (5.37) |
| Own tax revenue | 17.98 (32.67) | 14.64 (31.86) | 7.35 (11.40) | **7.30 (10.88)** | 8.41 (14.21) |
| Own non-tax revenue | 27.55 (40.86) | 34.36 (55.66) | **24.02 (32.28)** | 24.85 (35.07) | 26.06 (33.54) |
| Capital expenditure | 19.91 (33.65) | 16.82 (29.69) | **15.93 (21.86)** | 17.92 (27.13) | 17.39 (22.58) |
| Social sector expenditure | 11.15 (20.51) | 8.00 (12.48) | **6.90 (8.39)** | 6.96 (8.37) | 7.49 (9.39) |

### Interval coverage on the hold-out (nominal 80%)

| Target (chosen model) | h=1 | h=2 |
|---|---|---|
| Revenue expenditure (ridge_macro) | 78% | 77% |
| Own tax revenue (naive_last) | 70% | **13%** |
| Own non-tax revenue (naive_last) | 72% | 50% |
| Capital expenditure (naive_last) | 78% | 67% |
| Social sector expenditure (naive_last) | 82% | 80% |

### Findings

1. **Revenue expenditure** is forecast well. ridge_macro has a 4.6% one-year error on the hold-out, against 10.4% for naive, and its intervals are close to nominal coverage.
2. **The selection rule chose naive for four targets, and the hold-out shows this was the wrong choice for own tax revenue and social sector spending.**
   - The selection years 2018-19 → 2020-21 cover the GST transition, when state tax collections were flat under GST compensation, and then COVID-19. In those years "next year = this year" was hard to beat; see `reports/backtest_by_year.csv`.
   - In the hold-out years, growth resumed. Ridge models then cut one-year error from 18.0% to 7.3% (own tax revenue) and from 11.2% to 6.9% (social sector).
   - The naive intervals are also badly calibrated at h=2: coverage is 13% for own tax revenue.
   - **Decision (2026-10-23): keep the pre-specified rule.** Changing the rule after seeing hold-out results would remove the hold-out's value as an independent test. Excluding the COVID year would not change the choice anyway, because naive also wins in 2018-19 and 2019-20.
   - The feedback-loop calibration (§5) partly offsets the naive models' under-prediction.
   - Models will be re-selected under the same rule when the next RBI edition adds 2023-24 actuals. The selection window then moves forward and the GST-compensation years carry less weight.
3. **Own non-tax revenue and capital expenditure** remain hard to forecast. The best hold-out errors are 24% and 16%.

## 3. Impact models (spending → indicators)

Two-way fixed-effects elasticities with a state-clustered bootstrap (999 draws), 90% intervals.

| Relationship | Elasticity | 90% interval | n (states) | Spending years | Within R² | Supported |
|---|---|---|---|---|---|---|
| Health per capita → infant mortality | +0.088 | [−0.114, +0.368] | 242 (31) | 2012–2019 | 0.123 | No |
| Social sector per capita → infant mortality | −0.008 | [−0.330, +0.242] | 364 (31) | 2011–2022 | 0.100 | No |
| Capital outlay per capita → road length | +0.061 | [−0.088, +0.220] | 242 (31) | 2011–2018 | 0.032 | No |
| Capital outlay per capita → urban unemployment | +0.042 | [−0.109, +0.195] | 214 (31) | 2016–2022 | 0.003 | No |

**Finding:** once state and year effects are controlled for, year-to-year changes in state spending have no detectable association with these indicators. The system therefore makes **no indicator predictions**. Scenarios show the estimates and intervals marked "not supported", and the optimiser's OUTCOME objective is disabled.

Likely reasons are short and noisy outcome series, slow-moving indicators, lags longer than one year, and spending aggregates that are too broad (for example, social sector spending includes far more than child health). This is a limitation of the available data, not evidence that spending has no effect.

## 4. Stress-test calibration

- **Tax buoyancy** (Δln own tax revenue on Δln nominal GSDP, actual years 2012-13 → 2022-23, n = 335): **0.84**, 90% interval [0.62, 1.10].
- **Example, Karnataka 2024-25 BE:**
  - Revenue shock −5.2% (its 2020-21 fall).
  - Downturn: real growth −2.4% instead of its 7.6% mean, so GSDP is 9.2% lower.
  - Inflation 8.4%: the 95th percentile of state CPI.
  - Result: GFD/GSDP rises from 2.88% to 3.22% (revenue shock) and 3.73% (downturn or combined). Returning to 3% would need ₹19,133 crore of adjustment.

## 5. Feedback loop: calibration on real deviations

Bias was estimated from one-year-ahead backtest deviations for target years ≤ 2020-21 and evaluated on 2021-22 and 2022-23 (60 records per target).

| Target | Model | Hold-out MAPE before → after | Signed bias before → after | Result |
|---|---|---|---|---|
| Revenue expenditure | ridge_macro | 4.56 → 4.61 | +0.13% → −1.80% | v2 stored, **not activated** (v1 stays active) |
| Own tax revenue | naive_last | 17.98 → 15.53 | −17.98% → −15.53% | **v2 activated** |
| Own non-tax revenue | naive_last | 27.55 → 29.49 | −10.96% → −6.44% | v2 stored, **not activated** |
| Capital expenditure | naive_last | 19.91 → 17.10 | −16.80% → −11.87% | **v2 activated** |
| Social sector expenditure | naive_last | 11.15 → 7.24 | −10.74% → −3.44% | **v2 activated** |

The loop corrects naive forecasts' systematic under-prediction of growing series where that helps, and rejects the correction where it doesn't. The correction comes from one-year-ahead errors, so it is less reliable at horizons 2–3.
