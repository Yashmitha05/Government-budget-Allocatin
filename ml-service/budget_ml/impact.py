"""Spending -> public-service indicator relationships (two-way fixed-effects panel regressions).

Specification for each (spending x, outcome y) pair:
    ln y[s, t+lag] = a_s + d_t + b * ln x_pc[s, t] + c * ln nsdp_pc[s, t] + e
    x_pc = sector spending per person (₹), nsdp_pc = per-capita NSDP (current prices).
State effects absorb fixed differences between states; year effects absorb common shocks and the
common price level (so nominal per-capita values are acceptable). b is an elasticity: a 1% increase in
per-capita spending is associated with a b% change in the indicator.

Uncertainty: state-clustered bootstrap (resample whole states). A relationship is "supported" only if the
90% bootstrap interval of b excludes zero AND has the expected sign. These are historical associations,
not causal effects.
"""
from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import pandas as pd

log = logging.getLogger("ml-service.impact")

ELIGIBLE_CATEGORIES = ("STATE", "UT_LEGISLATURE")
N_BOOT = 999
CI_LEVEL = 0.90


@dataclass(frozen=True)
class ImpactSpec:
    key: str
    sector: str            # allocation sector the spending variable represents
    spend_col: str         # per-capita spending column in the panel
    outcome: str
    outcome_name: str
    expected_sign: int     # -1: more spending should lower the indicator (IMR); +1: raise it (roads)
    lag: int = 1
    higher_is_better: bool = False


SPECS: tuple[ImpactSpec, ...] = (
    ImpactSpec("health_imr", "health", "health_expenditure_per_capita", "infant_mortality_rate",
               "Infant mortality rate (per 1,000 live births)", -1),
    ImpactSpec("social_imr", "social_sector", "social_sector_expenditure_per_capita", "infant_mortality_rate",
               "Infant mortality rate (per 1,000 live births)", -1),
    ImpactSpec("capital_roads", "capital_outlay", "capital_outlay_per_capita", "road_length",
               "Road length (km)", +1, higher_is_better=True),
    ImpactSpec("capital_unemp_urban", "capital_outlay", "capital_outlay_per_capita", "unemployment_urban",
               "Urban unemployment rate (per 1,000)", -1),
)


@dataclass
class ImpactResult:
    key: str
    sector: str
    outcome: str
    outcome_name: str
    elasticity: float
    ci_low: float
    ci_high: float
    n_obs: int
    n_states: int
    years: str
    within_r2: float
    supported: bool
    expected_sign: int
    higher_is_better: bool
    note: str

    def as_dict(self) -> dict:
        return asdict(self)


def _design(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """X with [ln x, ln nsdp_pc, state dummies, year dummies (drop first)], y = ln outcome."""
    X = np.column_stack([
        df["lx"].to_numpy(), df["lnsdp"].to_numpy(),
        pd.get_dummies(df["state_code"], dtype=float).to_numpy(),
        pd.get_dummies(df["fy_start"], drop_first=True, dtype=float).to_numpy(),
    ])
    return X, df["ly"].to_numpy()


def _fit(df: pd.DataFrame) -> np.ndarray:
    X, y = _design(df)
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    return beta


def estimation_sample(panel: pd.DataFrame, spec: ImpactSpec, categories: dict[str, str]) -> pd.DataFrame:
    p = panel[panel.state_code.map(categories).isin(ELIGIBLE_CATEGORIES) & ~panel.structural_break]
    future = p[["state_code", "fy_start", spec.outcome]].copy()
    future["fy_start"] -= spec.lag
    d = p[["state_code", "fy_start", spec.spend_col, "per_capita_nsdp_current"]].merge(
        future, on=["state_code", "fy_start"])
    d = d[(d[spec.spend_col] > 0) & (d[spec.outcome] > 0) & (d.per_capita_nsdp_current > 0)].dropna()
    d = d.assign(lx=np.log(d[spec.spend_col]), lnsdp=np.log(d.per_capita_nsdp_current), ly=np.log(d[spec.outcome]))
    counts = d.state_code.value_counts()
    return d[d.state_code.isin(counts[counts >= 3].index)].reset_index(drop=True)


def estimate(panel: pd.DataFrame, spec: ImpactSpec, categories: dict[str, str], seed: int = 0) -> ImpactResult:
    d = estimation_sample(panel, spec, categories)
    if len(d) < 40 or d.state_code.nunique() < 10:
        return ImpactResult(spec.key, spec.sector, spec.outcome, spec.outcome_name, float("nan"), float("nan"),
                            float("nan"), len(d), d.state_code.nunique(), "", float("nan"), False,
                            spec.expected_sign, spec.higher_is_better, "insufficient overlapping data")
    beta = _fit(d)
    b = float(beta[0])
    # within R^2: share of variation left after the fixed effects that the two regressors explain
    X, y = _design(d)
    fe_only = X[:, 2:]
    r_fe = y - fe_only @ np.linalg.lstsq(fe_only, y, rcond=None)[0]
    r_full = y - X @ beta
    within_r2 = float(1 - (r_full @ r_full) / (r_fe @ r_fe)) if r_fe @ r_fe > 0 else float("nan")

    rng = np.random.default_rng(seed)
    states = d.state_code.unique()
    groups = {s: g for s, g in d.groupby("state_code")}
    boots = []
    for _ in range(N_BOOT):
        pick = rng.choice(states, size=len(states), replace=True)
        parts = []
        for i, s in enumerate(pick):  # relabel duplicates so each draw gets its own fixed effect
            parts.append(groups[s].assign(state_code=f"{s}_{i}"))
        boots.append(_fit(pd.concat(parts, ignore_index=True))[0])
    lo, hi = np.quantile(boots, [(1 - CI_LEVEL) / 2, 1 - (1 - CI_LEVEL) / 2])
    excludes_zero = lo > 0 or hi < 0
    right_sign = np.sign(b) == spec.expected_sign
    supported = bool(excludes_zero and right_sign)
    note = ("supported: interval excludes zero with expected sign" if supported else
            "not supported: interval includes zero" if not excludes_zero else
            "not supported: sign opposite to expectation (likely confounding)")
    years = f"{int(d.fy_start.min())}-{int(d.fy_start.max())} (spending year)"
    return ImpactResult(spec.key, spec.sector, spec.outcome, spec.outcome_name, b, float(lo), float(hi), len(d),
                        int(d.state_code.nunique()), years, within_r2, supported, spec.expected_sign,
                        spec.higher_is_better, note)


def estimate_all(panel: pd.DataFrame, categories: dict[str, str]) -> list[ImpactResult]:
    return [estimate(panel, s, categories) for s in SPECS]


CACHE_FILE = "impact_results.json"


def cache_key(dataset_hash: str) -> dict:
    """Everything the results depend on: the data and the estimation settings (the bootstrap seed is fixed)."""
    return {"dataset_hash": dataset_hash, "n_boot": N_BOOT, "ci_level": CI_LEVEL, "seed": 0,
            "eligible_categories": list(ELIGIBLE_CATEGORIES), "specs": [asdict(s) for s in SPECS]}


def load_or_estimate(panel: pd.DataFrame, categories: dict[str, str], cache_path: Path, dataset_hash: str) -> list[dict]:
    """Results of estimate_all, reused from cache_path when it was computed for the same data and settings.

    The estimation is deterministic, so a matching cache gives identical results; it only avoids repeating
    ~4,000 regression fits at every service start. A missing or stale cache is recomputed and rewritten.
    """
    key = cache_key(dataset_hash)
    try:
        cached = json.loads(cache_path.read_text(encoding="utf-8"))
        if cached.get("key") == key:
            return cached["results"]
    except (OSError, ValueError, KeyError):
        pass
    results = [r.as_dict() for r in estimate_all(panel, categories)]
    try:
        cache_path.write_text(json.dumps({"key": key, "results": results}, indent=2), encoding="utf-8")
    except OSError as exc:   # read-only filesystem: still correct, just recomputed next start
        log.warning("could not write impact cache %s: %s", cache_path, exc)
    return results


def predict_outcome(result: dict, baseline_outcome: float, baseline_spend: float, scenario_spend: float) -> dict:
    """Indicator under a scenario: baseline * exp(b * ln(scenario/baseline)), with the bootstrap interval."""
    if not result["supported"] or baseline_spend <= 0 or scenario_spend <= 0:
        return {"predicted": baseline_outcome if not result["supported"] else None, "lower": None, "upper": None}
    dl = float(np.log(scenario_spend / baseline_spend))
    vals = sorted(baseline_outcome * float(np.exp(b * dl)) for b in (result["ci_low"], result["ci_high"]))
    return {"predicted": baseline_outcome * float(np.exp(result["elasticity"] * dl)), "lower": vals[0], "upper": vals[1]}
