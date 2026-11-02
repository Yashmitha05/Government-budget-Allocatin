"""Train (select, calibrate intervals, fit, register) a forecasting model for every target.

Usage:  python scripts/train_models.py
Writes models/<target>/v<N>/ and reports/backtest_predictions.csv.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pandas as pd  # noqa: E402

from budget_ml.config import ML_ROOT  # noqa: E402
from budget_ml.forecasting.train import train_target  # noqa: E402
from budget_ml.preprocessing import FORECAST_TARGETS, load_processed  # noqa: E402


def main() -> None:
    _, panel, meta = load_processed()
    reports = ML_ROOT / "reports"
    reports.mkdir(exist_ok=True)
    all_res = []
    for target in FORECAST_TARGETS:
        m, res = train_target(panel, target, meta)
        all_res.append(res)
        hold = m["holdout_metrics"]
        print(f"\n{target}: chose {m['model_type']} -> v{m['version']}")
        print("  selection MAPE h1:", {k: round(v, 2) for k, v in m["selection_mape_h1"].items()})
        for model, hs in hold.items():
            print(f"  holdout {model:12s}", {h: (round(x['mape'], 2), x['n']) for h, x in hs.items()})
        print("  interval coverage (holdout):", m["interval_coverage_holdout"])
    pd.concat(all_res).to_csv(reports / "backtest_predictions.csv", index=False)


if __name__ == "__main__":
    main()
