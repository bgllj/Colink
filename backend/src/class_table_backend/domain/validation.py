from __future__ import annotations

from dataclasses import dataclass

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.domain.models import ParsedOccurrence
from class_table_backend.parsing.profiles.base import ExtractionResult

# 时段列提示的常规节次范围；文本节次优先，仅作交叉校验。
_DAYPART_PERIOD_HINTS: dict[str, tuple[int, int]] = {
    "上午": (1, 4),
    "下午": (5, 9),
    "晚上": (10, 12),
    "晚间": (10, 12),
}


@dataclass
class ValidationConfig:
    max_week: int | None = None


def _derive_max_week(occurrences: list[ParsedOccurrence]) -> int:
    week_ends = [week_range.end for occurrence in occurrences for week_range in occurrence.week_ranges]
    return max(week_ends) if week_ends else 0


def validate_extraction(
    extraction: ExtractionResult,
    config: ValidationConfig | None = None,
) -> tuple[list[ParsedOccurrence], list[Issue], int]:
    cfg = config if config is not None else ValidationConfig()
    new_issues: list[Issue] = []

    if cfg.max_week is not None:
        effective_max_week = cfg.max_week
    else:
        effective_max_week = _derive_max_week(extraction.occurrences)
        if effective_max_week == 0:
            new_issues.append(
                Issue(
                    code=IssueCode.MISSING_MAX_WEEK,
                    message="无法从周次表达式推导最大学期周次，且未提供导入配置覆盖",
                    severity="error",
                )
            )

    if effective_max_week > 0:
        for occurrence in extraction.occurrences:
            for week_range in occurrence.week_ranges:
                if week_range.end > effective_max_week:
                    new_issues.append(
                        Issue(
                            code=IssueCode.WEEK_OUT_OF_RANGE,
                            message=(
                                f"周次区间 {week_range.start}-{week_range.end} "
                                f"超出最大学期周次 {effective_max_week}"
                            ),
                            severity="warning",
                            sheet=occurrence.sheet,
                            coordinate=occurrence.coordinate,
                            line_index=occurrence.line_index,
                            raw_text=occurrence.raw_line,
                            details={
                                "range_start": week_range.start,
                                "range_end": week_range.end,
                                "max_week": effective_max_week,
                            },
                        )
                    )

    for occurrence in extraction.occurrences:
        hint = _DAYPART_PERIOD_HINTS.get(occurrence.daypart or "")
        if hint is None or occurrence.period_start is None:
            continue
        hint_start, hint_end = hint
        if hint_start <= occurrence.period_start <= hint_end:
            continue
        new_issues.append(
            Issue(
                code=IssueCode.PERIOD_GRID_MISMATCH,
                message=(
                    f"时段 {occurrence.daypart!r} 的节次 {occurrence.period_start} "
                    f"不在常规范围 {hint_start}-{hint_end} 内；以文本节次为准"
                ),
                severity="warning",
                sheet=occurrence.sheet,
                coordinate=occurrence.coordinate,
                line_index=occurrence.line_index,
                raw_text=occurrence.raw_line,
                details={
                    "daypart": occurrence.daypart,
                    "period_start": occurrence.period_start,
                    "period_end": occurrence.period_end,
                    "hint_start": hint_start,
                    "hint_end": hint_end,
                },
            )
        )

    all_issues = list(extraction.issues) + new_issues
    return extraction.occurrences, all_issues, effective_max_week
