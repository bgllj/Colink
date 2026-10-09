from __future__ import annotations

from enum import StrEnum


class ImportStatus(StrEnum):
    UPLOADED = "UPLOADED"
    PARSED = "PARSED"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"
    FAILED = "FAILED"
