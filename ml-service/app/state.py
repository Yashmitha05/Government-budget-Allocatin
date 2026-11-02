"""In-memory service state: the processed dataset and derived reference results."""
from __future__ import annotations

import json
import logging
import threading
from pathlib import Path

import pandas as pd

from budget_ml.config import GER_FILE, ML_ROOT, MODEL_DIR, PROCESSED_DIR, TABLES
from budget_ml.impact import estimate_all
from budget_ml.preprocessing import DERIVED_COLUMNS, FORECAST_TARGETS, load_processed
from budget_ml.registry import active_version
from budget_ml.states import load_states

log = logging.getLogger("ml-service")

_DERIVED = {
    "population": ("Population (derived: NSDP / per-capita NSDP)", "PERSONS"),
    "committed_expenditure": ("Committed expenditure (interest + pension)", "INR_CRORE"),
    "total_expenditure": ("Total expenditure (revenue + capital)", "INR_CRORE"),
    "social_ex_health": ("Social sector excluding health", "INR_CRORE"),
}


class ServiceState:
    def __init__(self, processed_dir: Path = PROCESSED_DIR, model_root: Path = MODEL_DIR):
        self.processed_dir = Path(processed_dir)
        self.model_root = Path(model_root)
        self.write_lock = threading.Lock()   # serialises retrain / calibrate writes to the registry
        self.reload()

    def reload(self) -> None:
        obs, panel, meta = load_processed(self.processed_dir)
        quality = json.loads((self.processed_dir / "quality_report.json").read_text(encoding="utf-8"))
        states, _ = load_states()
        self.observations: pd.DataFrame = obs
        self.panel: pd.DataFrame = panel
        self.metadata: dict = meta
        self.quality: dict = quality
        self.states = states
        self.categories = {c: s.category for c, s in states.items()}
        log.info("estimating impact models for dataset %s", meta["label"])
        self.impact_results = [r.as_dict() for r in estimate_all(panel, self.categories)]
        bt = ML_ROOT / "reports" / "backtest_predictions.csv"
        self.backtest = pd.read_csv(bt) if bt.exists() else pd.DataFrame()

    def active_models(self) -> dict[str, int | None]:
        return {t: active_version(t, self.model_root) for t in FORECAST_TARGETS}

    def indicators(self) -> list[dict]:
        out = [{"code": t.indicator, "name": t.name, "unit": t.unit, "category": t.category,
                "source_table": t.source_table, "is_forecast_target": t.is_forecast_target} for t in TABLES]
        for code in sorted(self.observations.indicator.unique()):
            if code.startswith("ger_"):
                out.append({"code": code, "name": f"Gross enrolment ratio, {code[4:]} level (total)", "unit": "PERCENT",
                            "category": "OUTCOME", "source_table": "RBI HSIS T1 (UDISE+)", "is_forecast_target": False})
            elif code.startswith("india_"):
                src = self.observations.loc[self.observations.indicator == code, "source_table"].iloc[0]
                out.append({"code": code, "name": code.replace("_", " ").replace("india", "India,", 1), "unit": "NATIONAL",
                            "category": "ECONOMIC", "source_table": src, "is_forecast_target": False})
        for code in DERIVED_COLUMNS:
            name, unit = _DERIVED[code]
            out.append({"code": code, "name": name, "unit": unit, "category": "DERIVED", "source_table": "derived",
                        "is_forecast_target": False})
        return out

    def state_list(self) -> list[dict]:
        with_budget = set(self.panel.loc[self.panel.revenue_expenditure.notna(), "state_code"])
        return [{"code": s.code, "name": s.name, "category": s.category, "formed_fiscal_year": s.formed_fiscal_year,
                 "has_budget_data": s.code in with_budget} for s in self.states.values()]


__all__ = ["ServiceState", "GER_FILE"]
