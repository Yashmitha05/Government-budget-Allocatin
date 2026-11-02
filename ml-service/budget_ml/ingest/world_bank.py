"""World Bank WDI fetcher for India national series, cached as raw JSON for reproducibility."""
from __future__ import annotations

import json
import urllib.request
from pathlib import Path

from ..config import RAW_WB_DIR, WDI_SERIES
from ..years import fy_label

API = "https://api.worldbank.org/v2/country/IND/indicator/{code}?format=json&per_page=200"


def fetch_series(code: str, cache_dir: Path = RAW_WB_DIR, refresh: bool = False) -> list[dict]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache = cache_dir / f"{code}.json"
    if refresh or not cache.exists():
        with urllib.request.urlopen(API.format(code=code), timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        if not isinstance(payload, list) or len(payload) < 2 or not payload[1]:
            raise RuntimeError(f"World Bank API returned no data for {code}: {str(payload)[:200]}")
        cache.write_text(json.dumps(payload), encoding="utf-8")
    return json.loads(cache.read_text(encoding="utf-8"))[1]


def load_wdi(cache_dir: Path = RAW_WB_DIR, refresh: bool = False) -> list[dict]:
    """Return long-format national records.

    WDI values are calendar-year; calendar year Y is mapped to fiscal year Y-(Y+1)
    (April-March overlaps nine months of Y). This approximation is documented in ML_PIPELINE.md.
    """
    records = []
    for code, indicator in WDI_SERIES.items():
        for row in fetch_series(code, cache_dir, refresh):
            if row.get("value") is None:
                continue
            year = int(row["date"])
            records.append({
                "state_code": "IN", "indicator": indicator, "fy_start": year, "fiscal_year": fy_label(year),
                "value": float(row["value"]), "data_status": "OBSERVED", "footnote_flag": None,
                "source_table": f"World Bank WDI {code}", "source_file": f"world_bank/{code}.json",
                "source_sheet": None,
            })
    return records
