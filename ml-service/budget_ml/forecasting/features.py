"""Growth-rate features for fiscal forecasting.

For state s, origin year t (data known up to t) and target series y:
    target  g_next = ln(y[t+1] / y[t])
    features
      g1, g2      = ln(y[t]/y[t-1]), ln(y[t-1]/y[t-2])
      g3_mean     = mean growth over the last 3 years
      cpi_nat     = national CPI inflation in year t (World Bank; full coverage)
      gsdp_g1     = ln(gsdp[t]/gsdp[t-1])      (state nominal GSDP; from 2012-13)
      ratio_gsdp  = y[t] / gsdp[t]
Only Accounts/Actual values are used as y (never RE/BE), and no window may span a structural break.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from ..preprocessing import ACTUAL_STATUSES, STRUCTURAL_BREAKS

CORE_FEATURES = ["g1", "g2", "g3_mean", "cpi_nat"]
MACRO_FEATURES = CORE_FEATURES + ["gsdp_g1", "ratio_gsdp"]
LAGS_NEEDED = 3   # y[t-3] .. y[t]


def spans_break(state: str, first_year: int, last_year: int) -> bool:
    """True if a break year falls in (first_year, last_year] - i.e. the window compares across it."""
    return any(first_year < b <= last_year for b in STRUCTURAL_BREAKS.get(state, ()))


@dataclass
class StateSeries:
    """One state's inputs: actual target values, nominal GSDP and national CPI, keyed by fy_start."""
    state_code: str
    y: dict[int, float]
    gsdp: dict[int, float]
    cpi: dict[int, float]

    @classmethod
    def from_panel(cls, panel: pd.DataFrame, state: str, target: str) -> "StateSeries":
        g = panel[panel.state_code == state].set_index("fy_start").sort_index()
        actual = g[target].where(g["fiscal_status"].isin(ACTUAL_STATUSES))
        clean = lambda s: {int(k): float(v) for k, v in s.items() if pd.notna(v)}  # noqa: E731
        return cls(state, clean(actual[actual > 0]),
                   clean(g["gsdp_current"]) if "gsdp_current" in g else {},
                   clean(g["india_cpi_inflation"]) if "india_cpi_inflation" in g else {})


def feature_row(y: dict[int, float], t: int, gsdp_t: float | None, gsdp_g1: float | None, cpi_t: float | None) -> dict | None:
    """Features at origin t from a value series y (actual or partly predicted). None if lags are missing."""
    window = [t - k for k in range(LAGS_NEEDED, -1, -1)]
    if any(w not in y for w in window):
        return None
    growth = np.diff(np.log([y[w] for w in window]))
    return {
        "y_origin": y[t], "g1": float(growth[-1]), "g2": float(growth[-2]), "g3_mean": float(growth.mean()),
        "cpi_nat": cpi_t if cpi_t is not None else np.nan,
        "gsdp_g1": gsdp_g1 if gsdp_g1 is not None else np.nan,
        "ratio_gsdp": y[t] / gsdp_t if gsdp_t else np.nan,
    }


def origin_features(s: StateSeries, t: int) -> dict | None:
    """Features at a real origin t (no break inside the lag window)."""
    if spans_break(s.state_code, t - LAGS_NEEDED, t):
        return None
    g1 = None
    if t in s.gsdp and t - 1 in s.gsdp and s.gsdp[t - 1] > 0:
        g1 = float(np.log(s.gsdp[t] / s.gsdp[t - 1]))
    return feature_row(s.y, t, s.gsdp.get(t) if g1 is not None else None, g1, s.cpi.get(t))


def build_supervised(panel: pd.DataFrame, target: str, include_unlabelled: bool = True) -> pd.DataFrame:
    """One row per (state, origin year t). `g_next` is NaN where y[t+1] is not an actual (e.g. RE/BE or future)."""
    rows = []
    for state in sorted(panel.state_code.unique()):
        s = StateSeries.from_panel(panel, state, target)
        for t in sorted(s.y):
            feats = origin_features(s, t)
            if feats is None:
                continue
            nxt = t + 1
            valid_next = nxt in s.y and not spans_break(state, t, nxt)
            row = {"state_code": state, "origin": t, **feats,
                   "y_next": s.y[nxt] if valid_next else np.nan,
                   "g_next": float(np.log(s.y[nxt] / s.y[t])) if valid_next else np.nan}
            if valid_next or include_unlabelled:
                rows.append(row)
    return pd.DataFrame(rows)


def recursive_forecast(model, s: StateSeries, origin: int, horizon: int) -> list[float] | None:
    """Predict y[origin+1..origin+horizon] by feeding predictions back as lags.

    Exogenous inputs are held at their origin-year values (no future information):
    national CPI at its origin value, state GSDP growing at its origin-year growth rate.
    """
    base = origin_features(s, origin)
    if base is None:
        return None
    y = {k: v for k, v in s.y.items() if k <= origin}
    gsdp_g1 = None if np.isnan(base["gsdp_g1"]) else base["gsdp_g1"]
    gsdp0 = s.gsdp.get(origin) if gsdp_g1 is not None else None
    cpi = None if np.isnan(base["cpi_nat"]) else base["cpi_nat"]
    preds = []
    for h in range(horizon):
        t = origin + h
        gsdp_t = gsdp0 * float(np.exp(gsdp_g1 * h)) if gsdp0 is not None else None
        row = feature_row(y, t, gsdp_t, gsdp_g1, cpi)
        g = float(model.predict(pd.DataFrame([row]))[0])
        if np.isnan(g):
            return None
        y[t + 1] = y[t] * float(np.exp(g))
        preds.append(y[t + 1])
    return preds
