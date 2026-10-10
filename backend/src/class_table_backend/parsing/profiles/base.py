from __future__ import annotations

from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue
from class_table_backend.domain.models import ParsedOccurrence, SemesterMeta


@dataclass
class ExtractionResult:
    meta: SemesterMeta = field(default_factory=SemesterMeta)
    occurrences: list[ParsedOccurrence] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
