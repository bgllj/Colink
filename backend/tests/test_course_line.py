from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.course_line import parse_course_line
from class_table_backend.parsing.period_expr import PeriodRange
from class_table_backend.parsing.week_expr import WeekParity, WeekRange


def test_full_course_line_with_room() -> None:
    line = "[133951]中国近现代史纲要 [2-4, 8-16双周][1-2节] 主教学楼B103"
    result = parse_course_line(line)
    assert result.ok
    assert result.raw_text == line
    assert result.course_code == "133951"
    assert result.course_name == "中国近现代史纲要"
    assert result.week_text == "2-4, 8-16双周"
    assert result.period_text == "1-2节"
    assert result.room_text == "主教学楼B103"
    assert result.week_result is not None and result.week_result.ok
    assert result.week_result.ranges == [
        WeekRange(start=2, end=4, parity=WeekParity.EVEN),
        WeekRange(start=8, end=16, parity=WeekParity.EVEN),
    ]
    assert result.period_result is not None and result.period_result.ok
    assert result.period_result.range == PeriodRange(start=1, end=2)
    assert result.issues == []


def test_leading_spaces_and_no_room() -> None:
    line = "              [193900]体育Ⅲ [2-16周][1-2节]"
    result = parse_course_line(line)
    assert result.ok
    assert result.raw_text == line
    assert result.course_code == "193900"
    assert result.course_name == "体育Ⅲ"
    assert result.week_text == "2-16周"
    assert result.period_text == "1-2节"
    assert result.room_text is None


def test_letter_course_code() -> None:
    line = "[FX3001004]财务管理Ⅰ（辅修） [1-5, 7-16周][5-7节] 主教学楼C404"
    result = parse_course_line(line)
    assert result.ok
    assert result.course_code == "FX3001004"
    assert result.course_name == "财务管理Ⅰ（辅修）"
    assert result.week_text == "1-5, 7-16周"
    assert result.period_text == "5-7节"
    assert result.room_text == "主教学楼C404"


def test_non_course_line_is_invalid() -> None:
    result = parse_course_line("注1：放假另行通知")
    assert not result.ok
    assert result.week_result is None
    assert result.period_result is None
    assert any(issue.code is IssueCode.INVALID_COURSE_LINE for issue in result.issues)


def test_line_index_preserved() -> None:
    result = parse_course_line("[193900]体育Ⅲ [2-16周][1-2节]", line_index=3)
    assert result.line_index == 3
    default = parse_course_line("[193900]体育Ⅲ [2-16周][1-2节]")
    assert default.line_index == 0


def test_fullwidth_brackets() -> None:
    line = "［193900］体育Ⅲ ［2-16周］［1-2节］"
    result = parse_course_line(line)
    assert result.ok
    assert result.course_code == "193900"
    assert result.course_name == "体育Ⅲ"
    assert result.week_text == "2-16周"
    assert result.period_text == "1-2节"


def test_week_and_period_issues_merged() -> None:
    line = "[193900]体育Ⅲ [单双周][8-5节]"
    result = parse_course_line(line)
    assert not result.ok
    codes = {issue.code for issue in result.issues}
    assert IssueCode.UNSUPPORTED_WEEK_PHRASE in codes
    assert IssueCode.REVERSED_PERIOD_RANGE in codes
