"""Build the validated long-format dataset and the state x fiscal-year panel.

Decisions (also documented in ML_PIPELINE.md):
* Missing source cells are dropped, never imputed.
* Exact duplicate keys are collapsed; conflicting duplicates raise DataQualityError.
* Large year-on-year jumps are flagged in the quality report, never removed.
* Growth is not computed across documented structural breaks (STRUCTURAL_BREAKS).
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from .config import GER_FILE, PROCESSED_DIR, RAW_RBI_DIR, RAW_WB_DIR, TABLES
from .ingest.rbi_hsis import parse_ger, parse_table
from .ingest.world_bank import load_wdi
from .years import fy_label

KEY = ["state_code", "indicator", "fy_start"]
FISCAL_INDICATORS = [t.indicator for t in TABLES if t.category == "FISCAL" and t.status_default == "ACCOUNTS"]
FORECAST_TARGETS = [t.indicator for t in TABLES if t.is_forecast_target]
ACTUAL_STATUSES = ("ACCOUNTS", "ACTUAL")

# fy_start values at which a state's series is not comparable with the previous year.
STRUCTURAL_BREAKS: dict[str, tuple[int, ...]] = {
    "AP": (2014,),        # bifurcation: Telangana formed 2 June 2014
    "JK": (2019, 2020),   # reorganisation into UTs J&K and Ladakh, 31 Oct 2019
}
OUTLIER_LOG_JUMP = 1.5   # |ln(x_t / x_{t-1})| above this (x4.5) is flagged for review


class DataQualityError(RuntimeError):
    pass


@dataclass
class Dataset:
    observations: pd.DataFrame   # long format, raw + derived
    panel: pd.DataFrame          # wide, one row per (state_code, fy_start); excludes aggregates
    quality: dict
    metadata: dict


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_raw(rbi_dir: Path = RAW_RBI_DIR, wb_dir: Path = RAW_WB_DIR, include_wdi: bool = True) -> tuple[pd.DataFrame, dict]:
    records, parse_logs = [], {}
    for spec in TABLES:
        recs, log = parse_table(rbi_dir / spec.file, spec)
        records += recs
        parse_logs[spec.indicator] = log.as_dict()
    recs, log = parse_ger(rbi_dir / GER_FILE)
    records += recs
    parse_logs["gross_enrolment_ratio"] = log.as_dict()
    if include_wdi:
        records += load_wdi(wb_dir)
    return pd.DataFrame.from_records(records), parse_logs


def deduplicate(df: pd.DataFrame) -> tuple[pd.DataFrame, int]:
    dup_mask = df.duplicated(KEY, keep=False)
    if dup_mask.any():
        conflicts = df[dup_mask].groupby(KEY)["value"].nunique()
        conflicts = conflicts[conflicts > 1]
        if len(conflicts):
            raise DataQualityError(f"Conflicting duplicate values for {len(conflicts)} keys, e.g. {conflicts.index[:3].tolist()}")
    before = len(df)
    df = df.drop_duplicates(KEY, keep="first").reset_index(drop=True)
    return df, before - len(df)


def _is_break(state: str, fy: int) -> bool:
    return fy in STRUCTURAL_BREAKS.get(state, ())


def flag_outliers(df: pd.DataFrame) -> list[dict]:
    flags = []
    sub = df[(df.state_code != "ALL") & (df.state_code != "IN") & (df.value > 0)].sort_values(KEY)
    for (state, ind), g in sub.groupby(["state_code", "indicator"]):
        g = g[["fy_start", "value"]].to_numpy()
        for (y0, v0), (y1, v1) in zip(g[:-1], g[1:]):
            if y1 == y0 + 1 and not _is_break(state, int(y1)):
                jump = float(np.log(v1 / v0))
                if abs(jump) > OUTLIER_LOG_JUMP:
                    flags.append({"state_code": state, "indicator": ind, "fiscal_year": fy_label(int(y1)),
                                  "previous": float(v0), "value": float(v1), "log_change": round(jump, 3)})
    return flags


def aggregate_check(df: pd.DataFrame) -> dict:
    """Compare the sum of states with the published 'All States and UTs' row (fiscal tables)."""
    out = {}
    for ind in FISCAL_INDICATORS:
        sub = df[df.indicator == ind]
        total = sub[sub.state_code == "ALL"].set_index("fy_start")["value"]
        summed = sub[sub.state_code != "ALL"].groupby("fy_start")["value"].sum()
        rel = ((summed - total) / total.abs()).dropna()
        out[ind] = {"median_abs_rel_diff": round(float(rel.abs().median()), 5),
                    "max_abs_rel_diff": round(float(rel.abs().max()), 5),
                    "years_compared": int(rel.size)}
    return out


def build_panel(obs: pd.DataFrame) -> pd.DataFrame:
    states = obs[~obs.state_code.isin(["ALL", "IN"])]
    panel = states.pivot_table(index=["state_code", "fy_start"], columns="indicator", values="value", aggfunc="first")
    panel.columns.name = None
    status = (states[states.indicator == "revenue_expenditure"]
              .set_index(["state_code", "fy_start"])["data_status"].rename("fiscal_status"))
    panel = panel.join(status).reset_index()

    national = obs[obs.state_code == "IN"].pivot_table(index="fy_start", columns="indicator", values="value")
    national.columns.name = None
    panel = panel.merge(national, left_on="fy_start", right_index=True, how="left")

    # Derived variables (from published values only).
    panel["population"] = panel["nsdp_current"] * 1e7 / panel["per_capita_nsdp_current"]
    panel["committed_expenditure"] = panel["interest_payments"] + panel["pension"]
    panel["total_expenditure"] = panel["revenue_expenditure"] + panel["capital_expenditure"]
    panel["social_ex_health"] = panel["social_sector_expenditure"] - panel["health_expenditure"]
    for col in ["gross_fiscal_deficit", "revenue_deficit", "own_tax_revenue", "total_expenditure",
                "social_sector_expenditure", "capital_outlay", "committed_expenditure"]:
        panel[f"{col}_pct_gsdp"] = 100 * panel[col] / panel["gsdp_current"]
    for col in ["social_sector_expenditure", "health_expenditure", "capital_outlay", "total_expenditure"]:
        panel[f"{col}_per_capita"] = panel[col] * 1e7 / panel["population"]  # ₹ crore -> ₹ per person

    panel = panel.sort_values(["state_code", "fy_start"]).reset_index(drop=True)
    panel.insert(2, "fiscal_year", panel["fy_start"].map(fy_label))
    panel["structural_break"] = [_is_break(s, int(y)) for s, y in zip(panel.state_code, panel.fy_start)]
    return panel


DERIVED_COLUMNS = ["population", "committed_expenditure", "total_expenditure", "social_ex_health"]


def derived_to_long(panel: pd.DataFrame) -> pd.DataFrame:
    long = panel.melt(id_vars=["state_code", "fy_start", "fiscal_year"], value_vars=DERIVED_COLUMNS,
                      var_name="indicator", value_name="value").dropna(subset=["value"])
    long["data_status"] = "DERIVED"
    long["footnote_flag"] = None
    long["source_table"] = "derived"
    long["source_file"] = None
    long["source_sheet"] = None
    return long


def build_dataset(rbi_dir: Path = RAW_RBI_DIR, wb_dir: Path = RAW_WB_DIR, include_wdi: bool = True) -> Dataset:
    raw, parse_logs = load_raw(rbi_dir, wb_dir, include_wdi)
    obs, n_dupes = deduplicate(raw)
    panel = build_panel(obs)

    pop = panel.groupby("fy_start")["population"].sum(min_count=20)
    wdi_pop = panel.groupby("fy_start")["india_population"].first() if "india_population" in panel else None
    pop_check = {}
    if wdi_pop is not None:
        for y in pop.dropna().index:
            if pd.notna(wdi_pop.get(y)):
                pop_check[fy_label(int(y))] = round(float(pop[y] / wdi_pop[y]), 4)

    observations = pd.concat([obs, derived_to_long(panel)], ignore_index=True)
    coverage = (observations.groupby("indicator")
                .agg(first_year=("fy_start", "min"), last_year=("fy_start", "max"),
                     states=("state_code", "nunique"), records=("value", "size")))
    coverage["first_year"] = coverage["first_year"].map(fy_label)
    coverage["last_year"] = coverage["last_year"].map(fy_label)

    quality = {
        "duplicates_collapsed": n_dupes,
        "parse_logs": parse_logs,
        "aggregate_check": aggregate_check(obs),
        "outlier_flags": flag_outliers(obs),
        "structural_breaks": {k: [fy_label(y) for y in v] for k, v in STRUCTURAL_BREAKS.items()},
        "population_sum_vs_wdi": pop_check,
        "coverage": coverage.reset_index().to_dict(orient="records"),
    }
    raw_files = sorted(rbi_dir.glob("*.xlsx")) + (sorted(wb_dir.glob("*.json")) if include_wdi else [])
    hashes = {f"{p.parent.name}/{p.name}": sha256(p) for p in raw_files}
    content_hash = hashlib.sha256("".join(f"{k}:{v}" for k, v in sorted(hashes.items())).encode()).hexdigest()
    metadata = {
        "label": f"hsis-2024-25+wdi-{content_hash[:8]}",
        "content_hash": content_hash,
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source_files": hashes,
        "record_count": int(len(observations)),
        "panel_rows": int(len(panel)),
        "sources": [
            "Reserve Bank of India, Handbook of Statistics on Indian States 2024-25 (released 11 Dec 2025), https://www.rbi.org.in/Scripts/AnnualPublications.aspx?head=Handbook%20of%20Statistics%20on%20Indian%20States",
            "World Bank, World Development Indicators API, https://api.worldbank.org/v2/country/IND/indicator/",
        ],
    }
    return Dataset(observations, panel, quality, metadata)


def write_dataset(ds: Dataset, out_dir: Path = PROCESSED_DIR) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    ds.observations.sort_values(KEY).to_csv(out_dir / "observations.csv", index=False)
    ds.panel.to_csv(out_dir / "panel.csv", index=False)
    (out_dir / "quality_report.json").write_text(json.dumps(ds.quality, indent=2, default=str), encoding="utf-8")
    (out_dir / "dataset_metadata.json").write_text(json.dumps(ds.metadata, indent=2), encoding="utf-8")


def load_processed(out_dir: Path = PROCESSED_DIR) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    obs = pd.read_csv(out_dir / "observations.csv")
    panel = pd.read_csv(out_dir / "panel.csv")
    meta = json.loads((out_dir / "dataset_metadata.json").read_text(encoding="utf-8"))
    return obs, panel, meta
