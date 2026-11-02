import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from budget_ml.config import RAW_RBI_DIR, RAW_WB_DIR  # noqa: E402
from budget_ml.impact import estimate_all  # noqa: E402
from budget_ml.preprocessing import build_dataset  # noqa: E402
from budget_ml.states import load_states  # noqa: E402


@pytest.fixture(scope="session")
def dataset():
    """The real dataset built from data/raw (World Bank from the local cache)."""
    if not any(RAW_RBI_DIR.glob("*.xlsx")):
        pytest.skip("raw RBI files not present")
    return build_dataset(RAW_RBI_DIR, RAW_WB_DIR, include_wdi=any(RAW_WB_DIR.glob("*.json")))


@pytest.fixture(scope="session")
def panel(dataset):
    return dataset.panel


@pytest.fixture(scope="session")
def categories():
    return {c: s.category for c, s in load_states()[0].items()}


@pytest.fixture(scope="session")
def impact_results(panel, categories):
    return [r.as_dict() for r in estimate_all(panel, categories)]


@pytest.fixture(scope="session")
def trained_registry(dataset, tmp_path_factory):
    """A real model for revenue_expenditure trained into a temporary registry."""
    from budget_ml.forecasting.train import train_target
    root = tmp_path_factory.mktemp("models")
    meta, res = train_target(dataset.panel, "revenue_expenditure", dataset.metadata, root=root)
    return root, meta, res
