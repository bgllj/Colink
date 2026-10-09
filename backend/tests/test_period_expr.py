from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.period_expr import PeriodRange, parse_period_expression


def test_range_with_period_suffix() -> None:
    result = parse_period_expression("1-2节")
    assert result.ok
    assert result.range == PeriodRange(start=1, end=2)
    assert result.original_text == "1-2节"


def test_range_without_period_suffix() -> None:
    result = parse_period_expression("5-8")
    assert result.ok
    assert result.range == PeriodRange(start=5, end=8)


def test_single_period_with_suffix() -> None:
    result = parse_period_expression("3节")
    assert result.ok
    assert result.range == PeriodRange(start=3, end=3)


def test_arbitrary_positive_range() -> None:
    result = parse_period_expression("10-12节")
    assert result.ok
    assert result.range == PeriodRange(start=10, end=12)


def test_fullwidth_digits_and_hyphen() -> None:
    result = parse_period_expression("１-２节")
    assert result.ok
    assert result.range == PeriodRange(start=1, end=2)


def test_reversed_range_is_error() -> None:
    result = parse_period_expression("8-5节")
    assert not result.ok
    assert result.range is None
    assert any(issue.code is IssueCode.REVERSED_PERIOD_RANGE for issue in result.issues)


def test_empty_is_error() -> None:
    result = parse_period_expression("")
    assert not result.ok
    assert result.range is None
    assert any(issue.code is IssueCode.EMPTY_PERIOD_EXPRESSION for issue in result.issues)
    result_ws = parse_period_expression("   ")
    assert not result_ws.ok
    assert any(issue.code is IssueCode.EMPTY_PERIOD_EXPRESSION for issue in result_ws.issues)


def test_non_positive_is_error() -> None:
    for text in ("0节", "0-2节", "-1", "3-0"):
        result = parse_period_expression(text)
        assert not result.ok
        assert result.range is None
        assert any(issue.code is IssueCode.INVALID_PERIOD_EXPRESSION for issue in result.issues)


def test_unparseable_is_error() -> None:
    for text in ("a-b节", "1-2-3节", "第一二节"):
        result = parse_period_expression(text)
        assert not result.ok
        assert result.range is None
        assert any(issue.code is IssueCode.INVALID_PERIOD_EXPRESSION for issue in result.issues)
