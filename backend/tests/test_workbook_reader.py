import io
from pathlib import Path

import openpyxl
import pytest

from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.workbook import (
    WorkbookReadError,
    a1_coordinate,
    detect_format,
    read_workbook,
)

SAMPLE_PATH = Path(__file__).resolve().parents[2] / "samples" / "excel" / "25计科9(1).xls"

requires_sample = pytest.mark.skipif(
    not SAMPLE_PATH.is_file(),
    reason="本地样例课表未提供（samples/excel 不入库）",
)


@requires_sample
def test_detect_xls_magic_on_sample() -> None:
    data = SAMPLE_PATH.read_bytes()
    assert detect_format(data) == "xls"


def test_detect_xlsx_and_unknown_magic() -> None:
    assert detect_format(b"PK\x03\x04rest-of-zip") == "xlsx"
    assert detect_format(b"not-a-workbook") is None
    assert detect_format(b"") is None


@requires_sample
def test_read_sample_has_sheet1_and_title_at_row0() -> None:
    workbook = read_workbook(SAMPLE_PATH.read_bytes())
    assert "Sheet1" in workbook.sheets
    grid = workbook.sheets["Sheet1"]
    assert grid[0][1] == "成都文理学院2026-2027学年第一学期课表"
    assert "Sheet1" in workbook.merged
    assert workbook.merged["Sheet1"]


def test_a1_coordinate() -> None:
    assert a1_coordinate(0, 0) == "A1"
    assert a1_coordinate(2, 3) == "D3"
    assert a1_coordinate(0, 26) == "AA1"


def test_read_workbook_rejects_unsupported_bytes() -> None:
    with pytest.raises(WorkbookReadError) as exc_info:
        read_workbook(b"plain text, not a workbook")
    assert exc_info.value.issue.code == IssueCode.UNSUPPORTED_FILE_TYPE


def test_read_workbook_rejects_empty_bytes() -> None:
    with pytest.raises(WorkbookReadError) as exc_info:
        read_workbook(b"")
    assert exc_info.value.issue.code == IssueCode.MALFORMED_WORKBOOK


def test_read_synthetic_xlsx_round_trip() -> None:
    book = openpyxl.Workbook()
    sheet = book.active
    sheet.title = "Timetable"
    sheet["B1"] = "标题行"
    sheet["C3"] = "星期一"
    sheet.merge_cells("C3:D3")
    sheet["C4"] = "课程内容"
    buffer = io.BytesIO()
    book.save(buffer)
    book.close()

    workbook = read_workbook(buffer.getvalue())
    assert detect_format(buffer.getvalue()) == "xlsx"
    grid = workbook.sheets["Timetable"]
    assert grid[0][1] == "标题行"
    assert grid[2][2] == "星期一"
    assert grid[3][2] == "课程内容"
    assert (2, 3, 2, 4) in workbook.merged["Timetable"]
