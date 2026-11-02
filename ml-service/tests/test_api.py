"""HTTP-level tests of the FastAPI service on the real processed dataset and a copy of the model registry."""
import shutil

import pandas as pd
import pytest
from fastapi.testclient import TestClient

from budget_ml.config import ML_ROOT, MODEL_DIR, PROCESSED_DIR


@pytest.fixture(scope="module")
def client(tmp_path_factory):
    if not (PROCESSED_DIR / "panel.csv").exists() or not any(MODEL_DIR.glob("*/active.json")):
        pytest.skip("processed dataset or trained models not present (run scripts/build_dataset.py, train_models.py)")
    models = tmp_path_factory.mktemp("api") / "models"
    shutil.copytree(MODEL_DIR, models)
    from app.main import create_app
    with TestClient(create_app(PROCESSED_DIR, models), raise_server_exceptions=False) as c:
        c.model_root = models
        yield c


def _err(resp, status, code):
    assert resp.status_code == status, resp.text
    body = resp.json()
    assert body["error"] == code and body["message"] and isinstance(body["details"], list)
    assert "Traceback" not in resp.text
    return body


# ---- reference data ----------------------------------------------------------------------------------------

def test_health(client):
    b = client.get("/health").json()
    assert b["status"] == "ok" and b["panel_rows"] == 829
    assert set(b["active_models"]) == {"revenue_expenditure", "own_tax_revenue", "own_non_tax_revenue",
                                       "capital_expenditure", "social_sector_expenditure"}


def test_states_and_indicators(client):
    states = {s["code"]: s for s in client.get("/states").json()}
    assert states["KA"]["has_budget_data"] and not states["LD"]["has_budget_data"]
    inds = {i["code"]: i for i in client.get("/indicators").json()}
    assert inds["revenue_expenditure"]["is_forecast_target"] and inds["population"]["category"] == "DERIVED"
    assert "ger_secondary" in inds and "india_cpi_inflation" in inds


def test_dataset_metadata(client):
    d = client.get("/dataset").json()
    assert d["label"].startswith("hsis-2024-25") and len(d["content_hash"]) == 64
    assert d["outlier_flag_count"] > 0 and d["structural_breaks"]["AP"] == ["2014-15"]


def test_observations_filter(client):
    d = client.get("/dataset/observations", params={"state_code": "KA", "indicator": "gross_fiscal_deficit"}).json()
    assert d["count"] == 21
    row = next(o for o in d["observations"] if o["fiscal_year"] == "2024-25")
    assert row["value"] == 82981.0 and row["data_status"] == "BE"


def test_baseline_and_impact(client):
    b = client.get("/baseline", params={"state_code": "KA"}).json()
    assert b["fiscal_year"] == "2024-25" and b["health_estimated"]
    imp = client.get("/impact").json()
    assert len(imp) == 4 and not any(i["supported"] for i in imp)
    _err(client.get("/baseline", params={"state_code": "XX"}), 404, "NOT_FOUND")


def test_models(client):
    ms = client.get("/models").json()
    assert len(ms) >= 5 and sum(m["is_active"] for m in ms) == 5
    m = client.get("/models/revenue_expenditure/1").json()
    assert m["version"] == 1 and m["model_type"] == "ridge_macro"
    _err(client.get("/models/revenue_expenditure/99"), 404, "MODEL_NOT_FOUND")
    _err(client.get("/models", params={"target": "gdp"}), 404, "NOT_FOUND")


def test_backtest_rows(client):
    if not (ML_ROOT / "reports" / "backtest_predictions.csv").exists():
        pytest.skip("backtest predictions not present")
    b = client.get("/backtest", params={"target": "revenue_expenditure", "model_type": "ridge_macro", "horizon": 1}).json()
    assert b["count"] > 100 and {r["horizon"] for r in b["rows"]} == {1}
    assert max(r["target_year"] for r in b["rows"]) == "2022-23"     # actuals only, no RE/BE
    _err(client.get("/backtest", params={"target": "revenue_expenditure", "model_type": "nope"}), 404, "NOT_FOUND")


# ---- forecasting -------------------------------------------------------------------------------------------

def test_forecast(client):
    r = client.post("/forecast", json={"state_code": "KA", "target": "revenue_expenditure", "horizon": 3})
    assert r.status_code == 200, r.text
    b = r.json()
    assert b["origin_fiscal_year"] == "2022-23" and len(b["predictions"]) == 3
    assert all(p["lower"] <= p["value"] <= p["upper"] for p in b["predictions"])


@pytest.mark.parametrize("payload,status,code", [
    ({"state_code": "KA", "target": "revenue_expenditure", "horizon": 5}, 422, "VALIDATION_ERROR"),
    ({"state_code": "KA", "target": "gdp"}, 422, "VALIDATION_ERROR"),
    ({"state_code": "ka", "target": "revenue_expenditure"}, 422, "VALIDATION_ERROR"),
    ({"state_code": "XX", "target": "revenue_expenditure"}, 404, "NOT_FOUND"),
    ({"state_code": "TG", "target": "revenue_expenditure", "origin_fiscal_year": "2015-16"}, 409, "INSUFFICIENT_HISTORY"),
])
def test_forecast_errors(client, payload, status, code):
    _err(client.post("/forecast", json=payload), status, code)


def test_malformed_json(client):
    _err(client.post("/forecast", content=b"{not json", headers={"content-type": "application/json"}),
         422, "VALIDATION_ERROR")


# ---- scenario / optimise / stress ----------------------------------------------------------------------------

@pytest.mark.parametrize("stype", ["BALANCED", "HEALTH_PRIORITY", "SOCIAL_PRIORITY", "INFRASTRUCTURE_PRIORITY"])
def test_scenario_presets(client, stype):
    r = client.post("/scenario", json={"state_code": "KA", "scenario_type": stype})
    assert r.status_code == 200, r.text
    b = r.json()
    assert len(b["sectors"]) == 3 and set(b["fiscal_effects"]) == {"baseline", "scenario"}
    assert all(e["predicted_value"] is None for e in b["indicator_effects"])


def test_scenario_custom_and_errors(client):
    r = client.post("/scenario", json={"state_code": "KA", "scenario_type": "CUSTOM",
                                       "adjustments_pct": {"capital_outlay": 20}})
    assert r.json()["fiscal_effects"]["scenario"]["breaches_frbm_gfd_limit"] is True
    _err(client.post("/scenario", json={"state_code": "KA", "scenario_type": "CUSTOM"}), 422, "VALIDATION_ERROR")
    _err(client.post("/scenario", json={"state_code": "KA", "scenario_type": "CUSTOM",
                                        "adjustments_pct": {"defence": 5}}), 422, "VALIDATION_ERROR")
    _err(client.post("/scenario", json={"state_code": "KA", "scenario_type": "CUSTOM",
                                        "adjustments_pct": {"social_sector": -150}}), 400, "INVALID_INPUT")


def test_optimize(client):
    r = client.post("/optimize", json={"state_code": "PB"})
    assert r.status_code == 200, r.text
    b = r.json()
    assert abs(b["fiscal_effects"]["recommended"]["gfd_pct_gsdp"] - 3.0) < 1e-6 and b["binding_constraints"]
    _err(client.post("/optimize", json={"state_code": "UP", "social_floor": "MEDIAN"}), 422, "INFEASIBLE_CONSTRAINTS")
    _err(client.post("/optimize", json={"state_code": "KA", "objective": "OUTCOME"}), 400, "INVALID_INPUT")
    _err(client.post("/optimize", json={"state_code": "KA", "total_budget": -5}), 422, "VALIDATION_ERROR")
    _err(client.post("/optimize", json={"state_code": "KA", "bounds": {"capital_outlay": {"min_share": 2}}}),
         422, "VALIDATION_ERROR")


def test_stress_test(client):
    r = client.post("/stress-test", json={"state_code": "KA", "scenarios": ["DOWNTURN", "COMBINED"]})
    assert r.status_code == 200, r.text
    b = r.json()
    assert {x["scenario"] for x in b["results"]} == {"DOWNTURN", "COMBINED"}
    assert b["calibration"]["tax_buoyancy"]["buoyancy"] > 0
    r = client.post("/stress-test", json={"state_code": "KA", "overrides": {"revenue_shock_pct": -10},
                                          "scenarios": ["REVENUE_SHOCK"]})
    assert r.json()["calibration"]["revenue_shock_pct"] == -10
    _err(client.post("/stress-test", json={"state_code": "KA", "scenarios": ["ALIENS"]}), 422, "VALIDATION_ERROR")


# ---- feedback loop -----------------------------------------------------------------------------------------

def test_evaluate(client):
    r = client.post("/evaluate", json={"records": [
        {"prediction_id": 1, "state_code": "KA", "predicted": 110, "actual": 100},
        {"prediction_id": 2, "state_code": "UP", "predicted": 95, "actual": 100}], "group_by": ["state_code"]})
    b = r.json()
    assert b["records"][0]["percentage_error"] == pytest.approx(10.0)
    assert {g["state_code"] for g in b["groups"]} == {"KA", "UP"}
    _err(client.post("/evaluate", json={"records": [{"predicted": 0, "actual": 1}]}), 422, "VALIDATION_ERROR")


def test_calibrate_creates_version(client):
    path = ML_ROOT / "reports" / "backtest_predictions.csv"
    if not path.exists():
        pytest.skip("backtest predictions not present")
    res = pd.read_csv(path)
    m = client.get("/models", params={"target": "revenue_expenditure"}).json()
    active = next(x for x in m if x["is_active"])
    r = res[(res.target == "revenue_expenditure") & (res.model == active["model_type"]) & (res.horizon == 1)]
    years = r.target_year.str.slice(0, 4).astype(int)
    rec = lambda d: d[["state_code", "target_year", "predicted", "actual"]].to_dict(orient="records")  # noqa: E731
    out = client.post("/calibrate", json={"target": "revenue_expenditure", "calibration_records": rec(r[years <= 2020]),
                                          "evaluation_records": rec(r[years >= 2021])})
    assert out.status_code == 200, out.text
    b = out.json()
    assert b["version"] == max(x["version"] for x in m) + 1 and b["calibration_evaluation"]["n"] == 60
    bad = client.post("/calibrate", json={"target": "revenue_expenditure", "calibration_records": rec(r[years >= 2021]),
                                          "evaluation_records": rec(r[years >= 2021])})
    _err(bad, 400, "INVALID_INPUT")


def test_retrain(client):
    before = client.get("/models", params={"target": "social_sector_expenditure"}).json()
    r = client.post("/retrain", json={"target": "social_sector_expenditure"})
    assert r.status_code == 200, r.text
    b = r.json()
    assert b["version"] == max(x["version"] for x in before) + 1
    assert b["comparison"]["parent_version"] == next(x["version"] for x in before if x["is_active"])


def test_unexpected_error_is_generic(client, monkeypatch):
    import app.main as main

    def boom(*a, **k):
        raise RuntimeError("secret internal detail")
    monkeypatch.setattr(main, "run_scenario", boom)
    body = _err(client.post("/scenario", json={"state_code": "KA", "scenario_type": "BALANCED"}), 500, "INTERNAL_ERROR")
    assert "secret" not in body["message"]
