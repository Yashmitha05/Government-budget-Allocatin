"""Rolling-origin backtest of the candidate forecasters for every forecast target.

Usage:  python scripts/backtest_forecasts.py
Writes ml-service/reports/backtest_predictions.csv, backtest_summary.csv and backtest_by_year.csv.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pandas as pd  # noqa: E402

from budget_ml.config import ML_ROOT  # noqa: E402
from budget_ml.forecasting.backtest import backtest, summarise, summarise_by_year  # noqa: E402
from budget_ml.preprocessing import FORECAST_TARGETS, load_processed  # noqa: E402


def main() -> None:
    _, panel, meta = load_processed()
    out_dir = ML_ROOT / "reports"
    out_dir.mkdir(exist_ok=True)
    all_preds, summaries, by_year = [], [], []
    for target in FORECAST_TARGETS:
        res = backtest(panel, target)
        all_preds.append(res)
        s = summarise(res)
        s.insert(0, "target", target)
        summaries.append(s)
        y = summarise_by_year(res).reset_index()
        y.insert(0, "target", target)
        by_year.append(y)
    pd.concat(all_preds).to_csv(out_dir / "backtest_predictions.csv", index=False)
    summary = pd.concat(summaries)
    summary.to_csv(out_dir / "backtest_summary.csv", index=False)
    pd.concat(by_year).to_csv(out_dir / "backtest_by_year.csv", index=False)
    print(f"dataset {meta['label']}")
    with pd.option_context("display.width", 200, "display.float_format", "{:,.3f}".format):
        print(summary.to_string(index=False))


if __name__ == "__main__":
    main()
