import numpy as np
import pandas as pd
import pytest

from budget_ml.errors import InvalidInput
from budget_ml.feedback import (apply_bias, calibrate_version, deviations, estimate_calibration, evaluate,
                                retrain)
from budget_ml.registry import active_version, list_versions, load


def test_deviation_math():
    d = deviations([{"predicted": 110.0, "actual": 100.0}])
    assert d.absolute_error[0] == 10 and d.percentage_error[0] == pytest.approx(10.0)
    assert d.log_error[0] == pytest.approx(np.log(1.1))


def test_deviation_rejects_non_positive():
    with pytest.raises(InvalidInput):
        deviations([{"predicted": 0.0, "actual": 100.0}])


def test_evaluate_groups():
    out = evaluate([{"predicted": 110, "actual": 100, "state_code": "KA"},
                    {"predicted": 90, "actual": 100, "state_code": "UP"}], group_by=["state_code"])
    assert {g["state_code"]: round(g["bias_pct"], 6) for g in out["groups"]} == {"KA": 10.0, "UP": -10.0}


def test_calibration_shrinkage_formula():
    devs = deviations([{"state_code": "KA", "target_year": f"201{i}-1{i+1}", "predicted": 110, "actual": 100}
                       for i in range(2)] +
                      [{"state_code": "UP", "target_year": "2015-16", "predicted": 100, "actual": 100}])
    cal = estimate_calibration(devs, shrink_m=3)
    med_panel = np.log(1.1)              # median of [ln1.1, ln1.1, 0]
    assert cal["panel_bias"] == pytest.approx(med_panel)
    assert cal["state_bias"]["KA"] == pytest.approx(2 / 5 * np.log(1.1) + 3 / 5 * med_panel)
    assert cal["state_bias"]["UP"] == pytest.approx(3 / 4 * med_panel)
    assert apply_bias(devs.iloc[:1], cal)[0] == pytest.approx(110 * np.exp(-cal["state_bias"]["KA"]))


def _records(res, model, years):
    r = res[(res.model == model) & (res.horizon == 1)]
    return r[r.target_year.isin(years)]


def test_calibrated_version_on_real_deviations(trained_registry):
    root, meta, res = trained_registry
    model = meta["model_type"]
    cal_years = [f"{y}-{(y + 1) % 100:02d}" for y in range(2015, 2021)]
    out = calibrate_version("revenue_expenditure", _records(res, model, cal_years),
                            _records(res, model, ["2021-22", "2022-23"]), root=root)
    assert out["version"] == meta["version"] + 1 and out["parent_version"] == meta["version"]
    ev = out["calibration_evaluation"]
    assert ev["improved"] == (ev["mape_after"] < ev["mape_before"])
    assert (active_version("revenue_expenditure", root) == out["version"]) == ev["improved"]
    artefact, _ = load("revenue_expenditure", out["version"], root)
    assert artefact["calibration"]["n_records"] > 0
    assert len(list_versions("revenue_expenditure", root)) == 2


def test_calibration_rejects_overlapping_years(trained_registry):
    root, meta, res = trained_registry
    r = _records(res, meta["model_type"], ["2019-20", "2020-21"])
    with pytest.raises(InvalidInput):
        calibrate_version("revenue_expenditure", r, r, root=root)


def test_retrain_creates_child_version(dataset, tmp_path):
    from budget_ml.forecasting.train import train_target
    train_target(dataset.panel, "social_sector_expenditure", dataset.metadata, root=tmp_path)
    meta = retrain(dataset.panel, "social_sector_expenditure", dataset.metadata, root=tmp_path)
    assert meta["version"] == 2 and meta["parent_version"] == 1
    assert meta["comparison"]["new_dataset"] is False
    # same data and protocol: identical hold-out error, so the new version is (re)activated
    assert meta["comparison"]["new_mape"] == pytest.approx(meta["comparison"]["parent_mape"])
    assert active_version("social_sector_expenditure", tmp_path) == 2
