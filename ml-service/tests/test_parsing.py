import pandas as pd
import pytest

from budget_ml.config import RAW_RBI_DIR, TABLES
from budget_ml.ingest.rbi_hsis import parse_cell, parse_table
from budget_ml.states import load_states, resolve, split_marker
from budget_ml.years import fy_label, fy_start, parse_year_header


@pytest.mark.parametrize("cell,expected", [
    ("2022-23 (A)", ("fiscal", 2022, "ACTUAL")),
    ("2023-24 (RE)", ("fiscal", 2023, "RE")),
    ("2024-25 (BE)", ("fiscal", 2024, "BE")),
    ("2014-15", ("fiscal", 2014, None)),
    ("1999-00", ("fiscal", 1999, None)),
    (2014.0, ("calendar", 2014, None)),
    ("2014.0", ("calendar", 2014, None)),
    ("Base: 2011-12x", None),
    (float("nan"), None),
])
def test_year_headers(cell, expected):
    assert parse_year_header(cell) == expected


def test_inconsistent_fiscal_label_rejected():
    with pytest.raises(ValueError):
        parse_year_header("2014-16")


def test_fy_roundtrip():
    assert fy_label(1999) == "1999-00"
    assert fy_start(fy_label(2024)) == 2024


@pytest.mark.parametrize("cell,expected", [
    (123.5, (123.5, None, None)),
    ("-", (None, None, "not_available")),
    (".", (None, None, "not_applicable")),
    ("16*", (16.0, "*", None)),
    ("1,234", (1234.0, None, None)),
    ("abc", (None, None, "unparseable")),
])
def test_parse_cell(cell, expected):
    assert parse_cell(cell) == expected


@pytest.mark.parametrize("label,code", [
    ("Andaman & Nicobar Islands", "AN"), ("Andaman and Nicobar Islands", "AN"),
    ("Bihar###", "BR"), ("Odisha3", "OD"), ("Andhra Pradesh@", "AP"), ("Jammu & kashmir", "JK"),
    ("NCT Delhi", "DL"), ("Delhi", "DL"), ("ALL INDIA2", "ALL"), ("All States and UTs", "ALL"),
    ("Dadra & Nagar Haveli & Daman & Diu", "DH"), ("Dadra & Nagar Haveli", "DN"),
    ("Source: RBI", None), ("State/Union Territory", None), (None, None),
])
def test_state_resolution(label, code):
    assert resolve(label) == code


def test_split_marker():
    assert split_marker("Bihar###") == ("Bihar", "###")
    assert split_marker("Kerala") == ("Kerala", None)


def test_mapping_loads_without_alias_conflicts():
    states, aliases = load_states()
    assert "TG" in states and states["TG"].formed_fiscal_year == "2014-15"
    assert len(set(aliases.values())) == len(states)


@pytest.fixture(scope="module")
def gfd():
    spec = next(t for t in TABLES if t.indicator == "gross_fiscal_deficit")
    if not (RAW_RBI_DIR / spec.file).exists():
        pytest.skip("raw RBI files not present")
    recs, log = parse_table(RAW_RBI_DIR / spec.file, spec)
    return pd.DataFrame(recs), log


def test_fiscal_table_structure(gfd):
    df, log = gfd
    assert df.fiscal_year.min() == "2004-05" and df.fiscal_year.max() == "2024-25"
    assert df.groupby("fiscal_year").data_status.first().to_dict()["2023-24"] == "RE"
    status = df.drop_duplicates("fiscal_year").set_index("fiscal_year").data_status
    assert status["2022-23"] == "ACTUAL" and status["2024-25"] == "BE" and status["2021-22"] == "ACCOUNTS"
    assert {"ALL", "KA", "UP", "DL", "PY"} <= set(df.state_code)
    assert not log.unparseable_cells


def test_fiscal_table_known_values(gfd):
    """Regression check: values parsed from T164 must not change if the parser is modified."""
    df, _ = gfd
    val = df.set_index(["state_code", "fiscal_year"]).value
    assert val["KA", "2024-25"] == 82981.0
    assert val["KA", "2022-23"] == 46622.0


def test_missing_cells_not_imputed(gfd):
    df, log = gfd
    assert df[df.state_code == "TG"].fiscal_year.min() == "2014-15"   # '-' before formation
    assert log.missing_cells.get("TG", 0) == 10
    assert df.value.notna().all()
