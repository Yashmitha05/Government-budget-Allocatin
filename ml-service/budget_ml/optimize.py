"""Constrained allocation of the discretionary budget across sectors.

Decision variables: x_k, k in {social_sector, capital_outlay, other_revenue} (₹ crore).
Constraints:
  sum x_k = D                       D = requested total, else the current discretionary total;
                                    with enforce_deficit_limit, D <= D0 + (3% x GSDP - GFD0)  (FRBM)
  min_k <= x_k <= max_k             default: share range spanned by the state's actual years (2011-12..2022-23)
                                    and its current budget, x D; user may override per sector (shares or amounts)
  x_social >= floor x population    optional per-capita floor: CURRENT (no per-capita cut) or
                                    MEDIAN (the all-state median per-capita social spending that year)
Objectives:
  MIN_ADJUSTMENT      minimise sum_k ((x_k - x0_k) / x0_k)^2 : the smallest proportional reallocation that
                      satisfies the constraints (unique; strictly convex).
  MAX_CAPITAL_OUTLAY  maximise x_capital subject to the constraints (linear programme); any remainder goes to
                      the other sectors in proportion to their current amounts.
  OUTCOME             maximise sum_k w_k * s_k * b_k * ln(x_k / x0_k) using impact elasticities b_k (s_k = +1 if
                      higher indicator is better, else -1). Only available when at least one elasticity is
                      statistically supported (impact.py); refused otherwise.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.optimize import linprog, minimize

from .budget import (FRBM_GFD_LIMIT_PCT, SECTOR_LABELS, SECTORS, baseline, fiscal_position, historical_shares,
                     per_capita, per_capita_benchmarks)
from .errors import Infeasible, InvalidInput
from .scenario import indicator_effects
from .years import fy_start

OBJECTIVES = ("MIN_ADJUSTMENT", "MAX_CAPITAL_OUTLAY", "OUTCOME")
FLOORS = ("NONE", "CURRENT", "MEDIAN")
TOL = 1e-6


def _bounds(panel, b, D: float, overrides: dict | None) -> dict[str, list[float]]:
    shares = historical_shares(panel, b.state_code)
    current = {k: v / b.discretionary for k, v in b.sectors().items()}
    # default range spans the state's own history and its current budget, so the current split is feasible
    bounds = {k: [min(shares[k]["min"], current[k]) * D, max(shares[k]["max"], current[k]) * D] for k in SECTORS}
    for k, ov in (overrides or {}).items():
        if k not in SECTORS:
            raise InvalidInput(f"Unknown sector '{k}' in bounds")
        for side, idx in (("min", 0), ("max", 1)):
            if ov.get(f"{side}_share") is not None:
                bounds[k][idx] = float(ov[f"{side}_share"]) * D
            if ov.get(side) is not None:
                bounds[k][idx] = float(ov[side])
        if bounds[k][0] < 0 or bounds[k][0] > bounds[k][1]:
            raise InvalidInput(f"Invalid bounds for {k}: min must be >= 0 and <= max")
    return bounds


def optimize_allocation(panel: pd.DataFrame, state: str, fiscal_year: str | None, objective: str,
                        impact_results: list[dict], total_budget: float | None = None,
                        bounds: dict | None = None, social_floor: str = "NONE",
                        enforce_deficit_limit: bool = True, weights: dict | None = None) -> dict:
    if objective not in OBJECTIVES:
        raise InvalidInput(f"Unknown objective '{objective}'", [f"valid: {list(OBJECTIVES)}"])
    if social_floor not in FLOORS:
        raise InvalidInput(f"Unknown social_floor '{social_floor}'", [f"valid: {list(FLOORS)}"])
    if total_budget is not None and total_budget <= 0:
        raise InvalidInput("total_budget must be positive")
    b = baseline(panel, state, fiscal_year)
    x0 = b.sectors()
    D0 = b.discretionary
    binding: list[str] = []

    D = float(total_budget) if total_budget is not None else D0
    deficit_room = None
    if enforce_deficit_limit:
        if not b.gsdp:
            raise InvalidInput("GSDP unavailable for this year, cannot apply the deficit limit")
        deficit_room = FRBM_GFD_LIMIT_PCT / 100 * b.gsdp - b.gross_fiscal_deficit
        D_max = D0 + deficit_room
        if D > D_max + TOL:
            D = D_max
            binding.append(f"total limited to ₹{D:,.0f} crore by the {FRBM_GFD_LIMIT_PCT:.0f}% GFD/GSDP limit")

    bnd = _bounds(panel, b, D, bounds)
    if social_floor != "NONE":
        if not b.population:
            raise InvalidInput("Population unavailable; per-capita floor cannot be applied")
        if social_floor == "CURRENT":
            floor = x0["social_sector"]
        else:
            med = per_capita_benchmarks(panel, fy_start(b.fiscal_year)).get("social_sector")
            if med is None:
                raise InvalidInput("All-state median not available for this year")
            floor = med * b.population / 1e7
        bnd["social_sector"][0] = max(bnd["social_sector"][0], floor)
        if bnd["social_sector"][0] > bnd["social_sector"][1]:
            bnd["social_sector"][1] = bnd["social_sector"][0]   # the floor overrides the historical maximum

    lo = np.array([bnd[k][0] for k in SECTORS])
    hi = np.array([bnd[k][1] for k in SECTORS])
    if lo.sum() > D + TOL or hi.sum() < D - TOL:
        raise Infeasible("No allocation satisfies the constraints",
                         [f"sum of minimums ₹{lo.sum():,.0f} cr, sum of maximums ₹{hi.sum():,.0f} cr, total ₹{D:,.0f} cr",
                          "relax the sector bounds, the per-capita floor or the total budget"])
    ref = np.array([x0[k] for k in SECTORS])

    if objective == "MIN_ADJUSTMENT":
        res = minimize(lambda x: float(np.sum(((x - ref) / ref) ** 2)), x0=np.clip(ref * D / D0, lo, hi),
                       jac=lambda x: 2 * (x - ref) / ref ** 2, bounds=list(zip(lo, hi)), method="SLSQP",
                       constraints=[{"type": "eq", "fun": lambda x: x.sum() - D, "jac": lambda x: np.ones_like(x)}],
                       options={"ftol": 1e-12, "maxiter": 500})
        x, ok, msg, obj_val = res.x, res.success, res.message, float(res.fun)
    elif objective == "MAX_CAPITAL_OUTLAY":
        ci = SECTORS.index("capital_outlay")
        c = np.zeros(3)
        c[ci] = -1.0
        lp = linprog(c, A_eq=np.ones((1, 3)), b_eq=[D], bounds=list(zip(lo, hi)), method="highs")
        ok, msg = lp.success, lp.message
        x = lp.x if ok else ref
        if ok:  # distribute slack between the non-capital sectors in proportion to current amounts
            cap = x[ci]
            rest_idx = [i for i in range(3) if i != ci]
            rem = D - cap
            sub = minimize(lambda z: float(np.sum(((z - ref[rest_idx]) / ref[rest_idx]) ** 2)),
                           x0=np.clip(ref[rest_idx] * rem / ref[rest_idx].sum(), lo[rest_idx], hi[rest_idx]),
                           bounds=list(zip(lo[rest_idx], hi[rest_idx])), method="SLSQP",
                           constraints=[{"type": "eq", "fun": lambda z: z.sum() - rem}])
            if sub.success:
                x = x.copy()
                x[rest_idx] = sub.x
        obj_val = float(x[ci])
    else:
        supported = [r for r in impact_results if r["supported"] and r["sector"] in SECTORS]
        if not supported:
            raise InvalidInput(
                "The OUTCOME objective needs at least one statistically supported spending-to-indicator "
                "relationship; none is supported by the current data",
                ["use MIN_ADJUSTMENT or MAX_CAPITAL_OUTLAY", "see the impact model results for estimates and intervals"])
        coef = np.zeros(3)
        for r in supported:
            sign = 1.0 if r["higher_is_better"] else -1.0
            coef[SECTORS.index(r["sector"])] += (weights or {}).get(r["sector"], 1.0) * sign * r["elasticity"]
        # optimise over shares z = x / D so gradients are O(1) rather than O(1/₹crore)
        zr = ref / D
        res = minimize(lambda z: -float(coef @ np.log(z / zr)), x0=np.clip(ref / D0, lo / D, hi / D),
                       jac=lambda z: -coef / z, bounds=list(zip(np.maximum(lo / D, 1e-9), hi / D)), method="SLSQP",
                       constraints=[{"type": "eq", "fun": lambda z: z.sum() - 1.0, "jac": lambda z: np.ones_like(z)}],
                       options={"ftol": 1e-12, "maxiter": 500})
        x, ok, msg, obj_val = res.x * D, res.success, res.message, float(-res.fun)

    if not ok:
        raise Infeasible(f"Solver did not converge: {msg}")
    alloc = {k: float(v) for k, v in zip(SECTORS, x)}
    for k, v in alloc.items():
        if abs(v - bnd[k][0]) <= max(1.0, 1e-6 * D):
            binding.append(f"{SECTOR_LABELS[k]} at its minimum")
        elif abs(v - bnd[k][1]) <= max(1.0, 1e-6 * D):
            binding.append(f"{SECTOR_LABELS[k]} at its maximum")
    pc0, pc1 = per_capita(b, x0), per_capita(b, alloc)
    return {
        "state_code": state, "fiscal_year": b.fiscal_year, "baseline_data_status": b.data_status,
        "objective": objective, "status": "OPTIMAL", "objective_value": obj_val, "solver_message": str(msg),
        "total_budget": D, "current_total": D0, "deficit_room": deficit_room, "social_floor": social_floor,
        "allocation": [{"sector": k, "label": SECTOR_LABELS[k], "current": x0[k], "recommended": alloc[k],
                        "change": alloc[k] - x0[k], "change_pct": 100 * (alloc[k] - x0[k]) / x0[k],
                        "min": bnd[k][0], "max": bnd[k][1],
                        "per_capita_current": pc0[k], "per_capita_recommended": pc1[k]} for k in SECTORS],
        "binding_constraints": binding,
        "fiscal_effects": {"current": fiscal_position(b, x0), "recommended": fiscal_position(b, alloc)},
        "indicator_effects": indicator_effects(panel, b, alloc, impact_results),
    }
