"""Build data/processed/ from the raw RBI XLSX files and the World Bank cache.

Usage:  python scripts/build_dataset.py [--refresh-wdi]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from budget_ml.config import PROCESSED_DIR, RAW_WB_DIR  # noqa: E402
from budget_ml.ingest.world_bank import load_wdi  # noqa: E402
from budget_ml.preprocessing import build_dataset, write_dataset  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh-wdi", action="store_true", help="re-download World Bank series")
    args = ap.parse_args()
    if args.refresh_wdi:
        load_wdi(RAW_WB_DIR, refresh=True)
    ds = build_dataset()
    write_dataset(ds)
    q = ds.quality
    print(f"dataset {ds.metadata['label']}: {ds.metadata['record_count']} observations, {ds.metadata['panel_rows']} panel rows")
    print(f"duplicates collapsed: {q['duplicates_collapsed']}, outlier flags: {len(q['outlier_flags'])}")
    print("population sum / WDI:", q["population_sum_vs_wdi"])
    print(f"written to {PROCESSED_DIR}")


if __name__ == "__main__":
    main()
