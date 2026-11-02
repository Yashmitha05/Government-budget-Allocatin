"""Parser for RBI 'Handbook of Statistics on Indian States' XLSX tables.

Each table has one or more sheets, each a block of year columns with states as rows.
The parser never imputes: '-' (not available) and '.' (not applicable) cells are skipped
and counted, and any row whose label is not a known state/UT is skipped and logged.
"""
from __future__ import annotations

import re
import warnings
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

from ..config import CALENDAR, END_MARCH, FISCAL, GER_FILE, TableSpec
from ..states import resolve, split_marker
from ..years import fy_label, parse_year_header

_MISSING = {"-": "not_available", ".": "not_applicable", "": "blank", "na": "not_available", "n.a.": "not_available"}
_FLAGGED_NUM_RE = re.compile(r"^\s*(-?\d+(?:\.\d+)?)\s*([*#@^]+)\s*$")


@dataclass
class ParseLog:
    missing_cells: dict[str, int] = field(default_factory=dict)
    unresolved_labels: set[str] = field(default_factory=set)
    unparseable_cells: list[str] = field(default_factory=list)
    sheets_used: list[str] = field(default_factory=list)
    sheets_skipped: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "missing_cells": self.missing_cells,
            "unresolved_labels": sorted(self.unresolved_labels),
            "unparseable_cells": self.unparseable_cells,
            "sheets_used": self.sheets_used,
            "sheets_skipped": self.sheets_skipped,
        }


def parse_cell(cell: object) -> tuple[float | None, str | None, str | None]:
    """Return (value, footnote_flag, missing_reason)."""
    if cell is None or (isinstance(cell, float) and cell != cell):
        return None, None, "blank"
    if isinstance(cell, (int, float)) and not isinstance(cell, bool):
        return float(cell), None, None
    text = str(cell).strip()
    if text.lower() in _MISSING:
        return None, None, _MISSING[text.lower()]
    m = _FLAGGED_NUM_RE.match(text)
    if m:
        return float(m.group(1)), m.group(2), None
    try:
        return float(text.replace(",", "")), None, None
    except ValueError:
        return None, None, "unparseable"


def _find_header(df: pd.DataFrame) -> tuple[int, int]:
    """Locate the 'State/Union Territory' header cell; return (row, col)."""
    for i in range(min(len(df), 15)):
        for j, v in enumerate(df.iloc[i].tolist()):
            if isinstance(v, str) and v.strip().lower().startswith("state"):
                return i, j
    raise ValueError("No 'State/Union Territory' header row found")


def _year_columns(df: pd.DataFrame, header_row: int, state_col: int) -> tuple[int, dict[int, tuple]]:
    """Find the row holding year labels (the header row or one of the next two)."""
    for i in range(header_row, min(header_row + 3, len(df))):
        cols = {}
        for j, v in enumerate(df.iloc[i].tolist()):
            if j == state_col:
                continue
            parsed = parse_year_header(v)
            if parsed:
                cols[j] = parsed
        if cols:
            return i, cols
    raise ValueError("No year columns found near header row")


def _to_fy_start(kind: str, year: int, spec: TableSpec) -> int:
    if spec.year_kind == FISCAL:
        if kind != "fiscal":
            raise ValueError(f"{spec.file}: expected fiscal-year columns, found calendar year {year}")
        return year
    if kind != "calendar":
        raise ValueError(f"{spec.file}: expected calendar-year columns, found fiscal year {fy_label(year)}")
    return year if spec.year_kind == CALENDAR else year - 1  # END_MARCH: end-March Y closes FY (Y-1)-Y


def _title_block(df: pd.DataFrame, header_row: int) -> str:
    cells = df.iloc[:header_row].to_numpy().ravel()
    return " ".join(str(c) for c in cells if isinstance(c, str))


def parse_table(path: Path, spec: TableSpec) -> tuple[list[dict], ParseLog]:
    log = ParseLog()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # openpyxl style warnings on RBI files
        sheets = pd.read_excel(path, sheet_name=None, header=None, engine="openpyxl", dtype=object)
    records: list[dict] = []
    for sheet_name, df in sheets.items():
        header_row, state_col = _find_header(df)
        if spec.sheet_contains and spec.sheet_contains.lower() not in _title_block(df, header_row).lower():
            log.sheets_skipped.append(sheet_name)
            continue
        log.sheets_used.append(sheet_name)
        year_row, year_cols = _year_columns(df, header_row, state_col)
        for i in range(year_row + 1, len(df)):
            raw_label = df.iat[i, state_col]
            code = resolve(raw_label)
            if code is None:
                if isinstance(raw_label, str) and raw_label.strip():
                    log.unresolved_labels.add(raw_label.strip()[:80])
                continue
            _, label_flag = split_marker(str(raw_label))
            for j, (kind, year, tag) in year_cols.items():
                value, value_flag, reason = parse_cell(df.iat[i, j])
                if value is None:
                    if reason == "unparseable":
                        log.unparseable_cells.append(f"{sheet_name}!r{i}c{j}={df.iat[i, j]!r}")
                    elif reason != "blank":
                        log.missing_cells[code] = log.missing_cells.get(code, 0) + 1
                    continue
                start = _to_fy_start(kind, year, spec)
                flag = "".join(filter(None, [label_flag, value_flag])) or None
                records.append({
                    "state_code": code,
                    "indicator": spec.indicator,
                    "fy_start": start,
                    "fiscal_year": fy_label(start),
                    "value": value * spec.scale,
                    "data_status": tag or spec.status_default,
                    "footnote_flag": flag,
                    "source_table": spec.source_table,
                    "source_file": spec.file,
                    "source_sheet": sheet_name,
                })
    return records, log


_GER_YEAR_RE = re.compile(r"(\d{4})\s*-\s*(\d{2})")


def parse_ger(path: Path) -> tuple[list[dict], ParseLog]:
    """Table 1 (GER): one sheet per year, columns grouped by school level x sex. Keeps 'Total' columns."""
    log = ParseLog()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        sheets = pd.read_excel(path, sheet_name=None, header=None, engine="openpyxl", dtype=object)
    records: list[dict] = []
    for sheet_name, df in sheets.items():
        header_row, state_col = _find_header(df)
        m = _GER_YEAR_RE.search(_title_block(df, header_row))
        if not m:
            raise ValueError(f"{path.name}[{sheet_name}]: no year in title")
        start = int(m.group(1))
        levels = df.iloc[header_row].ffill().tolist()
        sexes = df.iloc[header_row + 1].tolist()
        cols = {}
        for j, (lvl, sex) in enumerate(zip(levels, sexes)):
            if j > state_col and isinstance(sex, str) and sex.strip().lower() == "total" and isinstance(lvl, str):
                cols[j] = "ger_" + re.sub(r"[^a-z]", "", lvl.split()[0].lower())
        log.sheets_used.append(sheet_name)
        for i in range(header_row + 2, len(df)):
            code = resolve(df.iat[i, state_col])
            if code is None:
                lbl = df.iat[i, state_col]
                if isinstance(lbl, str) and lbl.strip():
                    log.unresolved_labels.add(lbl.strip()[:80])
                continue
            for j, indicator in cols.items():
                value, flag, reason = parse_cell(df.iat[i, j])
                if value is None:
                    if reason not in ("blank", "unparseable"):
                        log.missing_cells[code] = log.missing_cells.get(code, 0) + 1
                    continue
                records.append({
                    "state_code": code, "indicator": indicator, "fy_start": start,
                    "fiscal_year": fy_label(start), "value": value, "data_status": "OBSERVED",
                    "footnote_flag": flag, "source_table": "RBI HSIS T1 (UDISE+)",
                    "source_file": GER_FILE, "source_sheet": sheet_name,
                })
    return records, log
