"""Fiscal-year helpers. A fiscal year is written '2014-15' and indexed by its start year (2014)."""
from __future__ import annotations

import re

_FY_RE = re.compile(r"^\s*(\d{4})\s*-\s*(\d{2})\s*(?:\(\s*(A|RE|BE)\s*\))?\s*$", re.IGNORECASE)
_YEAR_RE = re.compile(r"^\s*(\d{4})(?:\.0+)?\s*$")

STATUS_TAGS = {"A": "ACTUAL", "RE": "RE", "BE": "BE"}


def fy_label(start: int) -> str:
    return f"{start}-{(start + 1) % 100:02d}"


def fy_start(label: str) -> int:
    m = _FY_RE.match(label)
    if not m:
        raise ValueError(f"Not a fiscal year label: {label!r}")
    return int(m.group(1))


def parse_year_header(cell: object) -> tuple[str, int, str | None] | None:
    """Classify a header cell.

    Returns ("fiscal", start_year, status_tag) for '2022-23 (RE)',
    ("calendar", year, None) for 2014 / '2014.0', or None if the cell is not a year.
    """
    if cell is None:
        return None
    if isinstance(cell, (int, float)) and not isinstance(cell, bool):
        if cell != cell:  # NaN
            return None
        if float(cell).is_integer() and 1900 < cell < 2100:
            return ("calendar", int(cell), None)
        return None
    text = str(cell)
    m = _FY_RE.match(text)
    if m:
        start, end = int(m.group(1)), int(m.group(2))
        if (start + 1) % 100 != end:
            raise ValueError(f"Inconsistent fiscal year label: {text!r}")
        tag = m.group(3)
        return ("fiscal", start, STATUS_TAGS[tag.upper()] if tag else None)
    m = _YEAR_RE.match(text)
    if m and 1900 < int(m.group(1)) < 2100:
        return ("calendar", int(m.group(1)), None)
    return None
