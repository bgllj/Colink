from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.week_expr import WeekParity, WeekRange, parse_week_expression


def test_single_week() -> None:
    result = parse_week_expression("15周")
    assert result.ok
    assert result.ranges == [WeekRange(start=15, end=15, parity=WeekParity.ALL)]
    assert result.original_text == "15周"


def test_simple_range() -> None:
    result = parse_week_expression("2-16周")
    assert result.ok
    assert result.ranges == [WeekRange(start=2, end=16, parity=WeekParity.ALL)]


def test_disjoint_ranges() -> None:
    result = parse_week_expression("1-5, 7-16周")
    assert result.ok
    assert result.ranges == [
        WeekRange(start=1, end=5, parity=WeekParity.ALL),
        WeekRange(start=7, end=16, parity=WeekParity.ALL),
    ]


def test_trailing_double_week_applies_to_all_segments() -> None:
    result = parse_week_expression("2-4,8-16双周")
    assert result.ok
    assert result.ranges == [
        WeekRange(start=2, end=4, parity=WeekParity.EVEN),
        WeekRange(start=8, end=16, parity=WeekParity.EVEN),
    ]
    assert result.expanded_weeks() == [2, 4, 8, 10, 12, 14, 16]


def test_single_week_and_range_with_double_week() -> None:
    result = parse_week_expression("2,6-16双周")
    assert result.ok
    assert result.ranges == [
        WeekRange(start=2, end=2, parity=WeekParity.EVEN),
        WeekRange(start=6, end=16, parity=WeekParity.EVEN),
    ]
    assert result.expanded_weeks() == [2, 6, 8, 10, 12, 14, 16]


def test_trailing_odd_week_applies_to_all_segments() -> None:
    result = parse_week_expression("1-16单周")
    assert result.ok
    assert result.ranges == [WeekRange(start=1, end=16, parity=WeekParity.ODD)]
    assert result.expanded_weeks() == [1, 3, 5, 7, 9, 11, 13, 15]


def test_list_of_single_weeks() -> None:
    result = parse_week_expression("1,3周")
    assert result.ok
    assert result.ranges == [
        WeekRange(start=1, end=1, parity=WeekParity.ALL),
        WeekRange(start=3, end=3, parity=WeekParity.ALL),
    ]


def test_reversed_range_is_error() -> None:
    result = parse_week_expression("16-2周")
    assert not result.ok
    assert any(issue.code is IssueCode.REVERSED_WEEK_RANGE for issue in result.issues)
    assert result.ranges == []


def test_unsupported_phrase_is_error() -> None:
    result = parse_week_expression("隔周上课")
    assert not result.ok
    assert any(issue.code is IssueCode.UNSUPPORTED_WEEK_PHRASE for issue in result.issues)


def test_empty_is_error() -> None:
    result = parse_week_expression("")
    assert not result.ok
    assert any(issue.code is IssueCode.EMPTY_WEEK_EXPRESSION for issue in result.issues)
    result_ws = parse_week_expression("   ")
    assert not result_ws.ok
    assert any(issue.code is IssueCode.EMPTY_WEEK_EXPRESSION for issue in result_ws.issues)


def test_original_text_preserved() -> None:
    result = parse_week_expression("3-11周")
    assert result.ok
    assert result.original_text == "3-11周"
    assert result.ranges == [WeekRange(start=3, end=11, parity=WeekParity.ALL)]


def test_arbitrary_bounds_not_hardcoded() -> None:
    result = parse_week_expression("3-11周")
    assert result.ok
    assert result.ranges == [WeekRange(start=3, end=11, parity=WeekParity.ALL)]
    assert result.expanded_weeks() == list(range(3, 12))


def test_non_positive_bound_is_error() -> None:
    result = parse_week_expression("0-5周")
    assert not result.ok
    assert any(issue.code is IssueCode.INVALID_WEEK_BOUND for issue in result.issues)
    result_neg = parse_week_expression("-3周")
    assert not result_neg.ok
    assert any(issue.code is IssueCode.INVALID_WEEK_BOUND for issue in result_neg.issues)


def test_unparseable_segment_is_error() -> None:
    result = parse_week_expression("abc周")
    assert not result.ok
    assert any(issue.code is IssueCode.INVALID_WEEK_EXPRESSION for issue in result.issues)


def test_standalone_odd_even_modifiers() -> None:
    result = parse_week_expression("2-4单")
    assert result.ok
    assert result.ranges == [WeekRange(start=2, end=4, parity=WeekParity.ODD)]
    result_even = parse_week_expression("2-4双")
    assert result_even.ok
    assert result_even.ranges == [WeekRange(start=2, end=4, parity=WeekParity.EVEN)]


def test_empty_segment_is_error() -> None:
    result = parse_week_expression("1,,3周")
    assert not result.ok
    assert any(issue.code is IssueCode.EMPTY_WEEK_EXPRESSION for issue in result.issues)


def test_empty_expansion_warning() -> None:
    result = parse_week_expression("3双周")
    assert result.ok
    assert result.expanded_weeks() == []
    assert any(
        issue.code is IssueCode.EMPTY_WEEK_EXPANSION and issue.severity == "warning"
        for issue in result.issues
    )
    result_odd = parse_week_expression("2单周")
    assert result_odd.ok
    assert result_odd.expanded_weeks() == []
    assert any(
        issue.code is IssueCode.EMPTY_WEEK_EXPANSION and issue.severity == "warning"
        for issue in result_odd.issues
    )


def test_dan_shuang_zhou_unsupported() -> None:
    for text in ("1-16单双周", "单双周"):
        result = parse_week_expression(text)
        assert not result.ok
        assert any(
            issue.code is IssueCode.UNSUPPORTED_WEEK_PHRASE and issue.raw_text == text
            for issue in result.issues
        )


def test_expanded_weeks_sorted_unique() -> None:
    result = parse_week_expression("5-6,1-3")
    assert result.ok
    assert result.expanded_weeks() == [1, 2, 3, 5, 6]
