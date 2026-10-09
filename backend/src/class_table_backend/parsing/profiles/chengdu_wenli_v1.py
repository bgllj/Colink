from __future__ import annotations

import re
from dataclasses import dataclass, replace
from datetime import date

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.domain.models import ParsedOccurrence, SemesterMeta
from class_table_backend.parsing.course_line import parse_course_line
from class_table_backend.parsing.profiles.base import ExtractionResult
from class_table_backend.parsing.week_expr import WeekRange
from class_table_backend.parsing.workbook import WorkbookData, a1_coordinate

_WEEKDAY_TOKEN_PATTERN = re.compile(
    r"(?:星期|周)(?P<day>[一二三四五六日天])"
)
_TITLE_PATTERN = re.compile(
    r"(?P<year>\d{4}\s*[-–—]\s*\d{4})\s*学年\s*(?P<semester>第\s*[一二三四五六七八九十\d]+\s*学期)"
)
_BARE_DATE_PATTERN = re.compile(
    r"^\s*(?P<year>\d{4})[-/](?P<month>\d{1,2})[-/](?P<day>\d{1,2})\s*$"
)

_WEEKDAY_BY_TOKEN = {
    "一": 1,
    "二": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
    "日": 7,
    "天": 7,
}

_SLOT_NUMERALS = {
    "一": 1,
    "二": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
}

_DAYPART_TOKENS = ("上午", "下午", "晚上", "晚间")

_DEPARTMENT_LABEL = re.compile(r"院\s*[（(]\s*系\s*[）)]|院系|院\(系\)")
_GRADE_LABEL = re.compile(r"年级")
_MAJOR_LABEL = re.compile(r"专业")
_CLASS_LABEL = re.compile(r"班级")
_LABEL_VALUE_SEPARATOR = re.compile(r"[：:]")


@dataclass
class _SheetLayout:
    header_row: int
    weekday_columns: dict[int, int]
    slot_column: int | None
    daypart_column: int | None
    slot_rows: list[tuple[int, int, str | None, str | None]]


def _cell_text(grid: list[list[str]], row: int, col: int) -> str:
    if row < 0 or row >= len(grid):
        return ""
    line = grid[row]
    if col < 0 or col >= len(line):
        return ""
    return line[col]


def _merged_anchor(
    merged: list[tuple[int, int, int, int]], row: int, col: int
) -> tuple[int, int]:
    for rlo, rhi, clo, chi in merged:
        if rlo <= row < rhi and clo <= col < chi:
            return rlo, clo
    return row, col


def _merged_span(
    merged: list[tuple[int, int, int, int]], row: int, col: int
) -> tuple[int, int]:
    """Return (first_col, last_col) covered by the merge containing (row, col)."""
    for rlo, rhi, clo, chi in merged:
        if rlo <= row < rhi and clo <= col < chi:
            return clo, chi - 1
    return col, col


def _compact(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def _parse_title(text: str) -> tuple[str | None, str | None, str | None]:
    match = _TITLE_PATTERN.search(text or "")
    if match is None:
        return None, None, None
    year = re.sub(r"\s+", "", match.group("year"))
    semester = re.sub(r"\s+", "", match.group("semester"))
    return year, semester, (text or "").strip()


def _parse_bare_date(text: str) -> date | None:
    match = _BARE_DATE_PATTERN.match(text or "")
    if match is None:
        return None
    try:
        return date(
            int(match.group("year")),
            int(match.group("month")),
            int(match.group("day")),
        )
    except ValueError:
        return None


def _label_value(grid: list[list[str]], row: int, col: int, raw: str) -> str | None:
    parts = _LABEL_VALUE_SEPARATOR.split(raw, maxsplit=1)
    if len(parts) == 2 and parts[1].strip():
        return parts[1].strip()
    for next_col in range(col + 1, len(grid[row])):
        candidate = _cell_text(grid, row, next_col).strip()
        if candidate:
            return candidate
    return None


def _extract_meta(grid: list[list[str]]) -> SemesterMeta:
    meta = SemesterMeta()
    for row in range(len(grid)):
        for col in range(len(grid[row])):
            raw = _cell_text(grid, row, col)
            if not raw or not raw.strip():
                continue

            year, semester, title = _parse_title(raw)
            if year and meta.academic_year is None:
                meta.academic_year = year
                meta.semester_name = semester
                meta.title = title

            bare = _parse_bare_date(raw)
            if bare is not None and meta.start_date is None:
                meta.start_date = bare

            compact = _compact(raw)
            if meta.department is None and _DEPARTMENT_LABEL.search(compact):
                # Label form "院(系)/部：" — value may follow the colon or sit rightward.
                value = _label_value(grid, row, col, raw)
                if value and not _GRADE_LABEL.search(value) and not _CLASS_LABEL.search(value):
                    meta.department = value
            if meta.grade is None and _GRADE_LABEL.search(compact):
                value = _label_value(grid, row, col, raw)
                if value:
                    meta.grade = value
            if meta.major is None and _MAJOR_LABEL.search(compact):
                value = _label_value(grid, row, col, raw)
                if value:
                    meta.major = value
            if meta.class_name is None and _CLASS_LABEL.search(compact):
                value = _label_value(grid, row, col, raw)
                if value:
                    meta.class_name = value
    return meta


def _find_weekday_columns(
    grid: list[list[str]], merged: list[tuple[int, int, int, int]]
) -> tuple[int, dict[int, int]] | None:
    for row in range(len(grid)):
        found: dict[int, int] = {}
        for col in range(len(grid[row])):
            compact = _compact(_cell_text(grid, row, col))
            match = _WEEKDAY_TOKEN_PATTERN.fullmatch(compact)
            if match is None:
                continue
            weekday = _WEEKDAY_BY_TOKEN[match.group("day")]
            span_start, _span_end = _merged_span(merged, row, col)
            found.setdefault(weekday, span_start)
        if found:
            return row, found
    return None


def _find_slot_column(
    grid: list[list[str]], header_row: int, max_weekday_col: int
) -> int | None:
    best_col: int | None = None
    best_score = 0
    for col in range(max(max_weekday_col, 0)):
        score = 0
        for row in range(header_row + 1, len(grid)):
            compact = _compact(_cell_text(grid, row, col))
            if compact in _SLOT_NUMERALS:
                score += 1
        if score > best_score:
            best_score = score
            best_col = col
    return best_col


def _find_daypart_column(
    grid: list[list[str]], merged: list[tuple[int, int, int, int]], slot_column: int | None
) -> int | None:
    best_col: int | None = None
    best_score = 0
    upper = slot_column if slot_column is not None else len(grid[0]) if grid else 0
    for col in range(max(upper, 0)):
        score = 0
        for row in range(len(grid)):
            anchor_row, anchor_col = _merged_anchor(merged, row, col)
            compact = _compact(_cell_text(grid, anchor_row, anchor_col))
            if compact in _DAYPART_TOKENS:
                score += 1
        if score > best_score:
            best_score = score
            best_col = col
    return best_col


def _daypart_fallback(slot_number: int) -> str | None:
    if slot_number in (1, 2):
        return "上午"
    if slot_number in (3, 4):
        return "下午"
    if slot_number in (5, 6):
        return "晚上"
    return None


def _sheet_layout(
    grid: list[list[str]], merged: list[tuple[int, int, int, int]]
) -> _SheetLayout | None:
    header = _find_weekday_columns(grid, merged)
    if header is None:
        return None
    header_row, weekday_columns = header
    max_weekday_col = max(weekday_columns.values())
    slot_column = _find_slot_column(grid, header_row, max_weekday_col)
    daypart_column = _find_daypart_column(grid, merged, slot_column)

    slot_rows: list[tuple[int, int, str | None, str | None]] = []
    for row in range(header_row + 1, len(grid)):
        slot_label: str | None = None
        slot_number: int | None = None
        if slot_column is not None:
            compact = _compact(_cell_text(grid, row, slot_column))
            if compact in _SLOT_NUMERALS:
                slot_label = compact
                slot_number = _SLOT_NUMERALS[compact]
        if slot_number is None:
            continue
        daypart: str | None = None
        if daypart_column is not None:
            anchor_row, anchor_col = _merged_anchor(merged, row, daypart_column)
            daypart = _compact(_cell_text(grid, anchor_row, anchor_col)) or None
        if not daypart:
            daypart = _daypart_fallback(slot_number)
        slot_rows.append((row, slot_number, daypart, slot_label))

    return _SheetLayout(
        header_row=header_row,
        weekday_columns=weekday_columns,
        slot_column=slot_column,
        daypart_column=daypart_column,
        slot_rows=slot_rows,
    )


def _occurrence_issues(
    issues: list[Issue], sheet: str, coordinate: str
) -> list[Issue]:
    return [
        replace(issue, sheet=sheet, coordinate=coordinate)
        if issue.sheet is None
        else issue
        for issue in issues
    ]


def extract_chengdu_wenli_v1(workbook: WorkbookData) -> ExtractionResult:
    result = ExtractionResult()
    recognized_any = False

    for sheet_name, grid in workbook.sheets.items():
        merged = workbook.merged.get(sheet_name, [])
        layout = _sheet_layout(grid, merged)
        sheet_meta = _extract_meta(grid)
        if result.meta.title is None:
            result.meta = sheet_meta
        else:
            for field_name in (
                "academic_year",
                "semester_name",
                "start_date",
                "department",
                "grade",
                "major",
                "class_name",
                "title",
            ):
                if getattr(result.meta, field_name) is None:
                    value = getattr(sheet_meta, field_name)
                    if value is not None:
                        setattr(result.meta, field_name, value)

        if layout is None:
            continue
        recognized_any = True

        for row, _slot_number, daypart, slot_label in layout.slot_rows:
            for weekday, col in sorted(layout.weekday_columns.items()):
                raw_cell = _cell_text(grid, row, col)
                if not raw_cell or not raw_cell.strip():
                    continue
                coordinate = a1_coordinate(row, col)
                for line_index, raw_line in enumerate(raw_cell.splitlines()):
                    if not raw_line.strip():
                        continue
                    parsed = parse_course_line(raw_line, line_index=line_index)
                    week_ranges: list[WeekRange] = []
                    if parsed.week_result is not None:
                        week_ranges = list(parsed.week_result.ranges)
                    period_range = None
                    if parsed.period_result is not None and parsed.period_result.range is not None:
                        period_range = parsed.period_result.range
                    issues = _occurrence_issues(parsed.issues, sheet_name, coordinate)
                    result.occurrences.append(
                        ParsedOccurrence(
                            course_code=parsed.course_code,
                            course_name=parsed.course_name,
                            weekday=weekday,
                            period_start=period_range.start if period_range else None,
                            period_end=period_range.end if period_range else None,
                            week_text=parsed.week_text,
                            week_ranges=week_ranges,
                            room_text=parsed.room_text,
                            sheet=sheet_name,
                            coordinate=coordinate,
                            line_index=line_index,
                            raw_line=raw_line,
                            daypart=daypart,
                            slot_label=slot_label,
                            issues=issues,
                        )
                    )
                    result.issues.extend(issues)

    if not recognized_any:
        result.issues.append(
            Issue(
                code=IssueCode.UNRECOGNIZED_LAYOUT,
                message="未能识别课表布局: 缺少星期表头行",
            )
        )
    if result.meta.start_date is None:
        result.issues.append(
            Issue(
                code=IssueCode.MISSING_SEMESTER_START,
                message="未找到开学日期 (期望无标签日期单元格, 例如 2026-08-31)",
            )
        )
    return result
