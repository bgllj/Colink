from __future__ import annotations

import re
from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.parsing.normalize import normalize_expression_text

_PERIOD_SUFFIX_PATTERN = re.compile(r"节$")
_SEGMENT_PATTERN = re.compile(r"^(-?\d+)(?:-(-?\d+))?$")


@dataclass(frozen=True)
class PeriodRange:
    start: int
    end: int


@dataclass
class PeriodParseResult:
    original_text: str
    range: PeriodRange | None = None
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(issue.severity == "error" for issue in self.issues)


def parse_period_expression(text: str) -> PeriodParseResult:
    original_text = text
    normalized = normalize_expression_text(text)

    if not normalized:
        return PeriodParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.EMPTY_PERIOD_EXPRESSION,
                    message="节次表达式为空",
                    raw_text=original_text,
                )
            ],
        )

    body = _PERIOD_SUFFIX_PATTERN.sub("", normalized, count=1)

    if not body:
        return PeriodParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.EMPTY_PERIOD_EXPRESSION,
                    message="节次表达式为空",
                    raw_text=original_text,
                )
            ],
        )

    match = _SEGMENT_PATTERN.fullmatch(body)
    if match is None:
        return PeriodParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.INVALID_PERIOD_EXPRESSION,
                    message=f"无法解析的节次表达式: {body!r}",
                    raw_text=original_text,
                )
            ],
        )

    start = int(match.group(1))
    end = int(match.group(2)) if match.group(2) is not None else start

    if start <= 0 or end <= 0:
        return PeriodParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.INVALID_PERIOD_EXPRESSION,
                    message=f"节次必须为正整数: {body!r}",
                    raw_text=original_text,
                    details={"start": start, "end": end},
                )
            ],
        )

    if start > end:
        return PeriodParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.REVERSED_PERIOD_RANGE,
                    message=f"节次区间起始大于结束: {body!r}",
                    raw_text=original_text,
                    details={"start": start, "end": end},
                )
            ],
        )

    return PeriodParseResult(original_text=original_text, range=PeriodRange(start=start, end=end))
