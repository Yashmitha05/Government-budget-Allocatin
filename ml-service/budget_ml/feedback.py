"""Closed feedback loop: prediction vs actual -> deviation -> calibration -> new model version.

Deviation record: predicted, actual, absolute_error = |p - a|, percentage_error = (p - a) / a * 100 (signed),
log_error = ln(p / a).

Calibration: per-state bias b_s = shrunk median log error of the state's most recent K deviation records,
    b_s = n/(n+M) * median_s + M/(n+M) * median_panel
and the calibrated prediction is p * exp(-b_s). A calibrated version is activated only if it lowers MAPE on
deviation records that were NOT used to estimate the bias (later target years).
"""
from __future__ import annotations

import copy

import numpy as np
import pandas as pd

from .config import MODEL_DIR
from .errors import InvalidInput
from .metrics import regression_metrics
from .registry import active_version, load, save_version
from .years import fy_start

RECENT_K = 5     # most recent records per state used for its bias
SHRINK_M = 3     # pseudo-observations pulling the state bias toward the panel median


def deviations(records: list[dict] | pd.DataFrame) -> pd.DataFrame:
    df = pd.DataFrame(records).copy()
    for col in ("predicted", "actual"):
        if col not in df:
            raise InvalidInput(f"Each record needs '{col}'")
    if (df.actual <= 0).any() or (df.predicted <= 0).any():
        raise InvalidInput("Predicted and actual values must be positive for deviation analysis")
    df["absolute_error"] = (df.predicted - df.actual).abs()
    df["percentage_error"] = 100 * (df.predicted - df.actual) / df.actual
    df["log_error"] = np.log(df.predicted / df.actual)
    return df


def evaluate(records: list[dict], group_by: list[str] | None = None) -> dict:
    df = deviations(records)
    groups = []
    keys = [k for k in (group_by or []) if k in df]
    for key, g in (df.groupby(keys) if keys else [((), df)]):
        m = regression_metrics(g.actual, g.predicted)
        groups.append({**dict(zip(keys, key if isinstance(key, tuple) else (key,))), **m})
    cols = [c for c in df.columns if c != "log_error"]
    return {"records": df[cols].to_dict(orient="records"), "groups": groups}


def estimate_calibration(devs: pd.DataFrame, recent_k: int = RECENT_K, shrink_m: int = SHRINK_M) -> dict:
    if devs.empty:
        raise InvalidInput("No deviation records to calibrate from")
    d = devs.copy()
    d["_y"] = d.target_year.map(fy_start)
    panel_bias = float(d.log_error.median())
    state_bias, counts = {}, {}
    for s, g in d.groupby("state_code"):
        g = g.sort_values("_y").tail(recent_k)
        n = len(g)
        state_bias[s] = float(n / (n + shrink_m) * g.log_error.median() + shrink_m / (n + shrink_m) * panel_bias)
        counts[s] = n
    return {"panel_bias": panel_bias, "state_bias": state_bias, "records_per_state": counts,
            "recent_k": recent_k, "shrink_m": shrink_m, "n_records": int(len(d)),
            "target_years": f"{d.target_year.min()}..{d.target_year.max()}"}


def apply_bias(devs: pd.DataFrame, cal: dict) -> pd.Series:
    b = devs.state_code.map(cal["state_bias"]).fillna(cal["panel_bias"])
    return devs.predicted * np.exp(-b)


def calibrate_version(target: str, calibration_records: pd.DataFrame, evaluation_records: pd.DataFrame,
                      root=MODEL_DIR, version: int | None = None) -> dict:
    """Create a calibrated child of `version` (default: active). Activate only if it improves evaluation MAPE."""
    if calibration_records.empty or evaluation_records.empty:
        raise InvalidInput("Need both calibration and evaluation deviation records")
    if fy_start(evaluation_records.target_year.min()) <= fy_start(calibration_records.target_year.max()):
        raise InvalidInput("Evaluation records must be for later years than the calibration records")
    artefact, parent = load(target, version, root)
    cal_dev = deviations(calibration_records)
    ev = deviations(evaluation_records)
    cal = estimate_calibration(cal_dev)
    before = regression_metrics(ev.actual, ev.predicted)
    after = regression_metrics(ev.actual, apply_bias(ev, cal))
    improved = after["mape"] < before["mape"]
    child = copy.copy(artefact)
    child["calibration"] = cal
    meta = {k: v for k, v in parent.items() if k not in ("version", "trained_at", "artifact_path", "is_active")}
    meta.update({
        "parent_version": parent["version"],
        "calibration": {k: v for k, v in cal.items() if k != "records_per_state"},
        "calibration_evaluation": {"evaluated_on": f"{ev.target_year.min()}..{ev.target_year.max()}",
                                   "mape_before": before["mape"], "mape_after": after["mape"],
                                   "bias_pct_before": before["bias_pct"], "bias_pct_after": after["bias_pct"],
                                   "n": before["n"], "improved": improved},
        "metrics": after if improved else parent.get("metrics", {}),
    })
    saved = save_version(target, child, meta, activate=improved, root=root)
    saved["previous_active_version"] = parent["version"]
    return saved


def retrain(panel: pd.DataFrame, target: str, dataset_meta: dict, root=MODEL_DIR, activate_if_better: bool = True) -> dict:
    """Refit with the full selection protocol on the (possibly new) dataset, as a child of the active version."""
    from .forecasting.train import train_target   # local import: train imports registry too

    parent_v = active_version(target, root)
    parent = load(target, parent_v, root)[1] if parent_v else None
    meta, _ = train_target(panel, target, dataset_meta, root=root, activate=False, parent_version=parent_v)
    new_mape = (meta.get("metrics") or {}).get("mape")
    old_mape = (parent.get("metrics") or {}).get("mape") if parent else None
    activate = (not activate_if_better) or parent is None or old_mape is None or (
        new_mape is not None and new_mape <= old_mape) or parent.get("dataset_hash") != dataset_meta["content_hash"]
    if activate:
        from .registry import set_active
        set_active(target, meta["version"], root)
    meta["is_active"] = activate
    meta["comparison"] = {"parent_version": parent_v, "parent_mape": old_mape, "new_mape": new_mape,
                          "new_dataset": bool(parent and parent.get("dataset_hash") != dataset_meta["content_hash"])}
    return meta
