"""Material disclosure preprocessing."""

from .paid_in_capital import (
    ACODE_FACT_KEYS,
    PAID_IN_CAPITAL_DOCUMENT_ACODE,
    parse_paid_in_capital_increase,
)
from .treasury_acquisition import (
    TREASURY_ACQUISITION_DOCUMENT_ACODE,
    parse_treasury_stock_acquisition,
)

__all__ = [
    "ACODE_FACT_KEYS",
    "PAID_IN_CAPITAL_DOCUMENT_ACODE",
    "TREASURY_ACQUISITION_DOCUMENT_ACODE",
    "parse_paid_in_capital_increase",
    "parse_treasury_stock_acquisition",
]
