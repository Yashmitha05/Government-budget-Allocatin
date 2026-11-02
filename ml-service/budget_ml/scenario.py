"""Budget allocation scenarios: alternative sector allocations and their fiscal and indicator effects.

Fiscal effects are exact accounting consequences (budget.fiscal_position). Indicator effects use the
estimated elasticities from impact.py and are reported only where statistically supported; otherwise the
estimate and interval are shown with supported=False and no predicted change.
"""
from __future__ import annotations

import pandas as pd

from .budget import (SECTOR_LABELS, SECTORS, Baseline, baseline, fiscal_position, historical_shares,
                     per_capita, per_capita_benchmarks)
from .errors import InvalidInput
from .impact import predict_outcome
from .years import fy_start

PRESET_SHIFT_PCT = 10.0
PRESETS = ("BALANCED", "HEALTH_PRIORITY", "SOCIAL_PRIORITY", "INFRASTRUCTURE_PRIORITY")


def _rebalance(b: Baseline, increase: str, amount: float) -> dict[str, float]:
    """Add `amount` to one sector and fund it pro rata from the other sectors (total unchanged)."""
    s = b.sectors()
    others = [k for k in SECTORS if k != increase]
    pool = sum(s[k] for k in others)
    if amount > pool:
        raise InvalidInput("Shift larger than the other sectors combined")
    s[increase] += amount
    for k in others:
        s[k] -= amount * s[k] / pool
    return s


def preset_allocation(panel: pd.DataFrame, b: Baseline, preset: str) -> tuple[dict[str, float], str]:
    if preset == "BALANCED":
        shares = historical_shares(panel, b.state_code)
        total = sum(v["mean_last5"] for v in shares.values())
        alloc = {k: b.discretionary * shares[k]["mean_last5"] / total for k in SECTORS}
        return alloc, f"each sector at the state's average share over its last five actual years ({shares['social_sector']['years']})"
    if preset == "HEALTH_PRIORITY":
        if b.health is None:
            raise InvalidInput(f"No health expenditure data for {b.state_code}")
        return (_rebalance(b, "social_sector", b.health * PRESET_SHIFT_PCT / 100),
                f"health +{PRESET_SHIFT_PCT:.0f}% (within social sector), funded pro rata from capital outlay and other revenue expenditure")
    if preset == "SOCIAL_PRIORITY":
        return (_rebalance(b, "social_sector", b.social_sector * PRESET_SHIFT_PCT / 100),
                f"social sector +{PRESET_SHIFT_PCT:.0f}%, funded pro rata from the other sectors")
    if preset == "INFRASTRUCTURE_PRIORITY":
        return (_rebalance(b, "capital_outlay", b.capital_outlay * PRESET_SHIFT_PCT / 100),
                f"capital outlay +{PRESET_SHIFT_PCT:.0f}%, funded pro rata from the other sectors")
    raise InvalidInput(f"Unknown scenario type '{preset}'", [f"valid: {list(PRESETS)} or CUSTOM"])


def custom_allocation(b: Baseline, allocations: dict | None, adjustments_pct: dict | None) -> tuple[dict[str, float], str]:
    if bool(allocations) == bool(adjustments_pct):
        raise InvalidInput("Provide exactly one of 'allocations' or 'adjustments_pct'")
    s = b.sectors()
    given = allocations or adjustments_pct
    unknown = set(given) - set(SECTORS) - {"health"}
    if unknown:
        raise InvalidInput(f"Unknown sectors {sorted(unknown)}", [f"valid: {list(SECTORS) + ['health']}"])
    if allocations:
        for k, v in allocations.items():
            if v is None or v < 0:
                raise InvalidInput(f"Allocation for {k} must be a non-negative amount")
            if k == "health":
                if b.health is None:
                    raise InvalidInput("No health expenditure data for this state")
                s["social_sector"] += v - b.health
            else:
                s[k] = float(v)
        return s, "custom amounts"
    for k, pct in adjustments_pct.items():
        if pct is None or pct < -100:
            raise InvalidInput(f"Adjustment for {k} must be >= -100%")
        if k == "health":
            if b.health is None:
                raise InvalidInput("No health expenditure data for this state")
            s["social_sector"] += b.health * pct / 100
        else:
            s[k] *= 1 + pct / 100
    if s["social_sector"] < 0:
        raise InvalidInput("Resulting social sector allocation is negative")
    return s, "custom % adjustments"


def _latest_outcome(panel: pd.DataFrame, state: str, indicator: str, year: int) -> tuple[float | None, str | None]:
    g = panel[(panel.state_code == state) & (panel.fy_start <= year) & panel[indicator].notna()]
    if g.empty:
        return None, None
    r = g.sort_values("fy_start").iloc[-1]
    return float(r[indicator]), str(r.fiscal_year)


def indicator_effects(panel: pd.DataFrame, b: Baseline, alloc: dict[str, float], impact_results: list[dict]) -> list[dict]:
    pc_base = per_capita(b, b.sectors())
    pc_new = per_capita(b, alloc)
    spend_new = {"social_sector": pc_new["social_sector"], "capital_outlay": pc_new["capital_outlay"]}
    spend_base = {"social_sector": pc_base["social_sector"], "capital_outlay": pc_base["capital_outlay"]}
    if b.health is not None and b.population:
        health_new = b.health + (alloc["social_sector"] - b.social_sector)   # social changes attributed to health
        spend_base["health"] = b.health * 1e7 / b.population
        spend_new["health"] = max(health_new, 0) * 1e7 / b.population
    out = []
    for r in impact_results:
        if r["sector"] not in spend_base or spend_base[r["sector"]] is None:
            continue
        current, as_of = _latest_outcome(panel, b.state_code, r["outcome"], fy_start(b.fiscal_year))
        if current is None:
            continue
        pred = predict_outcome(r, current, spend_base[r["sector"]], spend_new[r["sector"]])
        out.append({
            "indicator": r["outcome"], "indicator_name": r["outcome_name"], "driver_sector": r["sector"],
            "current_value": current, "current_as_of": as_of,
            "predicted_value": pred["predicted"] if r["supported"] else None,
            "lower": pred["lower"], "upper": pred["upper"], "supported": r["supported"],
            "elasticity": r["elasticity"], "elasticity_ci": [r["ci_low"], r["ci_high"]],
            "method_note": (f"two-way fixed-effects panel elasticity, {r['years']}, n={r['n_obs']}; " + r["note"]),
        })
    return out


def run_scenario(panel: pd.DataFrame, state: str, fiscal_year: str | None, scenario_type: str,
                 impact_results: list[dict], allocations: dict | None = None,
                 adjustments_pct: dict | None = None) -> dict:
    b = baseline(panel, state, fiscal_year)
    if scenario_type == "CUSTOM":
        alloc, how = custom_allocation(b, allocations, adjustments_pct)
    else:
        alloc, how = preset_allocation(panel, b, scenario_type)
    base_s = b.sectors()
    pc_b, pc_n = per_capita(b, base_s), per_capita(b, alloc)
    bench = per_capita_benchmarks(panel, fy_start(b.fiscal_year))
    sectors = [{
        "sector": k, "label": SECTOR_LABELS[k], "baseline": base_s[k], "scenario": alloc[k],
        "change": alloc[k] - base_s[k], "change_pct": 100 * (alloc[k] - base_s[k]) / base_s[k] if base_s[k] else None,
        "baseline_share_pct": 100 * base_s[k] / b.discretionary, "scenario_share_pct": 100 * alloc[k] / sum(alloc.values()),
        "per_capita_baseline": pc_b[k], "per_capita_scenario": pc_n[k], "per_capita_all_state_median": bench.get(k),
    } for k in SECTORS]
    return {
        "state_code": state, "fiscal_year": b.fiscal_year, "baseline_data_status": b.data_status,
        "scenario_type": scenario_type, "description": how,
        "baseline": b.as_dict(), "sectors": sectors,
        "fiscal_effects": {"baseline": fiscal_position(b, base_s), "scenario": fiscal_position(b, alloc)},
        "indicator_effects": indicator_effects(panel, b, alloc, impact_results),
        "notes": [
            "Fiscal effects are accounting identities with revenue receipts held at the baseline.",
            "Health amounts after 2019-20 are estimated from the latest published health share of social spending."
            if b.health_estimated else "Health amount is the published figure.",
            "Indicator effects are historical associations and are shown only when statistically supported.",
        ],
    }
