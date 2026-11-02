# Database Design (PostgreSQL)

Status: **[implemented]**.
- **Schema:** created by Flyway in `backend/src/main/resources/db/migration/V1__initial_schema.sql`.
- **Entities:** the JPA entities in `backend/src/main/java/com/budgetintel/entity/` mirror it. Hibernate runs with `ddl-auto: validate`, so any mismatch between entities and schema fails at startup.
- **Changes made during implementation:**
  - **Result snapshots:** `budget_scenario`, `optimization_run` and `stress_test` carry `result_snapshot jsonb`, the exact result shown to the user. A saved record stays reproducible after models or data change.
  - **Scenario fiscal columns:** `budget_scenario` stores baseline and scenario GFD, GFD/GSDP and the FRBM breach flag, so lists can be filtered and sorted without reading the snapshot.
  - **Unsupported outcomes:** `scenario_outcome.predicted_value` is nullable and a `supported` flag was added. No spending-to-indicator relationship is currently supported (MODEL_EVALUATION.md §3), so no value is predicted.
  - **Model versions:** `model_version` stores `dataset_label`/`dataset_hash` (not a foreign key, because the ML registry can hold versions trained on datasets not yet imported) and a `calibrated` flag.
  - **Observation status:** `observation.data_status` also allows `DERIVED` (e.g. population).
  - **National series:** World Bank series are stored against an `AGGREGATE` state row with code `IN`.
  - **Optimisation status:** `optimization_run.status` is `OPTIMAL` or `INFEASIBLE`. Infeasible runs are stored with their reasons in `solver_message`.

Conventions: `bigint` identity primary keys; `timestamptz` timestamps; money values are `numeric(18,2)` in **₹ crore**, unless the indicator's unit says otherwise; `fiscal_year` is `varchar(7)` in the form `'2023-24'`.

## 1. Entity overview

```
dataset_version 1─* observation *─1 state
                          *─1 indicator
dataset_version 1─* model_version *─1 indicator (target)
model_version   1─* forecast *─1 state, *─1 indicator
forecast        1─0..1 prediction_deviation *─1 observation (the actual)
state 1─* budget_scenario 1─* scenario_allocation
                          1─* scenario_outcome
state 1─* optimization_run 1─* optimization_allocation
state 1─* stress_test 1─* stress_test_result
```

## 2. Tables

### `dataset_version`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| label | varchar(64) | UNIQUE, NOT NULL, e.g. `hsis-2024-25+wdi-2026-10` |
| source_summary | text | NOT NULL (publishers and URLs) |
| content_hash | char(64) | NOT NULL (SHA-256 over the raw-file hashes) |
| imported_at | timestamptz | NOT NULL |
| record_count | integer | NOT NULL |
| quality_report | jsonb | output of the pipeline validation |
| is_current | boolean | NOT NULL, default false (partial unique index: only one row may be true) |

### `state`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| code | varchar(4) | UNIQUE, NOT NULL (e.g. `KA`, `UP`, `DL`) |
| name | varchar(80) | UNIQUE, NOT NULL |
| category | varchar(16) | NOT NULL; `STATE` \| `UT_LEGISLATURE` \| `UT` \| `AGGREGATE` |
| formed_fiscal_year | varchar(7) | nullable (Telangana `2014-15`) |

### `indicator`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| code | varchar(48) | UNIQUE, NOT NULL (e.g. `revenue_expenditure`) |
| name | varchar(120) | NOT NULL |
| unit | varchar(32) | NOT NULL (`INR_CRORE`, `PERCENT`, `PER_1000`, `KM`, …) |
| category | varchar(16) | NOT NULL; `FISCAL` \| `ECONOMIC` \| `OUTCOME` \| `DERIVED` |
| source_table | varchar(32) | e.g. `RBI HSIS T166` |
| is_forecast_target | boolean | NOT NULL default false |

### `observation` (budget and socioeconomic records)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| dataset_version_id | bigint | FK → dataset_version, NOT NULL |
| state_id | bigint | FK → state, NOT NULL |
| indicator_id | bigint | FK → indicator, NOT NULL |
| fiscal_year | varchar(7) | NOT NULL |
| value | numeric(20,4) | NOT NULL (missing values are not stored as rows) |
| data_status | varchar(8) | NOT NULL; `ACCOUNTS` \| `ACTUAL` \| `RE` \| `BE` \| `OBSERVED` |
| footnote_flag | varchar(16) | nullable (source footnote marker, e.g. `*`) |

UNIQUE (`dataset_version_id`, `state_id`, `indicator_id`, `fiscal_year`). Index on (`state_id`, `indicator_id`, `fiscal_year`).

### `model_version`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| target_indicator_id | bigint | FK → indicator, NOT NULL |
| version_number | integer | NOT NULL; UNIQUE (`target_indicator_id`, `version_number`) |
| model_type | varchar(48) | NOT NULL (e.g. `ridge_growth`, `naive_last`) |
| dataset_version_id | bigint | FK → dataset_version, NOT NULL |
| parent_version_id | bigint | FK → model_version, nullable (the version it was retrained or calibrated from) |
| trained_at | timestamptz | NOT NULL |
| train_year_from / train_year_to | varchar(7) | NOT NULL |
| metrics | jsonb | NOT NULL (`{"mae":…, "rmse":…, "mape":…, "r2":…, "n_test":…}` from the backtest) |
| config | jsonb | NOT NULL (features, hyperparameters, calibration flag) |
| artifact_path | varchar(255) | NOT NULL |
| is_active | boolean | NOT NULL (partial unique index: one active version per target) |

### `forecast` (prediction record)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK; exposed to users as the **Prediction ID** |
| model_version_id | bigint | FK → model_version, NOT NULL |
| state_id | bigint | FK → state, NOT NULL |
| indicator_id | bigint | FK → indicator, NOT NULL |
| origin_fiscal_year | varchar(7) | NOT NULL (last year of data used) |
| target_fiscal_year | varchar(7) | NOT NULL |
| horizon | smallint | NOT NULL, CHECK 1–5 |
| predicted_value | numeric(20,4) | NOT NULL |
| lower_bound / upper_bound | numeric(20,4) | nullable (empirical 80% interval) |
| kind | varchar(12) | NOT NULL; `BACKTEST` \| `LIVE` |
| created_at | timestamptz | NOT NULL |

UNIQUE (`model_version_id`, `state_id`, `indicator_id`, `origin_fiscal_year`, `target_fiscal_year`).

### `prediction_deviation`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| forecast_id | bigint | FK → forecast, NOT NULL; UNIQUE (`forecast_id`, `comparison_basis`) |
| observation_id | bigint | FK → observation, NOT NULL |
| comparison_basis | varchar(12) | NOT NULL; `ACTUAL` (Accounts/Actual) \| `GOVT_RE` \| `GOVT_BE` |
| actual_value | numeric(20,4) | NOT NULL (copied, so the record survives dataset changes) |
| absolute_error | numeric(20,4) | NOT NULL |
| percentage_error | numeric(10,4) | nullable (signed; NULL when actual = 0) |
| evaluated_at | timestamptz | NOT NULL |

### `budget_scenario`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| name | varchar(120) | NOT NULL |
| scenario_type | varchar(24) | NOT NULL; `BALANCED` \| `HEALTH_PRIORITY` \| `SOCIAL_PRIORITY` \| `INFRASTRUCTURE_PRIORITY` \| `CUSTOM` |
| state_id | bigint | FK → state, NOT NULL |
| fiscal_year | varchar(7) | NOT NULL |
| model_version_ids | jsonb | NOT NULL (the impact-model versions used) |
| notes | text | |
| created_at | timestamptz | NOT NULL |

### `scenario_allocation`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| scenario_id | bigint | FK → budget_scenario ON DELETE CASCADE |
| sector | varchar(24) | NOT NULL; UNIQUE (`scenario_id`, `sector`) |
| baseline_amount / scenario_amount | numeric(18,2) | NOT NULL, CHECK ≥ 0 |
| change_pct | numeric(8,3) | NOT NULL |

### `scenario_outcome`
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| scenario_id | bigint | FK → budget_scenario ON DELETE CASCADE |
| indicator_id | bigint | FK → indicator |
| baseline_predicted / scenario_predicted | numeric(20,4) | NOT NULL |
| lower_bound / upper_bound | numeric(20,4) | |
| method_note | varchar(255) | e.g. "panel elasticity, 2012-13–2019-20" |

### `optimization_run` / `optimization_allocation`
`optimization_run`: id PK, state_id FK, fiscal_year, total_budget numeric(18,2), objective varchar(48), constraints jsonb (bounds actually applied), status varchar(16) (`OPTIMAL` \| `INFEASIBLE` \| `ERROR`), objective_value numeric, solver_message text, created_at.

`optimization_allocation`: id PK, run_id FK ON DELETE CASCADE, sector, current_amount, recommended_amount, min_bound, max_bound (all numeric(18,2)). UNIQUE (`run_id`, `sector`).

### `stress_test` / `stress_test_result`
`stress_test`: id PK, state_id FK, fiscal_year, source_type varchar(16) (`CURRENT` \| `SCENARIO` \| `OPTIMIZATION`), source_id bigint (nullable, polymorphic reference, validated in the service layer), shocks jsonb (applied magnitudes plus their historical basis), created_at.

`stress_test_result`: id PK, stress_test_id FK ON DELETE CASCADE, shock_name varchar(32) (`REVENUE_SHOCK`, `INFLATION`, `POPULATION`, `DOWNTURN`, `COMBINED`), metric varchar(48), normal_value numeric(20,4), stressed_value numeric(20,4), breaches_limit boolean.

## 3. Design notes

- There is no separate `actual_outcome` table. Actuals *are* `observation` rows with `data_status` `ACCOUNTS`/`ACTUAL`, and `prediction_deviation` links a prediction to the observation that scored it.
- `budget_record` from the brief is the `observation` row for a fiscal indicator; the `indicator` table makes one record shape serve every series.
- JSONB is used only for variable-shaped configuration (constraints, shocks, metrics, hyperparameters). Anything that is queried or charted is in columns.
