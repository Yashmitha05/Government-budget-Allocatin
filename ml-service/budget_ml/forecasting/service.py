"""Live forecasting with a registered model version."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ..config import MODEL_DIR, TABLES
from ..errors import InsufficientHistory, InvalidInput, NotFound
from ..preprocessing import FORECAST_TARGETS
from ..registry import load
from ..years import fy_label, fy_start
from .features import StateSeries, origin_features, recursive_forecast
from .train import MAX_HORIZON

UNITS = {t.indicator: t.unit for t in TABLES}


def apply_calibration(value: float, calibration: dict | None, state: str) -> float:
    """Multiply by exp(-bias) where bias = shrunk median ln(predicted/actual) for the state."""
    if not calibration:
        return value
    bias = calibration["state_bias"].get(state, calibration.get("panel_bias", 0.0))
    return value * float(np.exp(-bias))


def forecast(panel: pd.DataFrame, state: str, target: str, horizon: int = 3,
             origin_fiscal_year: str | None = None, version: int | None = None, root=MODEL_DIR) -> dict:
    if target not in FORECAST_TARGETS:
        raise NotFound(f"Unknown forecast target '{target}'", [f"valid targets: {FORECAST_TARGETS}"])
    if state not in set(panel.state_code):
        raise NotFound(f"Unknown state code '{state}'")
    if not 1 <= horizon <= MAX_HORIZON:
        raise InvalidInput(f"horizon must be between 1 and {MAX_HORIZON}")
    artefact, meta = load(target, version, root)
    s = StateSeries.from_panel(panel, state, target)
    if not s.y:
        raise InsufficientHistory(f"No actual values of {target} for {state}")
    origin = fy_start(origin_fiscal_year) if origin_fiscal_year else max(s.y)
    if origin not in s.y or origin_features(s, origin) is None:
        raise InsufficientHistory(
            f"{state} needs 4 consecutive actual years of {target} ending {fy_label(origin)} without a structural break")
    preds = recursive_forecast(artefact["model"], s, origin, horizon)
    if preds is None:
        raise InsufficientHistory(f"Model inputs (e.g. GSDP) unavailable for {state} at {fy_label(origin)}")

    q = artefact["interval_quantiles"]
    out_preds = []
    for h, raw in enumerate(preds, start=1):
        # interval quantiles are of ln(actual / uncalibrated prediction), so they apply to the raw value;
        # the calibrated point estimate is a bias-corrected value inside that interval
        p = apply_calibration(raw, artefact.get("calibration"), state)
        lo, hi = q.get(h, (None, None))
        out_preds.append({"fiscal_year": fy_label(origin + h), "horizon": h, "value": p, "uncalibrated_value": raw,
                          "lower": min(p, raw * float(np.exp(lo))) if lo is not None else None,
                          "upper": max(p, raw * float(np.exp(hi))) if hi is not None else None})

    g = panel[panel.state_code == state].set_index("fy_start").sort_index()
    history = [{"fiscal_year": fy_label(int(y)), "value": float(v), "data_status": st}
               for y, v, st in zip(g.index, g[target], g["fiscal_status"]) if pd.notna(v) and y <= origin]
    gov = [{"fiscal_year": fy_label(int(y)), "value": float(v), "data_status": st}
           for y, v, st in zip(g.index, g[target], g["fiscal_status"]) if pd.notna(v) and st in ("RE", "BE")]
    return {
        "state_code": state, "target": target, "unit": UNITS[target],
        "origin_fiscal_year": fy_label(origin),
        "model": {"target": target, "version": meta["version"], "model_type": meta["model_type"],
                  "calibrated": bool(artefact.get("calibration"))},
        "history": history, "government_estimates": gov, "predictions": out_preds,
        "backtest_metrics": meta.get("metrics", {}),
        "interval_note": "80% empirical interval from out-of-time backtest errors",
    }
