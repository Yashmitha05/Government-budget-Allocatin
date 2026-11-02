"""Growth-rate forecasters. Each predicts g_next; levels are y_origin * exp(g_next).

All classes are plain picklable objects so fitted models can be saved with joblib.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from .features import CORE_FEATURES, MACRO_FEATURES

MIN_TRAIN_ORIGINS = 4   # a learned model needs at least this many distinct origin years of complete features


class NaiveLast:
    """Next year equals this year (g = 0)."""
    name = "naive_last"
    features: list[str] = []
    params: dict = {}

    def fit(self, df: pd.DataFrame) -> "NaiveLast":
        return self

    def predict(self, df: pd.DataFrame) -> np.ndarray:
        return np.zeros(len(df))


class Drift3y:
    """Next-year growth equals the state's mean growth over the last three years."""
    name = "drift_3y"
    features = ["g3_mean"]
    params: dict = {}

    def fit(self, df: pd.DataFrame) -> "Drift3y":
        return self

    def predict(self, df: pd.DataFrame) -> np.ndarray:
        return df["g3_mean"].to_numpy(dtype=float)


class SklearnGrowth:
    """Pooled panel model on growth features. Rows with missing features are dropped in training."""

    def __init__(self, name: str, kind: str, features: list[str], **params):
        self.name, self.kind, self.features, self.params = name, kind, list(features), params
        self.model = None
        self.n_train = 0

    def _estimator(self):
        if self.kind == "ridge":
            return make_pipeline(StandardScaler(), Ridge(alpha=self.params.get("alpha", 1.0)))
        if self.kind == "hgb":
            return HistGradientBoostingRegressor(random_state=0, **self.params)
        raise ValueError(self.kind)

    def fit(self, df: pd.DataFrame) -> "SklearnGrowth":
        d = df.dropna(subset=self.features + ["g_next"])
        if d.origin.nunique() < MIN_TRAIN_ORIGINS:
            raise ValueError(f"{self.name}: {d.origin.nunique()} training origins < {MIN_TRAIN_ORIGINS}")
        self.model = self._estimator().fit(d[self.features].to_numpy(), d["g_next"].to_numpy())
        self.n_train = len(d)
        return self

    def predict(self, df: pd.DataFrame) -> np.ndarray:
        X = df[self.features].to_numpy(dtype=float)
        out = np.full(len(df), np.nan)
        ok = ~np.isnan(X).any(axis=1)
        if ok.any():
            out[ok] = self.model.predict(X[ok])
        return out


def candidate_models() -> list:
    return [
        NaiveLast(),
        Drift3y(),
        SklearnGrowth("ridge_core", "ridge", CORE_FEATURES, alpha=1.0),
        SklearnGrowth("ridge_macro", "ridge", MACRO_FEATURES, alpha=1.0),
        SklearnGrowth("hgb_macro", "hgb", MACRO_FEATURES, max_depth=3, max_iter=150, learning_rate=0.05,
                      min_samples_leaf=20, l2_regularization=1.0),
    ]


BASELINES = ("naive_last", "drift_3y")
