from datetime import date
from pathlib import Path

import pytest

from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.profiles.chengdu_wenli_v1 import extract_chengdu_wenli_v1
from class_table_backend.parsing.workbook import WorkbookData, read_workbook

SAMPLE_PATH = Path(__file__).resolve().parents[2] / "samples" / "excel" / "25计科9(1).xls"

requires_sample = pytest.mark.skipif(
    not SAMPLE_PATH.is_file(),
    reason="本地样例课表未提供（samples/excel 不入库）",
)


def _extract():
    workbook = read_workbook(SAMPLE_PATH.read_bytes())
    return extract_chengdu_wenli_v1(workbook)


@requires_sample
def test_metadata_from_sample() -> None:
    result = _extract()
    meta = result.meta
    assert meta.academic_year == "2026-2027"
    assert meta.semester_name == "第一学期"
    assert meta.start_date == date(2026, 8, 31)
    assert meta.title == "成都文理学院2026-2027学年第一学期课表"
    assert meta.class_name is not None and "计算机科学与技术9" in meta.class_name
    assert meta.department == "人工智能与大数据学院"
    assert meta.grade == "2025"
    assert meta.major == "计算机科学与技术"


@requires_sample
def test_occurrences_pe_course_and_provenance() -> None:
    result = _extract()
    assert result.occurrences
    pe = [o for o in result.occurrences if o.course_code == "193900"]
    assert len(pe) == 1
    sport = pe[0]
    assert sport.weekday == 1
    assert sport.period_start == 1
    assert sport.period_end == 2
    assert sport.daypart == "上午"
    assert sport.slot_label == "一"
    assert sport.coordinate
    assert sport.sheet == "Sheet1"
    assert sport.raw_line
    assert sport.ok


@requires_sample
def test_multi_room_course_has_three_rooms() -> None:
    result = _extract()
    finance = [o for o in result.occurrences if o.course_code == "FX3001004"]
    assert len(finance) >= 3
    rooms = {o.room_text for o in finance}
    assert any(room and "C404" in room for room in rooms)
    assert any(room and "C304" in room for room in rooms)
    assert any(room and "C301" in room for room in rooms)
    for occ in finance:
        assert occ.weekday == 3
        assert occ.period_start == 5
        assert occ.period_end == 7


@requires_sample
def test_history_week_expansion() -> None:
    result = _extract()
    history = [
        o
        for o in result.occurrences
        if o.course_code == "133951" and o.period_start == 1
    ]
    target = [o for o in history if o.week_text == "2-4, 8-16双周"]
    assert target, "expected the 2-4, 8-16双周 history occurrence"
    occ = target[0]
    assert set(occ.expanded_weeks()) == {2, 4, 8, 10, 12, 14, 16}
    # Sample grid places this line under the 星期二 column (anchor col 5).
    assert occ.weekday == 2
    assert occ.period_start == 1
    assert occ.period_end == 2


def test_missing_start_date_is_reported() -> None:
    workbook = WorkbookData(
        sheets={"Sheet1": [["成都文理学院2026-2027学年第一学期课表"]]},
        merged={"Sheet1": []},
    )
    result = extract_chengdu_wenli_v1(workbook)
    assert any(issue.code == IssueCode.MISSING_SEMESTER_START for issue in result.issues)
