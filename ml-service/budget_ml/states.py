"""State/UT name resolution against the curated mapping table."""
from __future__ import annotations

import csv
import re
from dataclasses import dataclass
from functools import lru_cache

from .config import STATE_MAPPING_CSV

# Footnote markers RBI appends to row labels, e.g. "Bihar###", "Odisha3", "Andhra Pradesh@".
_MARKER_RE = re.compile(r"[@#*^$]+|(?<=[A-Za-z])\d+$")


@dataclass(frozen=True)
class State:
    code: str
    name: str
    category: str
    formed_fiscal_year: str | None


def _normalise(label: str) -> str:
    s = _MARKER_RE.sub("", label).strip().lower()
    s = re.sub(r"\band\b", "&", s)
    return re.sub(r"\s+", " ", s)


def split_marker(label: str) -> tuple[str, str | None]:
    """Return (label without footnote marker, marker or None)."""
    markers = "".join(m.group(0) for m in _MARKER_RE.finditer(label.strip()))
    return _MARKER_RE.sub("", label).strip(), (markers or None)


@lru_cache(maxsize=1)
def load_states() -> tuple[dict[str, State], dict[str, str]]:
    """Return ({code: State}, {normalised alias: code})."""
    states: dict[str, State] = {}
    aliases: dict[str, str] = {}
    with open(STATE_MAPPING_CSV, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            st = State(row["code"], row["name"], row["category"], row["formed_fiscal_year"] or None)
            states[st.code] = st
            for alias in [st.name, *filter(None, row["aliases"].split("|"))]:
                key = _normalise(alias)
                if key in aliases and aliases[key] != st.code:
                    raise ValueError(f"Alias {alias!r} maps to both {aliases[key]} and {st.code}")
                aliases[key] = st.code
    return states, aliases


def resolve(label: object) -> str | None:
    """Map a raw table row label to a state code, or None if it is not a state row."""
    if not isinstance(label, str) or not label.strip():
        return None
    return load_states()[1].get(_normalise(label))
