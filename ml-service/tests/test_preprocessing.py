import numpy as np
import pandas as pd
import pytest

from budget_ml.preprocessing import FISCAL_INDICATORS, DataQualityError, deduplicate


def _rec(value, state="KA", ind="pension", fy=2020):
    return {"state_code": state, "indicator": ind, "fy_start": fy, "value": value}


def test_exact_duplicates_collapsed():
    df, n = deduplicate(pd.DataFrame([_rec(1.0), _rec(1.0), _rec(2.0, fy=2021)]))
    assert n == 1 and len(df) == 2


def test_conflicting_duplicates_raise():
    with pytest.raises(DataQualityError):
        deduplicate(pd.DataFrame([_rec(1.0), _rec(2.0)]))


def test_no_duplicate_keys(dataset):
    obs = dataset.observations
    assert not obs.duplicated(["state_code", "indicator", "fy_start"]).any()
    assert dataset.quality["duplicates_collapsed"] == 0


def test_state_sums_match_published_totals(dataset):
    """Most RBI fiscal tables add up to the published 'All States and UTs' row."""
    check = dataset.quality["aggregate_check"]
    exact = [i for i in FISCAL_INDICATORS if check[i]["max_abs_rel_diff"] < 0.005]
    assert len(exact) >= 9
    assert all(check[i]["max_abs_rel_diff"] < 0.05 for i in FISCAL_INDICATORS)


def test_panel_excludes_aggregates_and_has_status(dataset):
    p = dataset.panel
    assert not p.state_code.isin(["ALL", "IN"]).any()
    assert not p.duplicated(["state_code", "fy_start"]).any()
    ka = p[p.state_code == "KA"].set_index("fiscal_year")
    assert ka.loc["2023-24", "fiscal_status"] == "RE" and ka.loc["2021-22", "fiscal_status"] == "ACCOUNTS"


def test_derived_population_is_consistent(dataset):
    p = dataset.panel.dropna(subset=["population"])
    recomputed = p.nsdp_current * 1e7 / p.per_capita_nsdp_current
    assert np.allclose(p.population, recomputed)
    # state sum vs World Bank national population: roughly 0.9-1.0 (small UTs not covered)
    ratios = dataset.quality["population_sum_vs_wdi"]
    assert ratios and all(0.85 < r < 1.02 for r in ratios.values())


def test_outliers_flagged_not_removed(dataset):
    obs = dataset.observations.set_index(["state_code", "indicator", "fiscal_year"])
    for f in dataset.quality["outlier_flags"][:20]:
        assert (f["state_code"], f["indicator"], f["fiscal_year"]) in obs.index


def test_structural_break_marked(dataset):
    p = dataset.panel.set_index(["state_code", "fiscal_year"])
    assert bool(p.loc[("AP", "2014-15"), "structural_break"])
    assert not bool(p.loc[("AP", "2015-16"), "structural_break"])
