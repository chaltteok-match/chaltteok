"""Material disclosure preprocessing."""

from .paid_in_capital import (
    ACODE_FACT_KEYS,
    PAID_IN_CAPITAL_DOCUMENT_ACODE,
    parse_paid_in_capital_increase,
)

__all__ = [
    "ACODE_FACT_KEYS",
    "PAID_IN_CAPITAL_DOCUMENT_ACODE",
    "parse_paid_in_capital_increase",
]
