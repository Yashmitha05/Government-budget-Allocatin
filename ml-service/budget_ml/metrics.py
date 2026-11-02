"""Error metrics on levels (₹ crore). MAPE excludes zero actuals."""
from __future__ import annotations

import numpy as np


def regression_metrics(actual, predicted) -> dict:
    a = np.asarray(actual, dtype=float)
    p = np.asarray(predicted, dtype=float)
    if a.size == 0:
        return {"n": 0, "mae": None, "rmse": None, "mape": None, "median_ape": None, "r2": None, "bias_pct": None}
    err = p - a
    nz = a != 0
    ape = np.abs(err[nz] / a[nz]) * 100
    ss_res = float(np.sum(err ** 2))
    ss_tot = float(np.sum((a - a.mean()) ** 2))
    return {
        "n": int(a.size),
        "mae": float(np.mean(np.abs(err))),
        "rmse": float(np.sqrt(np.mean(err ** 2))),
        "mape": float(np.mean(ape)) if ape.size else None,
        "median_ape": float(np.median(ape)) if ape.size else None,
        "r2": 1 - ss_res / ss_tot if ss_tot > 0 else None,
        "bias_pct": float(np.mean(err[nz] / a[nz]) * 100) if ape.size else None,  # signed mean % error
    }
