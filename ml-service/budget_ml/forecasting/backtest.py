"""Rolling-origin (expanding window) multi-horizon backtest.

For each test origin T: fit on rows with origin <= T-1 (their targets, year <= T, are known at T),
forecast years T+1..T+H recursively from data known at T, and score against published actuals.
All models are scored on the same (state, origin, horizon) rows so results are comparable.
"""
from __future__ import annotations

import copy

import numpy as np
import pandas as pd

from ..metrics import regression_metrics
from ..years import fy_label
from .features import StateSeries, build_supervised, recursive_forecast, spans_break
from .models import candidate_models

DEFAULT_TEST_ORIGINS = range(2014, 2022)   # one-step targets 2015-16 .. 2022-23
SELECTION_ORIGINS = range(2014, 2020)      # model selection and interval calibration
HOLDOUT_ORIGINS = range(2020, 2022)        # final, untouched evaluation (targets 2021-22, 2022-23)


def backtest(panel: pd.DataFrame, target: str, test_origins=DEFAULT_TEST_ORIGINS, models=None,
             horizon: int = 1) -> pd.DataFrame:
    sup = build_supervised(panel, target, include_unlabelled=False)
    series = {s: StateSeries.from_panel(panel, s, target) for s in sorted(panel.state_code.unique())}
    models = models or candidate_models()
    out = []
    for T in test_origins:
        train = sup[sup.origin <= T - 1]
        fitted = {}
        for m in models:
            mm = copy.deepcopy(m)
            try:
                fitted[m.name] = mm.fit(train)
            except ValueError:
                fitted[m.name] = None
        for state, s in series.items():
            if T not in s.y:
                continue
            for name, mm in fitted.items():
                preds = recursive_forecast(mm, s, T, horizon) if mm is not None else None
                for h in range(1, horizon + 1):
                    yr = T + h
                    if yr not in s.y or spans_break(state, T, yr):
                        continue
                    out.append({"target": target, "model": name, "state_code": state,
                                "origin": fy_label(T), "origin_start": T, "horizon": h,
                                "target_year": fy_label(yr), "y_origin": s.y[T], "actual": s.y[yr],
                                "predicted": preds[h - 1] if preds else np.nan})
    res = pd.DataFrame(out)
    if res.empty:
        return res
    complete = res.groupby(["state_code", "origin", "horizon"])["predicted"].transform(lambda x: x.notna().all())
    return res[complete].reset_index(drop=True)


def summarise(res: pd.DataFrame, by: list[str] | None = None) -> pd.DataFrame:
    keys = ["model"] + (by or [])
    rows = [{**dict(zip(keys, k if isinstance(k, tuple) else (k,))), **regression_metrics(g.actual, g.predicted)}
            for k, g in res.groupby(keys)]
    return pd.DataFrame(rows).sort_values(keys[1:] + ["mape"]).reset_index(drop=True)


def summarise_by_year(res: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (name, year), g in res[res.horizon == 1].groupby(["model", "target_year"]):
        m = regression_metrics(g.actual, g.predicted)
        rows.append({"model": name, "target_year": year, "n": m["n"], "mape": m["mape"]})
    return pd.DataFrame(rows).pivot(index="target_year", columns="model", values="mape")


def log_errors(res: pd.DataFrame) -> pd.Series:
    """ln(actual / predicted): positive = under-forecast."""
    return np.log(res.actual / res.predicted)
