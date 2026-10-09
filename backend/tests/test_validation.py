from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.domain.models import ParsedOccurrence
from class_table_backend.domain.validation import ValidationConfig, validate_extraction
from class_table_backend.parsing.profiles.base import ExtractionResult
from class_table_backend.parsing.week_expr import WeekParity, WeekRange


def _occurrence(week_ranges: list[WeekRange], **overrides: object) -> ParsedOccurrence:
    params: dict = {
        "course_code": None,
        "course_name": "测试课程",
        "weekday": 1,
        "period_start": 1,
        "period_end": 2,
        "week_text": "1-5周",
        "week_ranges": list(week_ranges),
        "sheet": "Sheet1",
        "coordinate": "B3",
        "line_index": 1,
        "raw_line": "测试课程 1-5周",
    }
    params.update(overrides)
    return ParsedOccurrence(**params)


def test_max_week_defaults_to_week_range_upper_bound() -> None:
    extraction = ExtractionResult(
        occurrences=[
            _occurrence([WeekRange(1, 5)], week_text="1-5周", raw_line="甲 1-5周"),
            _occurrence([WeekRange(7, 16)], week_text="7-16周", raw_line="乙 7-16周"),
        ]
    )
    occurrences, issues, effective_max_week = validate_extraction(extraction)
    assert effective_max_week == 16
    assert occurrences == extraction.occurrences
    assert not any(issue.code is IssueCode.MISSING_MAX_WEEK for issue in issues)
    assert not any(issue.code is IssueCode.WEEK_OUT_OF_RANGE for issue in issues)


def test_max_week_override_keeps_configured_bound() -> None:
    extraction = ExtractionResult(
        occurrences=[
            _occurrence(
                [WeekRange(1, 17)],
                week_text="1-17周",
                raw_line="甲 1-17周",
            )
        ]
    )
    occurrences, issues, effective_max_week = validate_extraction(
        extraction, ValidationConfig(max_week=16)
    )
    assert effective_max_week == 16
    out_of_range = [issue for issue in issues if issue.code is IssueCode.WEEK_OUT_OF_RANGE]
    assert len(out_of_range) == 1
    issue = out_of_range[0]
    assert issue.severity == "warning"
    assert issue.sheet == "Sheet1"
    assert issue.coordinate == "B3"
    assert issue.line_index == 1
    assert issue.raw_text == "甲 1-17周"
    assert not any(other.code is IssueCode.MISSING_MAX_WEEK for other in issues)


def test_period_grid_mismatch_warns_when_daypart_hint_conflicts() -> None:
    extraction = ExtractionResult(
        occurrences=[
            _occurrence(
                [WeekRange(1, 16)],
                week_text="1-16周",
                daypart="上午",
                period_start=10,
                period_end=11,
                raw_line="甲 10-11节",
            )
        ]
    )
    _, issues, _ = validate_extraction(extraction)
    mismatches = [issue for issue in issues if issue.code is IssueCode.PERIOD_GRID_MISMATCH]
    assert len(mismatches) == 1
    issue = mismatches[0]
    assert issue.severity == "warning"
    assert issue.sheet == "Sheet1"
    assert issue.coordinate == "B3"
    assert issue.line_index == 1
    assert issue.raw_text == "甲 10-11节"


def test_empty_weeks_without_override_reports_missing_max_week() -> None:
    extraction = ExtractionResult(occurrences=[_occurrence([], week_text="")])
    occurrences, issues, effective_max_week = validate_extraction(extraction)
    assert effective_max_week == 0
    assert occurrences == extraction.occurrences
    missing = [issue for issue in issues if issue.code is IssueCode.MISSING_MAX_WEEK]
    assert len(missing) == 1
    assert missing[0].severity == "error"


def test_extraction_issues_are_collected_with_validation_issues() -> None:
    preexisting = Issue(
        code=IssueCode.UNRECOGNIZED_LAYOUT,
        message="layout",
        sheet="Sheet1",
    )
    extraction = ExtractionResult(
        occurrences=[_occurrence([], week_text="")],
        issues=[preexisting],
    )
    _, issues, _ = validate_extraction(extraction)
    assert preexisting in issues
    assert any(issue.code is IssueCode.MISSING_MAX_WEEK for issue in issues)


def test_override_suppresses_missing_max_week_when_no_weeks() -> None:
    extraction = ExtractionResult(occurrences=[_occurrence([], week_text="")])
    _, issues, effective_max_week = validate_extraction(
        extraction, ValidationConfig(max_week=8)
    )
    assert effective_max_week == 8
    assert not any(issue.code is IssueCode.MISSING_MAX_WEEK for issue in issues)


def test_matching_daypart_and_periods_produce_no_mismatch() -> None:
    extraction = ExtractionResult(
        occurrences=[
            _occurrence(
                [WeekRange(1, 16)],
                week_text="1-16周",
                daypart="上午",
                period_start=2,
                period_end=3,
            ),
            _occurrence(
                [WeekRange(1, 16)],
                week_text="1-16周",
                daypart="晚上",
                period_start=11,
                period_end=12,
                coordinate="C4",
            ),
        ]
    )
    _, issues, _ = validate_extraction(extraction)
    assert not any(issue.code is IssueCode.PERIOD_GRID_MISMATCH for issue in issues)


def test_out_of_range_warning_per_range() -> None:
    extraction = ExtractionResult(
        occurrences=[
            _occurrence(
                [
                    WeekRange(1, 5, parity=WeekParity.ODD),
                    WeekRange(7, 18, parity=WeekParity.ODD),
                ],
                week_text="1-5,7-18单周",
                raw_line="甲 1-5,7-18单周",
            )
        ]
    )
    _, issues, effective_max_week = validate_extraction(
        extraction, ValidationConfig(max_week=16)
    )
    assert effective_max_week == 16
    out_of_range = [issue for issue in issues if issue.code is IssueCode.WEEK_OUT_OF_RANGE]
    assert len(out_of_range) == 1
    assert out_of_range[0].details["range_start"] == 7
    assert out_of_range[0].details["range_end"] == 18
