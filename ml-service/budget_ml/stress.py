"""Stress testing of a budget allocation against data-calibrated adverse conditions.

Shock calibration (defaults; each can be overridden by the caller):
  REVENUE_SHOCK  own tax revenue falls by the state's worst observed year-on-year decline in actual years
                 (2005-06..2022-23, structural breaks excluded); if it never declined, the panel 5th percentile.
  INFLATION      CPI inflation at the 95th percentile of state CPI 2014-15..2024-25 (all states); reduces the real
                 value of spending: real factor = (1 + baseline CPI) / (1 + stressed CPI).
  POPULATION     population growth at the 95th percentile of derived state population growth; the excess over the
                 state's own mean growth raises population and lowers per-capita spending.
  DOWNTURN       real GSDP growth at the state's worst observed year (T22) instead of its mean; GSDP falls by
                 exp(g_worst - g_mean) - 1, and own tax revenue by tax buoyancy x that fall.
  COMBINED       DOWNTURN + INFLATION + POPULATION, with the own-tax-revenue loss equal to the larger of the
                 REVENUE_SHOCK and the DOWNTURN-induced loss (the two are not added, to avoid double counting).
Spending is held at the tested allocation; deficits follow budget.fiscal_position identities.
Tax buoyancy: pooled OLS slope of d ln(own tax revenue) on d ln(nominal GSDP), actual years, state-clustered
bootstrap 90% interval.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .budget import FRBM_GFD_LIMIT_PCT, SECTORS, Baseline, baseline
from .errors import InvalidInput
from .preprocessing import ACTUAL_STATUSES, STRUCTURAL_BREAKS
from .years import fy_label

SCENARIOS = ("REVENUE_SHOCK", "INFLATION", "POPULATION", "DOWNTURN", "COMBINED")


def _growth(panel: pd.DataFrame, col: str, actual_only: bool = True) -> pd.DataFrame:
    p = panel[panel.fiscal_status.isin(ACTUAL_STATUSES)] if actual_only else panel
    p = p[["state_code", "fy_start", col]].dropna().sort_values(["state_code", "fy_start"])
    p = p[p[col] > 0]
    prev = p.groupby("state_code")[col].shift()
    prev_year = p.groupby("state_code")["fy_start"].shift()
    g = p.assign(g=np.log(p[col] / prev), consecutive=p.fy_start - prev_year == 1)
    brk = [y in STRUCTURAL_BREAKS.get(s, ()) for s, y in zip(g.state_code, g.fy_start)]
    return g[g.consecutive & ~pd.Series(brk, index=g.index)].dropna(subset=["g"])


def tax_buoyancy(panel: pd.DataFrame, n_boot: int = 499, seed: int = 0) -> dict:
    tax = _growth(panel, "own_tax_revenue")[["state_code", "fy_start", "g"]].rename(columns={"g": "g_tax"})
    gdp = _growth(panel, "gsdp_current", actual_only=False)[["state_code", "fy_start", "g"]].rename(columns={"g": "g_gdp"})
    d = tax.merge(gdp, on=["state_code", "fy_start"])

    def slope(df):
        return float(np.polyfit(df["g_gdp"].to_numpy(), df["g_tax"].to_numpy(), 1)[0])

    b = slope(d)
    rng = np.random.default_rng(seed)
    states = d.state_code.unique()
    groups = {s: g for s, g in d.groupby("state_code")}
    boots = [slope(pd.concat([groups[s] for s in rng.choice(states, len(states))])) for _ in range(n_boot)]
    lo, hi = np.quantile(boots, [0.05, 0.95])
    return {"buoyancy": b, "ci_low": float(lo), "ci_high": float(hi), "n_obs": int(len(d)),
            "years": f"{fy_label(int(d.fy_start.min()))}..{fy_label(int(d.fy_start.max()))}"}


_BUOYANCY_CACHE: dict[int, tuple[pd.DataFrame, dict]] = {}


def _cached_buoyancy(panel: pd.DataFrame) -> dict:
    """Bootstrap is slow; cache per panel object (the service holds one panel per dataset version)."""
    hit = _BUOYANCY_CACHE.get(id(panel))
    if hit is None or hit[0] is not panel:
        hit = (panel, tax_buoyancy(panel))
        _BUOYANCY_CACHE[id(panel)] = hit
    return hit[1]


def calibrate(panel: pd.DataFrame, state: str, b: Baseline) -> dict:
    tax_g = _growth(panel, "own_tax_revenue")
    own = tax_g[tax_g.state_code == state]
    worst = own.loc[own.g.idxmin()] if not own.empty else None
    if worst is not None and worst.g < 0:
        rev_shock = float(np.exp(worst.g) - 1)
        rev_basis = f"worst observed fall in {state} own tax revenue ({fy_label(int(worst.fy_start))})"
    else:
        rev_shock = float(np.exp(tax_g.g.quantile(0.05)) - 1)
        rev_basis = "panel 5th percentile of own tax revenue growth (state never recorded a fall)"

    cpi = panel["cpi_inflation"].dropna()
    infl = float(cpi.quantile(0.95))

    pop_g = _growth(panel, "population", actual_only=False)
    own_pop = pop_g[pop_g.state_code == state].g
    pop_p95 = float(pop_g.g.quantile(0.95))
    pop_excess = max(0.0, pop_p95 - (float(own_pop.mean()) if not own_pop.empty else float(pop_g.g.mean())))

    real = _growth(panel, "gsdp_constant", actual_only=False)
    own_real = real[real.state_code == state]
    if own_real.empty:
        raise InvalidInput(f"No real GSDP history for {state}")
    w = own_real.loc[own_real.g.idxmin()]
    gsdp_fall = float(np.exp(w.g - own_real.g.mean()) - 1)
    buoy = _cached_buoyancy(panel)
    return {
        "revenue_shock_pct": 100 * rev_shock, "revenue_shock_basis": rev_basis,
        "inflation_pct": infl, "inflation_basis": "95th percentile of state CPI inflation, 2014-15..2024-25",
        "baseline_inflation_pct": b.cpi_inflation,
        "population_excess_growth_pct": 100 * pop_excess,
        "population_basis": "95th percentile of state population growth minus the state's own mean growth",
        "gsdp_shortfall_pct": 100 * gsdp_fall,
        "gsdp_basis": f"{state} worst real GSDP growth ({fy_label(int(w.fy_start))}: {100*(np.exp(w.g)-1):.1f}%) vs its mean ({100*(np.exp(own_real.g.mean())-1):.1f}%)",
        "tax_buoyancy": buoy,
    }


def _metrics(b: Baseline, alloc: dict[str, float], otr_change: float, gsdp_factor: float,
             real_factor: float, pop_factor: float) -> dict:
    d_spend = sum(alloc[k] - getattr(b, k) for k in SECTORS)
    d_rev_spend = (alloc["social_sector"] - b.social_sector) + (alloc["other_revenue"] - b.other_revenue)
    rr = b.revenue_receipts + otr_change
    rd = b.revenue_deficit + d_rev_spend - otr_change
    gfd = b.gross_fiscal_deficit + d_spend - otr_change
    gsdp = b.gsdp * gsdp_factor if b.gsdp else None
    pop = b.population * pop_factor if b.population else None
    gfd_pct = 100 * gfd / gsdp if gsdp else None
    limit = FRBM_GFD_LIMIT_PCT / 100 * gsdp if gsdp else None
    return {
        "own_tax_revenue": b.own_tax_revenue + otr_change,
        "revenue_receipts": rr,
        "revenue_deficit": rd,
        "gross_fiscal_deficit": gfd,
        "gsdp": gsdp,
        "gfd_pct_gsdp": gfd_pct,
        "interest_pct_revenue_receipts": 100 * b.interest_payments / rr if rr > 0 else None,
        "real_per_capita_social_sector": alloc["social_sector"] * 1e7 / pop * real_factor if pop else None,
        "real_per_capita_capital_outlay": alloc["capital_outlay"] * 1e7 / pop * real_factor if pop else None,
        "adjustment_needed_for_frbm": max(0.0, gfd - limit) if limit is not None else None,
    }


def run_stress_test(panel: pd.DataFrame, state: str, fiscal_year: str | None, allocation: dict | None = None,
                    overrides: dict | None = None, scenarios: list[str] | None = None) -> dict:
    b = baseline(panel, state, fiscal_year)
    alloc = b.sectors()
    if allocation:
        unknown = set(allocation) - set(SECTORS)
        if unknown:
            raise InvalidInput(f"Unknown sectors {sorted(unknown)}")
        if any(v is None or v < 0 for v in allocation.values()):
            raise InvalidInput("Allocations must be non-negative")
        alloc.update({k: float(v) for k, v in allocation.items()})
    scenarios = scenarios or list(SCENARIOS)
    bad = set(scenarios) - set(SCENARIOS)
    if bad:
        raise InvalidInput(f"Unknown stress scenarios {sorted(bad)}", [f"valid: {list(SCENARIOS)}"])

    cal = calibrate(panel, state, b)
    for k, v in (overrides or {}).items():
        if v is None:
            continue
        if k not in ("revenue_shock_pct", "inflation_pct", "population_excess_growth_pct", "gsdp_shortfall_pct"):
            raise InvalidInput(f"Unknown shock override '{k}'")
        cal[k] = float(v)
        cal[k.replace("_pct", "_basis").replace("_excess_growth", "")] = "user-specified"
    base_infl = (b.cpi_inflation if b.cpi_inflation is not None else float(panel.cpi_inflation.median())) / 100
    rev = cal["revenue_shock_pct"] / 100
    real_f = (1 + base_infl) / (1 + cal["inflation_pct"] / 100)
    pop_f = 1 + cal["population_excess_growth_pct"] / 100
    gsdp_f = 1 + cal["gsdp_shortfall_pct"] / 100
    buoy = cal["tax_buoyancy"]["buoyancy"]
    downturn_otr = b.own_tax_revenue * buoy * (gsdp_f - 1)

    normal = _metrics(b, alloc, 0.0, 1.0, 1.0, 1.0)
    cases = {
        "REVENUE_SHOCK": _metrics(b, alloc, b.own_tax_revenue * rev, 1.0, 1.0, 1.0),
        "INFLATION": _metrics(b, alloc, 0.0, 1.0, real_f, 1.0),
        "POPULATION": _metrics(b, alloc, 0.0, 1.0, 1.0, pop_f),
        "DOWNTURN": _metrics(b, alloc, downturn_otr, gsdp_f, 1.0, 1.0),
        "COMBINED": _metrics(b, alloc, min(b.own_tax_revenue * rev, downturn_otr), gsdp_f, real_f, pop_f),
    }
    results = []
    for name in scenarios:
        stressed = cases[name]
        for metric, nv in normal.items():
            sv = stressed[metric]
            breach = metric == "gfd_pct_gsdp" and sv is not None and sv > FRBM_GFD_LIMIT_PCT
            results.append({"scenario": name, "metric": metric, "normal": nv, "stressed": sv,
                            "change_pct": 100 * (sv - nv) / abs(nv) if nv not in (None, 0) and sv is not None else None,
                            "breaches_limit": bool(breach)})
    return {"state_code": state, "fiscal_year": b.fiscal_year, "baseline_data_status": b.data_status,
            "allocation": alloc, "calibration": cal, "results": results,
            "notes": ["Spending is held at the tested allocation; only revenues, GSDP, prices and population are shocked.",
                      "Real per-capita values are in baseline-year rupees."]}
