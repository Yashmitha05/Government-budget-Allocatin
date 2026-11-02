"""Model selection, interval calibration and final training for one forecast target.

Protocol (documented in ML_PIPELINE.md §3):
  1. Multi-horizon rolling-origin backtest of all candidates.
  2. Selection on origins 2014-15..2019-20 (horizon 1 MAPE). A learned model is chosen only if it
     beats the better of the two baselines; otherwise that baseline is chosen.
  3. Prediction intervals = 10th/90th percentiles of the chosen model's selection-window log errors
     per horizon; their coverage is then measured on the hold-out origins.
  4. Hold-out (origins 2020-21, 2021-22) metrics are reported for every model; never used to choose.
  5. The chosen model is refitted on all labelled rows and saved as a new registry version.
"""
from __future__ import annotations

import copy

import numpy as np
import pandas as pd

from ..config import MODEL_DIR
from ..metrics import regression_metrics
from ..registry import save_version
from ..years import fy_label
from .backtest import HOLDOUT_ORIGINS, SELECTION_ORIGINS, backtest, log_errors
from .features import build_supervised
from .models import BASELINES, candidate_models

MAX_HORIZON = 3
INTERVAL_QUANTILES = (0.10, 0.90)


def _metrics_table(res: pd.DataFrame) -> dict:
    out = {}
    for (model, h), g in res.groupby(["model", "horizon"]):
        out.setdefault(model, {})[f"h{h}"] = regression_metrics(g.actual, g.predicted)
    return out


def select_model(selection_res: pd.DataFrame) -> tuple[str, dict]:
    h1 = selection_res[selection_res.horizon == 1]
    mape = {m: regression_metrics(g.actual, g.predicted)["mape"] for m, g in h1.groupby("model")}
    best_baseline = min(BASELINES, key=lambda b: mape[b])
    learned = {m: v for m, v in mape.items() if m not in BASELINES and v < mape[best_baseline]}
    chosen = min(learned, key=learned.get) if learned else best_baseline
    return chosen, mape


def interval_quantiles(res: pd.DataFrame, model: str) -> dict[int, tuple[float, float]]:
    q = {}
    for h, g in res[res.model == model].groupby("horizon"):
        e = log_errors(g)
        q[int(h)] = (float(e.quantile(INTERVAL_QUANTILES[0])), float(e.quantile(INTERVAL_QUANTILES[1])))
    return q


def interval_coverage(res: pd.DataFrame, model: str, q: dict) -> dict:
    out = {}
    for h, g in res[res.model == model].groupby("horizon"):
        if int(h) not in q:
            continue
        lo, hi = q[int(h)]
        inside = (g.actual >= g.predicted * np.exp(lo)) & (g.actual <= g.predicted * np.exp(hi))
        out[f"h{int(h)}"] = {"n": int(len(g)), "coverage": float(inside.mean()) if len(g) else None}
    return out


def train_target(panel: pd.DataFrame, target: str, dataset_meta: dict, root=MODEL_DIR,
                 activate: bool = True, parent_version: int | None = None) -> tuple[dict, pd.DataFrame]:
    res = backtest(panel, target, test_origins=range(min(SELECTION_ORIGINS), max(HOLDOUT_ORIGINS) + 1),
                   horizon=MAX_HORIZON)
    sel = res[res.origin_start.isin(SELECTION_ORIGINS)]
    hold = res[res.origin_start.isin(HOLDOUT_ORIGINS)]
    chosen, selection_mape = select_model(sel)
    q = interval_quantiles(sel, chosen)

    sup = build_supervised(panel, target, include_unlabelled=False)
    proto = next(m for m in candidate_models() if m.name == chosen)
    final = copy.deepcopy(proto).fit(sup)

    metadata = {
        "model_type": chosen,
        "features": list(proto.features),
        "params": dict(proto.params),
        "dataset_label": dataset_meta["label"],
        "dataset_hash": dataset_meta["content_hash"],
        "train_year_from": fy_label(int(sup.origin.min()) + 1),
        "train_year_to": fy_label(int(sup.origin.max()) + 1),
        "n_train_rows": int(len(sup)),
        "selection_window": f"origins {fy_label(min(SELECTION_ORIGINS))}..{fy_label(max(SELECTION_ORIGINS))}",
        "holdout_window": f"origins {fy_label(min(HOLDOUT_ORIGINS))}..{fy_label(max(HOLDOUT_ORIGINS))}",
        "selection_mape_h1": selection_mape,
        "holdout_metrics": _metrics_table(hold),
        "metrics": _metrics_table(hold).get(chosen, {}).get("h1", {}),
        "interval_quantiles": {f"h{h}": v for h, v in q.items()},
        "interval_coverage_holdout": interval_coverage(hold, chosen, q),
        "calibration": None,
        "parent_version": parent_version,
    }
    artefact = {"model": final, "interval_quantiles": q, "calibration": None}
    meta = save_version(target, artefact, metadata, activate=activate, root=root)
    return meta, res
