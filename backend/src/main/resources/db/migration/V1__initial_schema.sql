-- Budget Intelligence schema (see DATABASE_DESIGN.md). Money columns are in ₹ crore.

CREATE TABLE dataset_version (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label           VARCHAR(64)  NOT NULL UNIQUE,
    source_summary  TEXT         NOT NULL,
    content_hash    CHAR(64)     NOT NULL UNIQUE,
    built_at        VARCHAR(32)  NOT NULL,
    imported_at     TIMESTAMPTZ  NOT NULL,
    record_count    INTEGER      NOT NULL,
    quality_report  JSONB,
    is_current      BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX ux_dataset_version_current ON dataset_version (is_current) WHERE is_current;

CREATE TABLE state (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(4)   NOT NULL UNIQUE,
    name                VARCHAR(80)  NOT NULL UNIQUE,
    category            VARCHAR(16)  NOT NULL CHECK (category IN ('STATE', 'UT_LEGISLATURE', 'UT', 'AGGREGATE')),
    formed_fiscal_year  VARCHAR(7),
    has_budget_data     BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE indicator (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(48)  NOT NULL UNIQUE,
    name                VARCHAR(160) NOT NULL,
    unit                VARCHAR(32)  NOT NULL,
    category            VARCHAR(16)  NOT NULL,
    source_table        VARCHAR(64)  NOT NULL,
    is_forecast_target  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE observation (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataset_version_id  BIGINT        NOT NULL REFERENCES dataset_version (id) ON DELETE CASCADE,
    state_id            BIGINT        NOT NULL REFERENCES state (id),
    indicator_id        BIGINT        NOT NULL REFERENCES indicator (id),
    fiscal_year         VARCHAR(7)    NOT NULL,
    value               NUMERIC(24,6) NOT NULL,
    data_status         VARCHAR(8)    NOT NULL CHECK (data_status IN ('ACCOUNTS', 'ACTUAL', 'RE', 'BE', 'OBSERVED', 'DERIVED')),
    footnote_flag       VARCHAR(16),
    CONSTRAINT ux_observation UNIQUE (dataset_version_id, state_id, indicator_id, fiscal_year)
);
CREATE INDEX ix_observation_lookup ON observation (state_id, indicator_id, fiscal_year);

CREATE TABLE model_version (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_indicator_id  BIGINT       NOT NULL REFERENCES indicator (id),
    version_number       INTEGER      NOT NULL,
    model_type           VARCHAR(48)  NOT NULL,
    dataset_label        VARCHAR(64)  NOT NULL,
    dataset_hash         CHAR(64)     NOT NULL,
    parent_version_id    BIGINT       REFERENCES model_version (id),
    trained_at           TIMESTAMPTZ  NOT NULL,
    train_year_from      VARCHAR(7)   NOT NULL,
    train_year_to        VARCHAR(7)   NOT NULL,
    metrics              JSONB        NOT NULL,
    config               JSONB        NOT NULL,
    calibrated           BOOLEAN      NOT NULL DEFAULT FALSE,
    artifact_path        VARCHAR(255) NOT NULL,
    is_active            BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ux_model_version UNIQUE (target_indicator_id, version_number)
);
CREATE UNIQUE INDEX ux_model_version_active ON model_version (target_indicator_id) WHERE is_active;

CREATE TABLE forecast (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_version_id    BIGINT        NOT NULL REFERENCES model_version (id),
    state_id            BIGINT        NOT NULL REFERENCES state (id),
    indicator_id        BIGINT        NOT NULL REFERENCES indicator (id),
    origin_fiscal_year  VARCHAR(7)    NOT NULL,
    target_fiscal_year  VARCHAR(7)    NOT NULL,
    horizon             SMALLINT      NOT NULL CHECK (horizon BETWEEN 1 AND 5),
    predicted_value     NUMERIC(24,6) NOT NULL,
    lower_bound         NUMERIC(24,6),
    upper_bound         NUMERIC(24,6),
    kind                VARCHAR(12)   NOT NULL CHECK (kind IN ('BACKTEST', 'LIVE')),
    created_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_forecast UNIQUE (model_version_id, state_id, indicator_id, origin_fiscal_year, target_fiscal_year)
);
CREATE INDEX ix_forecast_lookup ON forecast (state_id, indicator_id, target_fiscal_year);

CREATE TABLE prediction_deviation (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    forecast_id       BIGINT        NOT NULL REFERENCES forecast (id) ON DELETE CASCADE,
    observation_id    BIGINT        REFERENCES observation (id) ON DELETE SET NULL,
    comparison_basis  VARCHAR(12)   NOT NULL CHECK (comparison_basis IN ('ACTUAL', 'GOVT_RE', 'GOVT_BE')),
    actual_value      NUMERIC(24,6) NOT NULL,
    absolute_error    NUMERIC(24,6) NOT NULL,
    percentage_error  NUMERIC(12,4),
    evaluated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_prediction_deviation UNIQUE (forecast_id, comparison_basis)
);

CREATE TABLE budget_scenario (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                      VARCHAR(120) NOT NULL,
    scenario_type             VARCHAR(24)  NOT NULL CHECK (scenario_type IN
                                  ('BALANCED', 'HEALTH_PRIORITY', 'SOCIAL_PRIORITY', 'INFRASTRUCTURE_PRIORITY', 'CUSTOM')),
    state_id                  BIGINT       NOT NULL REFERENCES state (id),
    fiscal_year               VARCHAR(7)   NOT NULL,
    baseline_data_status      VARCHAR(8)   NOT NULL,
    description               TEXT         NOT NULL,
    baseline_gfd              NUMERIC(18,2) NOT NULL,
    scenario_gfd              NUMERIC(18,2) NOT NULL,
    baseline_gfd_pct_gsdp     NUMERIC(8,4),
    scenario_gfd_pct_gsdp     NUMERIC(8,4),
    breaches_frbm_limit       BOOLEAN      NOT NULL,
    dataset_version_id        BIGINT       NOT NULL REFERENCES dataset_version (id),
    notes                     TEXT,
    result_snapshot           JSONB        NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL
);

CREATE TABLE scenario_allocation (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scenario_id      BIGINT        NOT NULL REFERENCES budget_scenario (id) ON DELETE CASCADE,
    sector           VARCHAR(24)   NOT NULL,
    baseline_amount  NUMERIC(18,2) NOT NULL CHECK (baseline_amount >= 0),
    scenario_amount  NUMERIC(18,2) NOT NULL CHECK (scenario_amount >= 0),
    change_pct       NUMERIC(10,4),
    CONSTRAINT ux_scenario_allocation UNIQUE (scenario_id, sector)
);

CREATE TABLE scenario_outcome (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scenario_id         BIGINT        NOT NULL REFERENCES budget_scenario (id) ON DELETE CASCADE,
    indicator_id        BIGINT        NOT NULL REFERENCES indicator (id),
    driver_sector       VARCHAR(24)   NOT NULL,
    current_value       NUMERIC(20,4) NOT NULL,
    predicted_value     NUMERIC(20,4),
    lower_bound         NUMERIC(20,4),
    upper_bound         NUMERIC(20,4),
    supported           BOOLEAN       NOT NULL,
    method_note         VARCHAR(400)  NOT NULL
);

CREATE TABLE optimization_run (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    state_id          BIGINT        NOT NULL REFERENCES state (id),
    fiscal_year       VARCHAR(7),
    objective         VARCHAR(24)   NOT NULL,
    total_budget      NUMERIC(18,2),
    constraints       JSONB         NOT NULL,
    status            VARCHAR(16)   NOT NULL CHECK (status IN ('OPTIMAL', 'INFEASIBLE')),
    objective_value   NUMERIC(24,8),
    solver_message    TEXT,
    dataset_version_id BIGINT       NOT NULL REFERENCES dataset_version (id),
    result_snapshot   JSONB,
    created_at        TIMESTAMPTZ   NOT NULL
);

CREATE TABLE optimization_allocation (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id              BIGINT        NOT NULL REFERENCES optimization_run (id) ON DELETE CASCADE,
    sector              VARCHAR(24)   NOT NULL,
    current_amount      NUMERIC(18,2) NOT NULL,
    recommended_amount  NUMERIC(18,2) NOT NULL,
    min_bound           NUMERIC(18,2) NOT NULL,
    max_bound           NUMERIC(18,2) NOT NULL,
    CONSTRAINT ux_optimization_allocation UNIQUE (run_id, sector)
);

CREATE TABLE stress_test (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    state_id           BIGINT       NOT NULL REFERENCES state (id),
    fiscal_year        VARCHAR(7)   NOT NULL,
    source_type        VARCHAR(16)  NOT NULL CHECK (source_type IN ('CURRENT', 'SCENARIO', 'OPTIMIZATION')),
    source_id          BIGINT,
    shocks             JSONB        NOT NULL,
    dataset_version_id BIGINT       NOT NULL REFERENCES dataset_version (id),
    result_snapshot    JSONB        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE stress_test_result (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stress_test_id  BIGINT        NOT NULL REFERENCES stress_test (id) ON DELETE CASCADE,
    shock_name      VARCHAR(32)   NOT NULL,
    metric          VARCHAR(48)   NOT NULL,
    normal_value    NUMERIC(24,6),
    stressed_value  NUMERIC(24,6),
    breaches_limit  BOOLEAN       NOT NULL
);
