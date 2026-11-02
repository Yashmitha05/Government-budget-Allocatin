import numpy as np
import pandas as pd
import pytest

from budget_ml.errors import InsufficientHistory, InvalidInput, NotFound
from budget_ml.forecasting.backtest import backtest, summarise
from budget_ml.forecasting.features import StateSeries, build_supervised, recursive_forecast
from budget_ml.forecasting.models import CORE_FEATURES, Drift3y, NaiveLast, SklearnGrowth
from budget_ml.forecasting.service import forecast
from budget_ml.forecasting.train import interval_coverage, select_model
from budget_ml.metrics import regression_metrics


def test_metrics_known_values():
    m = regression_metrics([100, 200], [110, 180])
    assert m["mae"] == 15 and m["mape"] == pytest.approx(10.0) and m["bias_pct"] == pytest.approx(0.0)
    assert m["rmse"] == pytest.approx(np.sqrt((100 + 400) / 2))


def test_metrics_skip_zero_actuals_for_mape():
    assert regression_metrics([0, 100], [5, 110])["mape"] == pytest.approx(10.0)


@pytest.fixture(scope="module")
def sup(panel):
    return build_supervised(panel, "revenue_expenditure", include_unlabelled=True)


def test_targets_are_actuals_only(panel, sup):
    status = panel.set_index(["state_code", "fy_start"]).fiscal_status
    labelled = sup.dropna(subset=["g_next"])
    assert {status[(s, o + 1)] for s, o in zip(labelled.state_code, labelled.origin)} <= {"ACCOUNTS", "ACTUAL"}
    assert labelled.origin.max() == 2021      # last target 2022-23 (Actual); RE/BE never targets


def test_no_window_spans_structural_break(sup):
    ap = sup[sup.state_code == "AP"]
    assert not ap.origin.between(2014, 2016).any()
    assert ap[ap.origin == 2013].g_next.isna().all()


def test_feature_math(panel, sup):
    ka = panel[panel.state_code == "KA"].set_index("fy_start").revenue_expenditure
    row = sup[(sup.state_code == "KA") & (sup.origin == 2018)].iloc[0]
    assert row.g1 == pytest.approx(np.log(ka[2018] / ka[2017]))
    assert row.g3_mean == pytest.approx(np.log(ka[2018] / ka[2015]) / 3)
    assert row.g_next == pytest.approx(np.log(ka[2019] / ka[2018]))


def test_recursive_drift_matches_hand_calculation(panel):
    s = StateSeries.from_panel(panel, "KA", "revenue_expenditure")
    y = s.y
    preds = recursive_forecast(Drift3y(), s, 2020, 2)
    g1 = np.log(y[2020] / y[2017]) / 3
    p1 = y[2020] * np.exp(g1)
    g2 = np.log(p1 / y[2018]) / 3
    assert preds[0] == pytest.approx(p1) and preds[1] == pytest.approx(p1 * np.exp(g2))


def test_recursive_uses_no_values_after_origin(panel):
    s = StateSeries.from_panel(panel, "KA", "revenue_expenditure")
    full = recursive_forecast(Drift3y(), s, 2019, 3)
    s.y = {k: v for k, v in s.y.items() if k <= 2019}
    assert recursive_forecast(Drift3y(), s, 2019, 3) == pytest.approx(full)


class _Spy(NaiveLast):
    seen: list = []       # shared across deep copies made by the backtest

    def fit(self, df):
        _Spy.seen.append(int(df.origin.max()))
        return self


def test_backtest_has_no_lookahead(panel):
    _Spy.seen = []
    res = backtest(panel, "revenue_expenditure", test_origins=range(2014, 2022), models=[_Spy(), Drift3y()])
    assert _Spy.seen == [T - 1 for T in range(2014, 2022)]
    assert res.groupby("model").size().nunique() == 1   # all models scored on the same rows


def test_min_training_history_enforced(panel):
    sup = build_supervised(panel, "revenue_expenditure", include_unlabelled=False)
    m = SklearnGrowth("r", "ridge", CORE_FEATURES + ["gsdp_g1"])
    with pytest.raises(ValueError):
        m.fit(sup[sup.origin <= 2013])   # GSDP growth exists only from 2012-13: two origins


def test_ridge_beats_naive_on_revenue_expenditure(panel):
    res = backtest(panel, "revenue_expenditure",
                   models=[NaiveLast(), Drift3y(), SklearnGrowth("ridge_core", "ridge", CORE_FEATURES)])
    s = summarise(res).set_index("model")
    assert s.loc["ridge_core", "mape"] < s.loc["naive_last", "mape"]


def _res(rows):
    return pd.DataFrame(rows, columns=["model", "horizon", "actual", "predicted"])


def test_selection_requires_beating_best_baseline():
    rows = [("naive_last", 1, 100, 90), ("drift_3y", 1, 100, 95), ("ridge_core", 1, 100, 97)]
    assert select_model(_res(rows))[0] == "ridge_core"
    rows = [("naive_last", 1, 100, 99), ("drift_3y", 1, 100, 95), ("ridge_core", 1, 100, 97)]
    assert select_model(_res(rows))[0] == "naive_last"


def test_interval_coverage_counts():
    res = _res([("m", 1, 100, 100), ("m", 1, 130, 100)])
    cov = interval_coverage(res, "m", {1: (np.log(0.9), np.log(1.1))})
    assert cov["h1"] == {"n": 2, "coverage": 0.5}


def test_trained_model_metadata(trained_registry):
    _, meta, res = trained_registry
    assert meta["version"] == 1 and meta["model_type"] in res.model.unique()
    assert set(meta["interval_quantiles"]) == {"h1", "h2", "h3"}
    lo, hi = meta["interval_quantiles"]["h1"]
    assert lo < 0 < hi
    assert meta["train_year_to"] == "2022-23"


def test_live_forecast(panel, trained_registry):
    root, meta, _ = trained_registry
    out = forecast(panel, "KA", "revenue_expenditure", 3, root=root)
    assert out["origin_fiscal_year"] == "2022-23"          # last Actual, not RE/BE
    assert [p["fiscal_year"] for p in out["predictions"]] == ["2023-24", "2024-25", "2025-26"]
    assert all(p["lower"] < p["value"] < p["upper"] for p in out["predictions"])
    assert {g["data_status"] for g in out["government_estimates"]} == {"RE", "BE"}
    assert all(h["data_status"] in ("ACCOUNTS", "ACTUAL") for h in out["history"])


@pytest.mark.parametrize("kwargs,err", [
    ({"state": "XX", "target": "revenue_expenditure"}, NotFound),
    ({"state": "KA", "target": "gdp"}, NotFound),
    ({"state": "KA", "target": "revenue_expenditure", "horizon": 5}, InvalidInput),
    ({"state": "TG", "target": "revenue_expenditure", "origin_fiscal_year": "2015-16"}, InsufficientHistory),
])
def test_forecast_errors(panel, trained_registry, kwargs, err):
    with pytest.raises(err):
        forecast(panel, root=trained_registry[0], **{"horizon": 3, **kwargs})
