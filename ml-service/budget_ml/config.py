"""Paths and the catalogue of source tables used by the pipeline."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ML_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.environ.get("BUDGET_DATA_DIR", ML_ROOT.parent / "data")).resolve()
RAW_RBI_DIR = DATA_DIR / "raw" / "rbi_hsis_2024_25"
RAW_WB_DIR = DATA_DIR / "raw" / "world_bank"
PROCESSED_DIR = DATA_DIR / "processed"
MODEL_DIR = Path(os.environ.get("BUDGET_MODEL_DIR", ML_ROOT / "models")).resolve()
STATE_MAPPING_CSV = Path(__file__).resolve().parent / "data" / "state_mapping.csv"

# Year label conventions in the source tables.
FISCAL = "fiscal"          # "2014-15"
CALENDAR = "calendar"      # 2014 -> fiscal year 2014-15 (SRS calendar-year rates)
END_MARCH = "end_march"    # 2014 (as at end-March) -> fiscal year 2013-14


@dataclass(frozen=True)
class TableSpec:
    file: str
    indicator: str
    name: str
    unit: str
    category: str              # FISCAL | ECONOMIC | OUTCOME
    source_table: str
    year_kind: str = FISCAL
    scale: float = 1.0         # multiply source values (e.g. ₹ lakh -> ₹ crore = 0.01)
    sheet_contains: str | None = None   # keep only sheets whose title block contains this text
    is_forecast_target: bool = False
    status_default: str = "OBSERVED"    # status for year columns without an (A)/(RE)/(BE) tag
    notes: tuple[str, ...] = field(default_factory=tuple)


_FISCAL = dict(unit="INR_CRORE", category="FISCAL", status_default="ACCOUNTS")

TABLES: tuple[TableSpec, ...] = (
    TableSpec("T164_gross_fiscal_deficit.xlsx", "gross_fiscal_deficit", "Gross fiscal deficit", source_table="RBI HSIS T164", **_FISCAL),
    TableSpec("T165_revenue_deficit.xlsx", "revenue_deficit", "Revenue deficit", source_table="RBI HSIS T165", **_FISCAL),
    TableSpec("T166_revenue_expenditure.xlsx", "revenue_expenditure", "Revenue expenditure", source_table="RBI HSIS T166", is_forecast_target=True, **_FISCAL),
    TableSpec("T168_own_tax_revenue.xlsx", "own_tax_revenue", "Own tax revenue", source_table="RBI HSIS T168", is_forecast_target=True, **_FISCAL),
    TableSpec("T169_own_non_tax_revenue.xlsx", "own_non_tax_revenue", "Own non-tax revenue", source_table="RBI HSIS T169", is_forecast_target=True, **_FISCAL),
    TableSpec("T170_interest_payments.xlsx", "interest_payments", "Interest payments", source_table="RBI HSIS T170", **_FISCAL),
    TableSpec("T171_pension.xlsx", "pension", "Pension", source_table="RBI HSIS T171", **_FISCAL),
    TableSpec("T172_capital_receipts.xlsx", "capital_receipts", "Capital receipts", source_table="RBI HSIS T172", **_FISCAL),
    TableSpec("T173_capital_expenditure.xlsx", "capital_expenditure", "Capital expenditure", source_table="RBI HSIS T173", is_forecast_target=True, **_FISCAL),
    TableSpec("T174_capital_outlay.xlsx", "capital_outlay", "Capital outlay", source_table="RBI HSIS T174", **_FISCAL),
    TableSpec("T175_social_sector_expenditure.xlsx", "social_sector_expenditure", "Social sector expenditure", source_table="RBI HSIS T175", is_forecast_target=True, **_FISCAL),
    TableSpec("T018_public_expenditure_health.xlsx", "health_expenditure", "Public expenditure on health", "INR_CRORE", "FISCAL", "RBI HSIS T18 (NHP/CBHI)"),
    TableSpec("T019_per_capita_nsdp_current.xlsx", "per_capita_nsdp_current", "Per capita NSDP (current prices)", "INR", "ECONOMIC", "RBI HSIS T19"),
    TableSpec("T021_gsdp_current.xlsx", "gsdp_current", "GSDP (current prices)", "INR_CRORE", "ECONOMIC", "RBI HSIS T21", scale=0.01),
    TableSpec("T022_gsdp_constant.xlsx", "gsdp_constant", "GSDP (constant 2011-12 prices)", "INR_CRORE", "ECONOMIC", "RBI HSIS T22", scale=0.01),
    TableSpec("T023_nsdp_current.xlsx", "nsdp_current", "NSDP (current prices)", "INR_CRORE", "ECONOMIC", "RBI HSIS T23", scale=0.01),
    TableSpec("T108_cpi_inflation_general.xlsx", "cpi_inflation", "Average CPI inflation (general)", "PERCENT", "ECONOMIC", "RBI HSIS T108"),
    TableSpec("T004_infant_mortality_rate.xlsx", "infant_mortality_rate", "Infant mortality rate", "PER_1000", "OUTCOME", "RBI HSIS T4 (SRS)", year_kind=CALENDAR),
    TableSpec("T146_length_of_roads.xlsx", "road_length", "Length of roads", "KM", "OUTCOME", "RBI HSIS T146", year_kind=END_MARCH),
    TableSpec("T008_unemployment_rural.xlsx", "unemployment_rural", "Unemployment rate, rural (UPS adjusted)", "PER_1000", "OUTCOME", "RBI HSIS T8 (PLFS)", sheet_contains="Overall"),
    TableSpec("T009_unemployment_urban.xlsx", "unemployment_urban", "Unemployment rate, urban (UPS adjusted)", "PER_1000", "OUTCOME", "RBI HSIS T9 (PLFS)", sheet_contains="Overall"),
)

GER_FILE = "T001_gross_enrolment_ratio.xlsx"

# World Bank WDI series for India (national context and stress calibration).
WDI_SERIES: dict[str, str] = {
    "FP.CPI.TOTL.ZG": "india_cpi_inflation",
    "NY.GDP.MKTP.CN": "india_gdp_current_lcu",
    "NY.GDP.MKTP.KD.ZG": "india_real_gdp_growth",
    "SP.POP.TOTL": "india_population",
    "SP.POP.GROW": "india_population_growth",
    "GC.REV.XGRT.GD.ZS": "india_revenue_pct_gdp",
}
