"""Budget baseline, impact models, scenarios, optimisation and stress testing on real state data."""
import numpy as np
import pytest

from budget_ml.budget import FRBM_GFD_LIMIT_PCT, SECTORS, baseline, fiscal_position, historical_shares
from budget_ml.errors import Infeasible, InvalidInput, NotFound
from budget_ml.impact import SPECS, estimate, estimation_sample, predict_outcome
from budget_ml.optimize import optimize_allocation
from budget_ml.scenario import PRESETS, run_scenario
from budget_ml.stress import run_stress_test, tax_buoyancy


# ---- budget baseline ---------------------------------------------------------------------------------------

def test_sector_split_is_exact(panel):
    b = baseline(panel, "KA", "2022-23")
    assert b.discretionary == pytest.approx(b.revenue_expenditure + b.capital_outlay - b.committed)
    assert b.data_status == "ACTUAL"


def test_latest_year_is_budget_estimate(panel):
    b = baseline(panel, "KA")
    assert b.fiscal_year == "2024-25" and b.data_status == "BE"
    assert b.health_estimated and b.health_share_source_year == "2019-20"


def test_published_health_used_when_available(panel):
    b = baseline(panel, "KA", "2018-19")
    assert not b.health_estimated
    assert b.health == panel[(panel.state_code == "KA") & (panel.fy_start == 2018)].health_expenditure.iloc[0]


def test_deficit_moves_one_for_one(panel):
    b = baseline(panel, "KA")
    s = b.sectors()
    s["capital_outlay"] += 1000
    fp = fiscal_position(b, s)
    assert fp["gross_fiscal_deficit"] == pytest.approx(b.gross_fiscal_deficit + 1000)
    assert fp["revenue_deficit"] == pytest.approx(b.revenue_deficit)     # capital spending, not revenue


def test_unknown_state(panel):
    with pytest.raises(NotFound):
        baseline(panel, "XX")


# ---- impact models -----------------------------------------------------------------------------------------

def test_impact_sample_excludes_uts_and_breaks(panel, categories):
    d = estimation_sample(panel, SPECS[0], categories)
    assert set(d.state_code.map(categories)) <= {"STATE", "UT_LEGISLATURE"}
    assert not ((d.state_code == "AP") & (d.fy_start == 2014)).any()


def test_impact_estimate_reproducible(panel, categories):
    a, b = estimate(panel, SPECS[0], categories, seed=1), estimate(panel, SPECS[0], categories, seed=1)
    assert a.elasticity == b.elasticity and a.ci_low == b.ci_low
    assert a.ci_low <= a.elasticity <= a.ci_high


def test_current_data_supports_no_impact_relationship(impact_results):
    """Documented finding (MODEL_EVALUATION.md): all intervals include zero."""
    assert impact_results and not any(r["supported"] for r in impact_results)


def test_unsupported_relationship_predicts_no_change():
    r = {"supported": False, "elasticity": -0.5, "ci_low": -0.9, "ci_high": 0.1}
    assert predict_outcome(r, 30.0, 100.0, 120.0)["lower"] is None


def test_supported_relationship_prediction():
    r = {"supported": True, "elasticity": -0.5, "ci_low": -0.8, "ci_high": -0.2}
    p = predict_outcome(r, 30.0, 100.0, 121.0)
    assert p["predicted"] == pytest.approx(30 * 1.21 ** -0.5) and p["lower"] < p["predicted"] < p["upper"]


# ---- scenarios ---------------------------------------------------------------------------------------------

@pytest.mark.parametrize("preset", PRESETS)
def test_presets_keep_total_constant(panel, impact_results, preset):
    s = run_scenario(panel, "KA", None, preset, impact_results)
    assert sum(x["scenario"] for x in s["sectors"]) == pytest.approx(sum(x["baseline"] for x in s["sectors"]))
    assert s["fiscal_effects"]["scenario"]["gross_fiscal_deficit"] == pytest.approx(
        s["fiscal_effects"]["baseline"]["gross_fiscal_deficit"])


def test_health_priority_amount(panel, impact_results):
    b = baseline(panel, "KA")
    s = run_scenario(panel, "KA", None, "HEALTH_PRIORITY", impact_results)
    social = next(x for x in s["sectors"] if x["sector"] == "social_sector")
    assert social["change"] == pytest.approx(0.10 * b.health)


def test_custom_increase_raises_deficit(panel, impact_results):
    b = baseline(panel, "KA")
    s = run_scenario(panel, "KA", None, "CUSTOM", impact_results, adjustments_pct={"capital_outlay": 20})
    assert s["fiscal_effects"]["scenario"]["gross_fiscal_deficit"] == pytest.approx(
        b.gross_fiscal_deficit + 0.2 * b.capital_outlay)
    assert s["fiscal_effects"]["scenario"]["breaches_frbm_gfd_limit"]


def test_unsupported_indicator_effects_have_no_prediction(panel, impact_results):
    s = run_scenario(panel, "KA", None, "SOCIAL_PRIORITY", impact_results)
    assert s["indicator_effects"] and all(e["predicted_value"] is None for e in s["indicator_effects"])


@pytest.mark.parametrize("kwargs", [
    {"adjustments_pct": {"defence": 5}},
    {"adjustments_pct": {"social_sector": -150}},
    {"allocations": {"capital_outlay": -1}},
    {},
    {"allocations": {"capital_outlay": 1}, "adjustments_pct": {"capital_outlay": 1}},
])
def test_invalid_custom_scenarios(panel, impact_results, kwargs):
    with pytest.raises(InvalidInput):
        run_scenario(panel, "KA", None, "CUSTOM", impact_results, **kwargs)


# ---- optimisation ------------------------------------------------------------------------------------------

def test_min_adjustment_keeps_feasible_current_budget(panel, impact_results):
    o = optimize_allocation(panel, "KA", None, "MIN_ADJUSTMENT", impact_results)
    for a in o["allocation"]:
        assert a["recommended"] == pytest.approx(a["current"], rel=1e-6)


def test_deficit_limit_enforced(panel, impact_results):
    b = baseline(panel, "PB")
    assert b.as_dict()["gfd_pct_gsdp"] > FRBM_GFD_LIMIT_PCT       # Punjab 2024-25 BE is above 3%
    o = optimize_allocation(panel, "PB", None, "MIN_ADJUSTMENT", impact_results)
    assert o["fiscal_effects"]["recommended"]["gfd_pct_gsdp"] == pytest.approx(FRBM_GFD_LIMIT_PCT, abs=1e-6)
    assert sum(a["recommended"] for a in o["allocation"]) == pytest.approx(o["total_budget"])


def test_bounds_respected(panel, impact_results):
    o = optimize_allocation(panel, "KA", None, "MAX_CAPITAL_OUTLAY", impact_results, total_budget=275000)
    for a in o["allocation"]:
        assert a["min"] - 1e-3 <= a["recommended"] <= a["max"] + 1e-3
    cap = next(a for a in o["allocation"] if a["sector"] == "capital_outlay")
    assert cap["recommended"] >= cap["current"] - 1e-6


def test_infeasible_floor_detected(panel, impact_results):
    with pytest.raises(Infeasible):
        optimize_allocation(panel, "UP", None, "MIN_ADJUSTMENT", impact_results, social_floor="MEDIAN")


def test_outcome_objective_refused_without_supported_effect(panel, impact_results):
    with pytest.raises(InvalidInput):
        optimize_allocation(panel, "KA", None, "OUTCOME", impact_results)


def test_outcome_objective_moves_toward_supported_sector(panel, impact_results):
    """Solver check with an assumed elasticity (not a data claim): spending shifts to the sector with effect."""
    real = next(r for r in impact_results if r["key"] == "capital_roads")
    assumed = [{**real, "supported": True, "elasticity": 0.3, "ci_low": 0.1, "ci_high": 0.5}]
    o = optimize_allocation(panel, "KA", None, "OUTCOME", assumed)
    cap = next(a for a in o["allocation"] if a["sector"] == "capital_outlay")
    assert cap["recommended"] == pytest.approx(cap["max"], rel=1e-4)


def test_invalid_bounds(panel, impact_results):
    with pytest.raises(InvalidInput):
        optimize_allocation(panel, "KA", None, "MIN_ADJUSTMENT", impact_results,
                            bounds={"capital_outlay": {"min": 10, "max": 5}})


def test_historical_shares_sum_reasonably(panel):
    sh = historical_shares(panel, "KA")
    assert set(sh) == set(SECTORS)
    assert sum(v["mean"] for v in sh.values()) == pytest.approx(1.0)


def test_historical_shares_skip_invalid_split_years(panel, impact_results):
    """PB 2016-17 publishes components larger than revenue expenditure; it must not produce negative bounds."""
    sh = historical_shares(panel, "PB")
    assert all(v["min"] >= 0 for v in sh.values())
    o = optimize_allocation(panel, "PB", None, "MIN_ADJUSTMENT", impact_results)
    assert all(a["min"] >= 0 for a in o["allocation"])


# ---- stress testing ----------------------------------------------------------------------------------------

@pytest.fixture(scope="module")
def ka_stress(panel):
    return run_stress_test(panel, "KA", None)


def _val(res, scenario, metric, key="stressed"):
    return next(r[key] for r in res["results"] if r["scenario"] == scenario and r["metric"] == metric)


def test_stress_calibration_from_data(ka_stress):
    c = ka_stress["calibration"]
    assert c["revenue_shock_pct"] < 0 and "2020-21" in c["revenue_shock_basis"]
    assert c["gsdp_shortfall_pct"] < 0 and c["inflation_pct"] > c["baseline_inflation_pct"]
    assert 0 < c["tax_buoyancy"]["ci_low"] < c["tax_buoyancy"]["buoyancy"] < c["tax_buoyancy"]["ci_high"]


def test_normal_case_equals_baseline(panel, ka_stress):
    b = baseline(panel, "KA")
    assert _val(ka_stress, "REVENUE_SHOCK", "gross_fiscal_deficit", "normal") == pytest.approx(b.gross_fiscal_deficit)


def test_stress_worsens_position(ka_stress):
    normal = _val(ka_stress, "COMBINED", "gfd_pct_gsdp", "normal")
    for sc in ("REVENUE_SHOCK", "DOWNTURN", "COMBINED"):
        assert _val(ka_stress, sc, "gfd_pct_gsdp") > normal
    assert _val(ka_stress, "COMBINED", "gfd_pct_gsdp") >= _val(ka_stress, "DOWNTURN", "gfd_pct_gsdp")
    assert _val(ka_stress, "INFLATION", "real_per_capita_social_sector") < _val(
        ka_stress, "INFLATION", "real_per_capita_social_sector", "normal")


def test_stress_override_and_validation(panel):
    r = run_stress_test(panel, "KA", None, overrides={"revenue_shock_pct": -10}, scenarios=["REVENUE_SHOCK"])
    b = baseline(panel, "KA")
    assert _val(r, "REVENUE_SHOCK", "own_tax_revenue") == pytest.approx(0.9 * b.own_tax_revenue)
    with pytest.raises(InvalidInput):
        run_stress_test(panel, "KA", None, scenarios=["ALIENS"])


def test_tax_buoyancy_reasonable(panel):
    t = tax_buoyancy(panel, n_boot=50)
    assert 0.3 < t["buoyancy"] < 1.5 and t["n_obs"] > 200
    assert np.isfinite(t["ci_low"])
