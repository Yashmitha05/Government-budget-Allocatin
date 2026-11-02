"""Budget baseline for one state and fiscal year, built from published RBI aggregates.

Sector structure (₹ crore), exact by construction:
    deficit-relevant expenditure E = revenue_expenditure + capital_outlay
    committed       = interest_payments + pension            (fixed in scenarios)
    social_sector   = RBI social sector expenditure (T175)
    capital_outlay  = RBI capital outlay (T174)
    other_revenue   = revenue_expenditure - committed - social_sector
    discretionary D = social_sector + capital_outlay + other_revenue = E - committed
RBI's social-sector figure includes its own capital outlay and loans, so the social and capital lines overlap
and other_revenue is a residual; the total D is nonetheless exact. Deficits follow the accounting identity
    GFD = revenue_expenditure + capital_outlay + (net lending - non-debt capital receipts) - revenue receipts
so any change in D changes GFD one-for-one (revenue receipts held fixed).
Health (T18) is published only to 2019-20; for later years it is *estimated* as
social_sector x the state's latest published health share, and marked as such.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import pandas as pd

from .errors import InsufficientHistory, InvalidInput, NotFound
from .preprocessing import ACTUAL_STATUSES
from .years import fy_label, fy_start

SECTORS = ("social_sector", "capital_outlay", "other_revenue")
SECTOR_LABELS = {
    "social_sector": "Social sector (education, health, welfare)",
    "capital_outlay": "Capital outlay (infrastructure and assets)",
    "other_revenue": "Other revenue expenditure (general and economic services)",
    "health": "Health (part of social sector)",
}
FRBM_GFD_LIMIT_PCT = 3.0       # Fiscal Responsibility and Budget Management norm, GFD/GSDP
SHARE_WINDOW = (2011, 2022)    # actual years used for historical share ranges


@dataclass
class Baseline:
    state_code: str
    fiscal_year: str
    data_status: str
    revenue_expenditure: float
    capital_outlay: float
    committed: float
    social_sector: float
    other_revenue: float
    health: float | None
    health_estimated: bool
    health_share_source_year: str | None
    gross_fiscal_deficit: float
    revenue_deficit: float
    revenue_receipts: float
    own_tax_revenue: float
    interest_payments: float
    gsdp: float | None
    population: float | None
    cpi_inflation: float | None

    @property
    def discretionary(self) -> float:
        return self.social_sector + self.capital_outlay + self.other_revenue

    def sectors(self) -> dict[str, float]:
        return {k: getattr(self, k) for k in SECTORS}

    def as_dict(self) -> dict:
        d = asdict(self)
        d["discretionary"] = self.discretionary
        d["gfd_pct_gsdp"] = 100 * self.gross_fiscal_deficit / self.gsdp if self.gsdp else None
        return d


def _row(panel: pd.DataFrame, state: str, year: int) -> pd.Series:
    if state not in set(panel.state_code):
        raise NotFound(f"Unknown state code '{state}'")
    r = panel[(panel.state_code == state) & (panel.fy_start == year)]
    if r.empty:
        raise NotFound(f"No data for {state} in {fy_label(year)}")
    return r.iloc[0]


def latest_budget_year(panel: pd.DataFrame, state: str) -> int:
    g = panel[(panel.state_code == state) & panel.revenue_expenditure.notna()]
    if g.empty:
        raise NotFound(f"No budget data for '{state}'")
    return int(g.fy_start.max())


def _num(v) -> float | None:
    return float(v) if pd.notna(v) else None


def baseline(panel: pd.DataFrame, state: str, fiscal_year: str | None = None) -> Baseline:
    year = fy_start(fiscal_year) if fiscal_year else latest_budget_year(panel, state)
    r = _row(panel, state, year)
    required = ["revenue_expenditure", "capital_outlay", "interest_payments", "pension",
                "social_sector_expenditure", "gross_fiscal_deficit", "revenue_deficit", "own_tax_revenue"]
    missing = [c for c in required if pd.isna(r.get(c))]
    if missing:
        raise InsufficientHistory(f"{state} {fy_label(year)} is missing {missing}")
    committed = float(r.interest_payments + r.pension)
    other = float(r.revenue_expenditure - committed - r.social_sector_expenditure)
    if other < 0:
        raise InvalidInput(f"{state} {fy_label(year)}: published components exceed revenue expenditure; "
                           "sector split not possible for this year")

    health, estimated, src = _num(r.get("health_expenditure")), False, None
    if health is None:
        hist = panel[(panel.state_code == state) & (panel.fy_start <= year)
                     & panel.health_expenditure.notna() & panel.social_sector_expenditure.gt(0)]
        if not hist.empty:
            last = hist.sort_values("fy_start").iloc[-1]
            health = float(r.social_sector_expenditure * last.health_expenditure / last.social_sector_expenditure)
            estimated, src = True, fy_label(int(last.fy_start))
    return Baseline(
        state, fy_label(year), str(r.fiscal_status), float(r.revenue_expenditure), float(r.capital_outlay),
        committed, float(r.social_sector_expenditure), other, health, estimated, src,
        float(r.gross_fiscal_deficit), float(r.revenue_deficit),
        float(r.revenue_expenditure - r.revenue_deficit), float(r.own_tax_revenue), float(r.interest_payments),
        _num(r.get("gsdp_current")), _num(r.get("population")), _num(r.get("cpi_inflation")))


def historical_shares(panel: pd.DataFrame, state: str) -> dict[str, dict[str, float]]:
    """Min / max / mean share of discretionary spending per sector over actual years in SHARE_WINDOW."""
    g = panel[(panel.state_code == state) & panel.fiscal_status.isin(ACTUAL_STATUSES)
              & panel.fy_start.between(*SHARE_WINDOW)].dropna(
        subset=["revenue_expenditure", "capital_outlay", "social_sector_expenditure", "interest_payments", "pension"])
    if len(g) < 3:
        raise InsufficientHistory(f"{state}: fewer than 3 actual years to derive allocation ranges")
    committed = g.interest_payments + g.pension
    other = g.revenue_expenditure - committed - g.social_sector_expenditure
    # years where published components exceed revenue expenditure (e.g. PB 2016-17) have no valid split; skip them
    valid = other >= 0
    g, committed, other = g[valid], committed[valid], other[valid]
    if len(g) < 3:
        raise InsufficientHistory(f"{state}: fewer than 3 actual years with a valid sector split")
    d = g.social_sector_expenditure + g.capital_outlay + other
    shares = {"social_sector": g.social_sector_expenditure / d, "capital_outlay": g.capital_outlay / d,
              "other_revenue": other / d}
    last5 = g.fy_start >= g.fy_start.max() - 4
    return {k: {"min": float(v.min()), "max": float(v.max()), "mean": float(v.mean()),
                "mean_last5": float(v[last5].mean()), "years": f"{fy_label(int(g.fy_start.min()))}..{fy_label(int(g.fy_start.max()))}"}
            for k, v in shares.items()}


def per_capita_benchmarks(panel: pd.DataFrame, year: int) -> dict[str, float]:
    """Median per-capita spending (₹) across states/UTs with legislature in the given year."""
    g = panel[(panel.fy_start == year) & panel.population.gt(0) & panel.revenue_expenditure.notna()]
    out = {}
    if len(g) >= 10:
        out["social_sector"] = float((g.social_sector_expenditure * 1e7 / g.population).median())
        out["capital_outlay"] = float((g.capital_outlay * 1e7 / g.population).median())
    return out


def fiscal_position(b: Baseline, sectors: dict[str, float], revenue_side_change: float | None = None) -> dict:
    """Deficits after changing sector amounts, revenue receipts held at baseline.

    Changes to social_sector and other_revenue are treated as revenue spending (social capital spending is
    not separable in the data, so revenue-deficit effects are an upper bound); capital_outlay changes are capital.
    """
    d_social = sectors["social_sector"] - b.social_sector
    d_other = sectors["other_revenue"] - b.other_revenue
    d_cap = sectors["capital_outlay"] - b.capital_outlay
    d_rev = d_social + d_other if revenue_side_change is None else revenue_side_change
    gfd = b.gross_fiscal_deficit + d_social + d_other + d_cap
    rd = b.revenue_deficit + d_rev
    out = {"discretionary": sum(sectors.values()), "gross_fiscal_deficit": gfd, "revenue_deficit": rd,
           "gfd_pct_gsdp": 100 * gfd / b.gsdp if b.gsdp else None,
           "rd_pct_gsdp": 100 * rd / b.gsdp if b.gsdp else None}
    out["breaches_frbm_gfd_limit"] = out["gfd_pct_gsdp"] is not None and out["gfd_pct_gsdp"] > FRBM_GFD_LIMIT_PCT
    return out


def per_capita(b: Baseline, sectors: dict[str, float]) -> dict[str, float | None]:
    if not b.population:
        return {k: None for k in sectors}
    return {k: v * 1e7 / b.population for k, v in sectors.items()}
