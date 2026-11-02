"""Request/response schemas of the ML service API (JSON field names are snake_case)."""
from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator

from budget_ml.preprocessing import FORECAST_TARGETS

FiscalYear = Field(pattern=r"^\d{4}-\d{2}$", examples=["2024-25"])
StateCode = Field(pattern=r"^[A-Z]{2,3}$", examples=["KA"])

Target = Literal[tuple(FORECAST_TARGETS)]  # type: ignore[valid-type]
Sector = Literal["social_sector", "capital_outlay", "other_revenue"]
ScenarioSector = Literal["social_sector", "capital_outlay", "other_revenue", "health"]
ScenarioType = Literal["BALANCED", "HEALTH_PRIORITY", "SOCIAL_PRIORITY", "INFRASTRUCTURE_PRIORITY", "CUSTOM"]
Objective = Literal["MIN_ADJUSTMENT", "MAX_CAPITAL_OUTLAY", "OUTCOME"]
SocialFloor = Literal["NONE", "CURRENT", "MEDIAN"]
StressScenario = Literal["REVENUE_SHOCK", "INFLATION", "POPULATION", "DOWNTURN", "COMBINED"]


class _Out(BaseModel):
    model_config = ConfigDict(extra="ignore")


class ErrorResponse(BaseModel):
    error: str
    message: str
    details: list[str] = []


# --------------------------------------------------------------------------------------------- reference data

class HealthResponse(_Out):
    status: Literal["ok"]
    dataset_label: str
    panel_rows: int
    active_models: dict[str, Optional[int]]


class StateOut(_Out):
    code: str
    name: str
    category: str
    formed_fiscal_year: Optional[str]
    has_budget_data: bool


class IndicatorOut(_Out):
    code: str
    name: str
    unit: str
    category: str
    source_table: str
    is_forecast_target: bool


class CoverageOut(_Out):
    indicator: str
    first_year: str
    last_year: str
    states: int
    records: int


class DatasetResponse(_Out):
    label: str
    content_hash: str
    built_at: str
    record_count: int
    panel_rows: int
    sources: list[str]
    source_files: dict[str, str]
    coverage: list[CoverageOut]
    aggregate_check: dict[str, dict]
    outlier_flag_count: int
    population_sum_vs_wdi: dict[str, float]
    structural_breaks: dict[str, list[str]]


class ObservationOut(_Out):
    state_code: str
    indicator: str
    fiscal_year: str
    value: float
    data_status: str
    footnote_flag: Optional[str] = None
    source_table: Optional[str] = None


class ObservationsResponse(_Out):
    dataset_label: str
    count: int
    observations: list[ObservationOut]


class ModelVersionOut(_Out):
    target: str
    version: int
    model_type: str
    is_active: bool
    trained_at: str
    dataset_label: str
    dataset_hash: str
    train_year_from: str
    train_year_to: str
    parent_version: Optional[int] = None
    features: list[str]
    params: dict
    metrics: dict
    selection_mape_h1: dict[str, Optional[float]]
    holdout_metrics: dict
    interval_quantiles: dict[str, list[float]]
    interval_coverage_holdout: dict
    calibration: Optional[dict] = None
    calibration_evaluation: Optional[dict] = None
    artifact_path: str


class BacktestRow(_Out):
    target: str
    model: str
    state_code: str
    origin: str
    horizon: int
    target_year: str
    actual: float
    predicted: float


class BacktestResponse(_Out):
    target: str
    model_type: str
    count: int
    rows: list[BacktestRow]


class ImpactOut(_Out):
    key: str
    sector: str
    outcome: str
    outcome_name: str
    elasticity: Optional[float]
    ci_low: Optional[float]
    ci_high: Optional[float]
    n_obs: int
    n_states: int
    years: str
    within_r2: Optional[float]
    supported: bool
    expected_sign: int
    higher_is_better: bool
    note: str


class BaselineOut(_Out):
    state_code: str
    fiscal_year: str
    data_status: str
    revenue_expenditure: float
    capital_outlay: float
    committed: float
    social_sector: float
    other_revenue: float
    health: Optional[float]
    health_estimated: bool
    health_share_source_year: Optional[str]
    gross_fiscal_deficit: float
    revenue_deficit: float
    revenue_receipts: float
    own_tax_revenue: float
    interest_payments: float
    gsdp: Optional[float]
    population: Optional[float]
    cpi_inflation: Optional[float]
    discretionary: float
    gfd_pct_gsdp: Optional[float]


# ------------------------------------------------------------------------------------------------- forecast

class ForecastRequest(BaseModel):
    state_code: str = StateCode
    target: Target
    horizon: int = Field(3, ge=1, le=3)
    origin_fiscal_year: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}$")
    model_version: Optional[int] = Field(None, ge=1)


class SeriesPoint(_Out):
    fiscal_year: str
    value: float
    data_status: str


class PredictionPoint(_Out):
    fiscal_year: str
    horizon: int
    value: float
    uncalibrated_value: float
    lower: Optional[float]
    upper: Optional[float]


class ModelRef(_Out):
    target: str
    version: int
    model_type: str
    calibrated: bool


class ForecastResponse(_Out):
    state_code: str
    target: str
    unit: str
    origin_fiscal_year: str
    model: ModelRef
    history: list[SeriesPoint]
    government_estimates: list[SeriesPoint]
    predictions: list[PredictionPoint]
    backtest_metrics: dict
    interval_note: str


# ------------------------------------------------------------------------------------------------- scenario

class ScenarioRequest(BaseModel):
    state_code: str = StateCode
    fiscal_year: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}$")
    scenario_type: ScenarioType
    allocations: Optional[dict[ScenarioSector, float]] = None
    adjustments_pct: Optional[dict[ScenarioSector, float]] = None

    @model_validator(mode="after")
    def _custom_inputs(self):
        if self.scenario_type == "CUSTOM" and bool(self.allocations) == bool(self.adjustments_pct):
            raise ValueError("CUSTOM scenarios need exactly one of 'allocations' or 'adjustments_pct'")
        if self.scenario_type != "CUSTOM" and (self.allocations or self.adjustments_pct):
            raise ValueError("'allocations'/'adjustments_pct' are only used with scenario_type CUSTOM")
        return self


class FiscalPosition(_Out):
    discretionary: float
    gross_fiscal_deficit: float
    revenue_deficit: float
    gfd_pct_gsdp: Optional[float]
    rd_pct_gsdp: Optional[float]
    breaches_frbm_gfd_limit: bool


class ScenarioSectorOut(_Out):
    sector: str
    label: str
    baseline: float
    scenario: float
    change: float
    change_pct: Optional[float]
    baseline_share_pct: float
    scenario_share_pct: float
    per_capita_baseline: Optional[float]
    per_capita_scenario: Optional[float]
    per_capita_all_state_median: Optional[float]


class IndicatorEffect(_Out):
    indicator: str
    indicator_name: str
    driver_sector: str
    current_value: float
    current_as_of: str
    predicted_value: Optional[float]
    lower: Optional[float]
    upper: Optional[float]
    supported: bool
    elasticity: Optional[float]
    elasticity_ci: list[Optional[float]]
    method_note: str


class ScenarioResponse(_Out):
    state_code: str
    fiscal_year: str
    baseline_data_status: str
    scenario_type: str
    description: str
    baseline: BaselineOut
    sectors: list[ScenarioSectorOut]
    fiscal_effects: dict[Literal["baseline", "scenario"], FiscalPosition]
    indicator_effects: list[IndicatorEffect]
    notes: list[str]


# --------------------------------------------------------------------------------------------- optimisation

class SectorBound(BaseModel):
    min: Optional[float] = Field(None, ge=0)
    max: Optional[float] = Field(None, ge=0)
    min_share: Optional[float] = Field(None, ge=0, le=1)
    max_share: Optional[float] = Field(None, ge=0, le=1)


class OptimizeRequest(BaseModel):
    state_code: str = StateCode
    fiscal_year: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}$")
    objective: Objective = "MIN_ADJUSTMENT"
    total_budget: Optional[float] = Field(None, gt=0)
    bounds: Optional[dict[Sector, SectorBound]] = None
    social_floor: SocialFloor = "NONE"
    enforce_deficit_limit: bool = True
    weights: Optional[dict[Sector, float]] = None


class AllocationOut(_Out):
    sector: str
    label: str
    current: float
    recommended: float
    change: float
    change_pct: Optional[float]
    min: float
    max: float
    per_capita_current: Optional[float]
    per_capita_recommended: Optional[float]


class OptimizeResponse(_Out):
    state_code: str
    fiscal_year: str
    baseline_data_status: str
    objective: str
    status: str
    objective_value: float
    solver_message: str
    total_budget: float
    current_total: float
    deficit_room: Optional[float]
    social_floor: str
    allocation: list[AllocationOut]
    binding_constraints: list[str]
    fiscal_effects: dict[Literal["current", "recommended"], FiscalPosition]
    indicator_effects: list[IndicatorEffect]


# -------------------------------------------------------------------------------------------- stress testing

class ShockOverrides(BaseModel):
    revenue_shock_pct: Optional[float] = Field(None, ge=-100, le=100)
    inflation_pct: Optional[float] = Field(None, ge=-20, le=100)
    population_excess_growth_pct: Optional[float] = Field(None, ge=-10, le=20)
    gsdp_shortfall_pct: Optional[float] = Field(None, ge=-100, le=100)


class StressTestRequest(BaseModel):
    state_code: str = StateCode
    fiscal_year: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}$")
    allocation: Optional[dict[Sector, float]] = None
    overrides: Optional[ShockOverrides] = None
    scenarios: Optional[list[StressScenario]] = None


class StressResult(_Out):
    scenario: str
    metric: str
    normal: Optional[float]
    stressed: Optional[float]
    change_pct: Optional[float]
    breaches_limit: bool


class StressTestResponse(_Out):
    state_code: str
    fiscal_year: str
    baseline_data_status: str
    allocation: dict[str, float]
    calibration: dict
    results: list[StressResult]
    notes: list[str]


# ------------------------------------------------------------------------------------------ feedback & models

class EvaluationRecord(BaseModel):
    prediction_id: Optional[int] = None
    state_code: Optional[str] = None
    target: Optional[str] = None
    target_year: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}$")
    model_version: Optional[int] = None
    predicted: float = Field(gt=0)
    actual: float = Field(gt=0)


class EvaluateRequest(BaseModel):
    records: list[EvaluationRecord] = Field(min_length=1)
    group_by: list[Literal["state_code", "target", "model_version", "target_year"]] = []


class DeviationOut(_Out):
    prediction_id: Optional[int] = None
    state_code: Optional[str] = None
    target: Optional[str] = None
    target_year: Optional[str] = None
    model_version: Optional[int] = None
    predicted: float
    actual: float
    absolute_error: float
    percentage_error: float


class EvaluateResponse(_Out):
    records: list[DeviationOut]
    groups: list[dict]


class DeviationRecord(BaseModel):
    state_code: str = StateCode
    target_year: str = FiscalYear
    predicted: float = Field(gt=0)
    actual: float = Field(gt=0)


class CalibrateRequest(BaseModel):
    target: Target
    calibration_records: list[DeviationRecord] = Field(min_length=1)
    evaluation_records: list[DeviationRecord] = Field(min_length=1)
    model_version: Optional[int] = Field(None, ge=1)


class RetrainRequest(BaseModel):
    target: Target
    activate_if_better: bool = True


class RetrainResponse(ModelVersionOut):
    comparison: dict


class CalibrateResponse(ModelVersionOut):
    previous_active_version: int
