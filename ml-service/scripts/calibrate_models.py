"""Run the feedback loop on real backtest deviations for every target.

Calibration records: the active model type's one-year-ahead backtest predictions for target years up to
2020-21 (selection window). Evaluation records: target years 2021-22 and 2022-23 (hold-out). A calibrated
child version is saved for each target and activated only if hold-out MAPE improves.

Usage:  python scripts/calibrate_models.py   (after train_models.py)
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pandas as pd  # noqa: E402

from budget_ml.config import ML_ROOT  # noqa: E402
from budget_ml.feedback import calibrate_version  # noqa: E402
from budget_ml.preprocessing import FORECAST_TARGETS  # noqa: E402
from budget_ml.registry import load  # noqa: E402
from budget_ml.years import fy_start  # noqa: E402


def main() -> None:
    res = pd.read_csv(ML_ROOT / "reports" / "backtest_predictions.csv")
    for target in FORECAST_TARGETS:
        _, meta = load(target)
        r = res[(res.target == target) & (res.model == meta["model_type"]) & (res.horizon == 1)]
        years = r.target_year.map(fy_start)
        out = calibrate_version(target, r[years <= 2020], r[years >= 2021])
        e = out["calibration_evaluation"]
        print(f"{target:28s} {meta['model_type']:12s} v{out['previous_active_version']}->v{out['version']} "
              f"hold-out MAPE {e['mape_before']:.2f} -> {e['mape_after']:.2f}, bias {e['bias_pct_before']:+.2f}% -> "
              f"{e['bias_pct_after']:+.2f}%  activated={out['is_active']}")


if __name__ == "__main__":
    main()
