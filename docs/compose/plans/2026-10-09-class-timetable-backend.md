# Class Timetable Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `backend/` end-to-end: parse Chengdu Wenli `.xls` timetables, validate/preview imports, and persist confirmed schedules.

**Architecture:** Vertical slice under `backend/src/class_table_backend` with strict layering (`api` → `import_flow` → `parsing`/`domain`/`persistence`). Single source-layout profile `chengdu_wenli_v1`. Preview writes only import bookkeeping; confirm writes schedule rows in one transaction.

**Tech Stack:** Python 3.12, FastAPI, Pydantic v2, SQLAlchemy 2, Alembic, xlrd, openpyxl, pytest, httpx, ruff

**Spec:** `docs/compose/specs/2026-10-09-class-timetable-backend-design.md`

## Global Constraints

- Backend code lives only under `backend/`; repo root remains the project root.
- Never restore deleted Android/Gradle files; never rewrite `docs/里程碑任务清单.md`.
- Do not use `pandas.read_excel()` as the timetable grid parser.
- Week expression trailing `单周`/`双周` applies to the **entire listed set** (verified: `2-4,8-16双周` → weeks 2,4,8,10,12,14,16).
- Never hard-code example week bounds such as `1-9` or `1-16` in implementation code.
- Preview must not write `courses` / `meeting_occurrences` / `meeting_weeks`.
- Confirm is transactional and idempotent via file hash; never silently replace a confirmed schedule.
- Preserve raw week text + source coordinates on every parsed occurrence.
- User-facing messages/docs in Chinese; code identifiers/API fields in English.
- `DATABASE_URL` via environment; SQLite for local tests; no committed credentials.
- Real workbook fixture allowed: `excel样例/25计科9(1).xls` (relative to repo root).
- Run `pytest` and `ruff check` before claiming a task complete.

---

### Task 1: Project scaffold + week expression parser

**Covers:** S3, S5

**Files:**
- Create: `backend/pyproject.toml`
- Create: `backend/src/class_table_backend/__init__.py`
- Create: `backend/src/class_table_backend/parsing/__init__.py`
- Create: `backend/src/class_table_backend/parsing/normalize.py`
- Create: `backend/src/class_table_backend/parsing/week_expr.py`
- Create: `backend/src/class_table_backend/domain/__init__.py`
- Create: `backend/src/class_table_backend/domain/issues.py`
- Test: `backend/tests/test_normalize.py`
- Test: `backend/tests/test_week_expr.py`

**Interfaces:**
- Consumes: nothing (first task)
- Produces:
  - `normalize.normalize_expression_text(text: str) -> str`
  - `week_expr.WeekParity` enum (`ALL`, `ODD`, `EVEN`)
  - `week_expr.WeekRange` dataclass (`start: int`, `end: int`, `parity: WeekParity`)
  - `week_expr.WeekParseResult` dataclass (`original_text: str`, `ranges: list[WeekRange]`, `issues: list[Issue]`, `ok: bool`)
  - `week_expr.parse_week_expression(text: str) -> WeekParseResult`
  - `domain.issues.IssueCode`, `domain.issues.Issue`

- [ ] **Step 1: Create package scaffold and pyproject**

Create `backend/pyproject.toml`:

```toml
[project]
name = "class-table-backend"
version = "0.1.0"
description = "Class timetable import backend"
requires-python = ">=3.12"
dependencies = [
  "fastapi>=0.115",
  "uvicorn[standard]>=0.30",
  "pydantic>=2.7",
  "sqlalchemy>=2.0",
  "alembic>=1.13",
  "xlrd>=2.0.1",
  "openpyxl>=3.1",
  "python-multipart>=0.0.9",
]

[project.optional-dependencies]
dev = [
  "pytest>=8.2",
  "httpx>=0.27",
  "ruff>=0.5",
]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
testpaths = ["tests"]
pythonpath = ["src"]

[tool.ruff]
line-length = 100
target-version = "py312"

[tool.ruff.lint]
select = ["E", "F", "I", "UP"]
```

Create empty `__init__.py` files:

- `backend/src/class_table_backend/__init__.py`
- `backend/src/class_table_backend/parsing/__init__.py`
- `backend/src/class_table_backend/domain/__init__.py`

Create `backend/src/class_table_backend/domain/issues.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum


class IssueCode(StrEnum):
    EMPTY_WEEK_EXPRESSION = "EMPTY_WEEK_EXPRESSION"
    INVALID_WEEK_EXPRESSION = "INVALID_WEEK_EXPRESSION"
    UNSUPPORTED_WEEK_PHRASE = "UNSUPPORTED_WEEK_PHRASE"
    REVERSED_WEEK_RANGE = "REVERSED_WEEK_RANGE"
    INVALID_WEEK_BOUND = "INVALID_WEEK_BOUND"
    EMPTY_PERIOD_EXPRESSION = "EMPTY_PERIOD_EXPRESSION"
    INVALID_PERIOD_EXPRESSION = "INVALID_PERIOD_EXPRESSION"
    REVERSED_PERIOD_RANGE = "REVERSED_PERIOD_RANGE"
    INVALID_COURSE_LINE = "INVALID_COURSE_LINE"
    UNRECOGNIZED_LAYOUT = "UNRECOGNIZED_LAYOUT"
    MISSING_SEMESTER_START = "MISSING_SEMESTER_START"
    MISSING_MAX_WEEK = "MISSING_MAX_WEEK"
    WEEK_OUT_OF_RANGE = "WEEK_OUT_OF_RANGE"
    PERIOD_GRID_MISMATCH = "PERIOD_GRID_MISMATCH"
    UNSUPPORTED_FILE_TYPE = "UNSUPPORTED_FILE_TYPE"
    MALFORMED_WORKBOOK = "MALFORMED_WORKBOOK"
    DUPLICATE_CONFIRM = "DUPLICATE_CONFIRM"
    IMPORT_NOT_CONFIRMABLE = "IMPORT_NOT_CONFIRMABLE"


@dataclass(frozen=True, slots=True)
class Issue:
    code: IssueCode
    message: str
    severity: str = "error"  # "error" | "warning" | "info"
    sheet: str | None = None
    coordinate: str | None = None
    line_index: int | None = None
    raw_text: str | None = None
    details: dict[str, object] = field(default_factory=dict)
```

- [ ] **Step 2: Write failing tests for normalize + week parser**

Create `backend/tests/test_normalize.py`:

```python
from class_table_backend.parsing.normalize import normalize_expression_text


def test_strips_outer_whitespace():
    assert normalize_expression_text("  1-16周  ") == "1-16周"


def test_unifies_fullwidth_punctuation():
    assert normalize_expression_text("２－１６周") == "2-16周"
    assert normalize_expression_text("2－16周") == "2-16周"
    assert normalize_expression_text("2，8周") == "2,8周"


def test_keeps_cjk_parities():
    assert normalize_expression_text("1-16单周") == "1-16单周"
    assert normalize_expression_text("1-16双周") == "1-16双周"
```

Create `backend/tests/test_week_expr.py`:

```python
from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.week_expr import WeekParity, parse_week_expression


def expand(result) -> list[int]:
    weeks: list[int] = []
    for rng in result.ranges:
        for week in range(rng.start, rng.end + 1):
            if rng.parity is WeekParity.ALL:
                weeks.append(week)
            elif rng.parity is WeekParity.ODD and week % 2 == 1:
                weeks.append(week)
            elif rng.parity is WeekParity.EVEN and week % 2 == 0:
                weeks.append(week)
    return weeks


def test_single_week():
    result = parse_week_expression("15周")
    assert result.ok
    assert result.ranges == []
    assert expand(result) == [15] or result.ranges  # normalized below
    assert [(r.start, r.end, r.parity) for r in result.ranges] == [(15, 15, WeekParity.ALL)]


def test_range_all_weeks():
    result = parse_week_expression("2-16周")
    assert result.ok
    assert [(r.start, r.end, r.parity) for r in result.ranges] == [(2, 16, WeekParity.ALL)]


def test_disjoint_ranges_all_weeks():
    result = parse_week_expression("1-5, 7-16周")
    assert result.ok
    assert [(r.start, r.end, r.parity) for r in result.ranges] == [
        (1, 5, WeekParity.ALL),
        (7, 16, WeekParity.ALL),
    ]


def test_trailing_even_applies_to_whole_list():
    # Verified school rule: 2-4,8-16双周 -> 2,4,8,10,12,14,16
    result = parse_week_expression("2-4, 8-16双周")
    assert result.ok
    assert [(r.start, r.end, r.parity) for r in result.ranges] == [
        (2, 4, WeekParity.EVEN),
        (8, 16, WeekParity.EVEN),
    ]
    assert expand(result) == [2, 4, 8, 10, 12, 14, 16]


def test_trailing_even_with_single_and_range():
    result = parse_week_expression("2,6-16双周")
    assert result.ok
    assert expand(result) == [2, 6, 8, 10, 12, 14, 16]


def test_trailing_odd():
    result = parse_week_expression("1-16单周")
    assert result.ok
    assert expand(result) == [1, 3, 5, 7, 9, 11, 13, 15]


def test_mixed_disjoint_list():
    result = parse_week_expression("1,3周")
    assert result.ok
    assert expand(result) == [1, 3]


def test_reversed_range_is_error():
    result = parse_week_expression("16-2周")
    assert not result.ok
    assert any(i.code is IssueCode.REVERSED_WEEK_RANGE for i in result.issues)


def test_unsupported_phrase_is_flagged():
    result = parse_week_expression("隔周上课")
    assert not result.ok
    assert any(i.code is IssueCode.UNSUPPORTED_WEEK_PHRASE for i in result.issues)


def test_empty_input():
    result = parse_week_expression("   ")
    assert not result.ok
    assert any(i.code is IssueCode.EMPTY_WEEK_EXPRESSION for i in result.issues)


def test_original_text_preserved():
    raw = "2-4, 8-16双周"
    result = parse_week_expression(raw)
    assert result.original_text == raw


def test_no_hardcoded_bounds_random_range():
    result = parse_week_expression("3-11周")
    assert result.ok
    assert [(r.start, r.end, r.parity) for r in result.ranges] == [(3, 11, WeekParity.ALL)]
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd backend
python -m venv .venv
.venv/Scripts/python.exe -m pip install -e ".[dev]"
.venv/Scripts/python.exe -m pytest tests/test_normalize.py tests/test_week_expr.py -v
```

Expected: FAIL / collection errors (`ModuleNotFoundError`)

- [ ] **Step 4: Implement normalize + week parser**

Create `backend/src/class_table_backend/parsing/normalize.py`:

```python
from __future__ import annotations

import unicodedata

_PUNCT_MAP = str.maketrans(
    {
        "，": ",",
        ",": ",",
        "、": ",",
        "。": ".",
        "．": ".",
        "：": ":",
        "；": ";",
        "～": "-",
        "~": "-",
        "—": "-",
        "–": "-",
        "－": "-",
        "─": "-",
        "—": "-",
    }
)


def normalize_expression_text(text: str) -> str:
    if text is None:
        return ""
    normalized = unicodedata.normalize("NFKC", text)
    normalized = normalized.translate(_PUNCT_MAP)
    return normalized.strip()
```

Create `backend/src/class_table_backend/parsing/week_expr.py`:

```python
from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import StrEnum

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.parsing.normalize import normalize_expression_text


class WeekParity(StrEnum):
    ALL = "ALL"
    ODD = "ODD"
    EVEN = "EVEN"


@dataclass(frozen=True, slots=True)
class WeekRange:
    start: int
    end: int
    parity: WeekParity = WeekParity.ALL


@dataclass(slots=True)
class WeekParseResult:
    original_text: str
    ranges: list[WeekRange] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(i.severity == "error" for i in self.issues)


_SEGMENT_RE = re.compile(r"(\d+)(?:\s*-\s*(\d+))?")
_PARITY_SUFFIX_RE = re.compile(r"(单周|双周|单|双)$")
_UNSUPPORTED_RE = re.compile(r"隔周|前半学期|后半学期|按通知|节假日|调课|另行")


def parse_week_expression(text: str) -> WeekParseResult:
    original = text
    normalized = normalize_expression_text(text)
    result = WeekParseResult(original_text=original)
    if not normalized:
        result.issues.append(
            Issue(
                code=IssueCode.EMPTY_WEEK_EXPRESSION,
                message="周次表达式为空",
                severity="error",
                raw_text=original,
            )
        )
        return result

    if _UNSUPPORTED_RE.search(normalized):
        result.issues.append(
            Issue(
                code=IssueCode.UNSUPPORTED_WEEK_PHRASE,
                message=f"不支持的周次用语: {normalized}",
                severity="error",
                raw_text=original,
            )
        )
        return result

    working = normalized
    if working.endswith("周"):
        working = working[:-1]

    parity = WeekParity.ALL
    parity_match = _PARITY_SUFFIX_RE.search(working)
    if parity_match:
        token = parity_match.group(1)
        parity = WeekParity.ODD if token.startswith("单") else WeekParity.EVEN
        working = working[: parity_match.start()]

    working = working.strip()
    if not working:
        result.issues.append(
            Issue(
                code=IssueCode.INVALID_WEEK_EXPRESSION,
                message=f"无法解析周次表达式: {normalized}",
                severity="error",
                raw_text=original,
            )
        )
        return result

    segments = [part.strip() for part in working.split(",") if part.strip()]
    if not segments:
        result.issues.append(
            Issue(
                code=IssueCode.INVALID_WEEK_EXPRESSION,
                message=f"无法解析周次表达式: {normalized}",
                severity="error",
                raw_text=original,
            )
        )
        return result

    parsed: list[WeekRange] = []
    for segment in segments:
        match = _SEGMENT_RE.fullmatch(segment)
        if not match:
            result.issues.append(
                Issue(
                    code=IssueCode.INVALID_WEEK_EXPRESSION,
                    message=f"无法解析周次片段: {segment}",
                    severity="error",
                    raw_text=original,
                )
            )
            continue
        start = int(match.group(1))
        end = int(match.group(2)) if match.group(2) else start
        if start < 1 or end < 1:
            result.issues.append(
                Issue(
                    code=IssueCode.INVALID_WEEK_BOUND,
                    message=f"周次必须为正整数: {segment}",
                    severity="error",
                    raw_text=original,
                )
            )
            continue
        if end < start:
            result.issues.append(
                Issue(
                    code=IssueCode.REVERSED_WEEK_RANGE,
                    message=f"周次区间起点大于终点: {segment}",
                    severity="error",
                    raw_text=original,
                )
            )
            continue
        parsed.append(WeekRange(start=start, end=end, parity=parity))

    if not any(i.severity == "error" for i in result.issues):
        result.ranges = parsed
    return result
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_normalize.py tests/test_week_expr.py -v
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: all PASS; ruff clean (fix unused imports if any)

- [ ] **Step 6: Commit**

```bash
git add backend/pyproject.toml backend/src backend/tests
git commit -m "feat(backend): scaffold package and week expression parser"
```

---

### Task 2: Period expression + course line parser

**Covers:** S4, S5

**Files:**
- Create: `backend/src/class_table_backend/parsing/period_expr.py`
- Create: `backend/src/class_table_backend/parsing/course_line.py`
- Test: `backend/tests/test_period_expr.py`
- Test: `backend/tests/test_course_line.py`

**Interfaces:**
- Consumes: `week_expr.parse_week_expression`, `WeekParseResult`, `Issue`, `IssueCode`
- Produces:
  - `period_expr.PeriodRange` (`start: int`, `end: int`)
  - `period_expr.PeriodParseResult` (`original_text: str`, `range: PeriodRange | None`, `issues: list[Issue]`, `ok: bool`)
  - `period_expr.parse_period_expression(text: str) -> PeriodParseResult`
  - `course_line.ParsedCourseLine` (`course_code`, `course_name`, `week_text`, `period_text`, `room_text`, `week_result`, `period_result`, `raw_text`, `line_index`)
  - `course_line.parse_course_line(raw_line: str, *, line_index: int = 0) -> ParsedCourseLine`

- [ ] **Step 1: Write failing tests for periods**

Create `backend/tests/test_period_expr.py`:

```python
from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.period_expr import parse_period_expression


def test_simple_range():
    result = parse_period_expression("1-2节")
    assert result.ok
    assert result.range is not None
    assert (result.range.start, result.range.end) == (1, 2)


def test_range_without_suffix():
    result = parse_period_expression("5-8")
    assert result.ok
    assert (result.range.start, result.range.end) == (5, 8)


def test_single_period():
    result = parse_period_expression("3节")
    assert result.ok
    assert (result.range.start, result.range.end) == (3, 3)


def test_reversed_period_range():
    result = parse_period_expression("8-5节")
    assert not result.ok
    assert any(i.code is IssueCode.REVERSED_PERIOD_RANGE for i in result.issues)


def test_empty_period():
    result = parse_period_expression("")
    assert not result.ok
    assert any(i.code is IssueCode.EMPTY_PERIOD_EXPRESSION for i in result.issues)
```

- [ ] **Step 2: Run period tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_period_expr.py -v
```

Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: Implement period parser**

Create `backend/src/class_table_backend/parsing/period_expr.py`:

```python
from __future__ import annotations

import re
from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.parsing.normalize import normalize_expression_text


@dataclass(frozen=True, slots=True)
class PeriodRange:
    start: int
    end: int


@dataclass(slots=True)
class PeriodParseResult:
    original_text: str
    range: PeriodRange | None = None
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.range is not None and not any(i.severity == "error" for i in self.issues)


_PERIOD_RE = re.compile(r"(\d+)(?:\s*-\s*(\d+))?")


def parse_period_expression(text: str) -> PeriodParseResult:
    original = text
    normalized = normalize_expression_text(text)
    result = PeriodParseResult(original_text=original)
    if not normalized:
        result.issues.append(
            Issue(
                code=IssueCode.EMPTY_PERIOD_EXPRESSION,
                message="节次表达式为空",
                severity="error",
                raw_text=original,
            )
        )
        return result

    working = normalized[:-1] if normalized.endswith("节") else normalized
    working = working.strip()
    match = _PERIOD_RE.fullmatch(working)
    if not match:
        result.issues.append(
            Issue(
                code=IssueCode.INVALID_PERIOD_EXPRESSION,
                message=f"无法解析节次表达式: {normalized}",
                severity="error",
                raw_text=original,
            )
        )
        return result

    start = int(match.group(1))
    end = int(match.group(2)) if match.group(2) else start
    if start < 1 or end < 1:
        result.issues.append(
            Issue(
                code=IssueCode.INVALID_PERIOD_EXPRESSION,
                message=f"节次必须为正整数: {normalized}",
                severity="error",
                raw_text=original,
            )
        )
        return result
    if end < start:
        result.issues.append(
            Issue(
                code=IssueCode.REVERSED_PERIOD_RANGE,
                message=f"节次区间起点大于终点: {normalized}",
                severity="error",
                raw_text=original,
            )
        )
        return result

    result.range = PeriodRange(start=start, end=end)
    return result
```

- [ ] **Step 4: Write failing tests for course lines**

Create `backend/tests/test_course_line.py`:

```python
from class_table_backend.domain.issues import IssueCode
from class_table_backend.parsing.course_line import parse_course_line


def test_course_with_room():
    raw = "[133951]中国近现代史纲要 [2-4, 8-16双周][1-2节] 主教学楼B103"
    parsed = parse_course_line(raw, line_index=0)
    assert parsed.course_code == "133951"
    assert parsed.course_name == "中国近现代史纲要"
    assert parsed.week_text == "2-4, 8-16双周"
    assert parsed.period_text == "1-2节"
    assert parsed.room_text == "主教学楼B103"
    assert parsed.ok


def test_course_without_room_and_leading_spaces():
    raw = "              [193900]体育Ⅲ [2-16周][1-2节]"
    parsed = parse_course_line(raw)
    assert parsed.course_code == "193900"
    assert parsed.course_name == "体育Ⅲ"
    assert parsed.room_text == ""
    assert parsed.ok


def test_course_code_with_letters():
    raw = "[FX3001004]财务管理Ⅰ（辅修） [1-5, 7-16周][5-7节] 主教学楼C404"
    parsed = parse_course_line(raw)
    assert parsed.course_code == "FX3001004"
    assert parsed.course_name == "财务管理Ⅰ（辅修）"
    assert parsed.ok


def test_unrecognized_line():
    parsed = parse_course_line("注1：放假另行通知")
    assert not parsed.ok
    assert any(i.code is IssueCode.INVALID_COURSE_LINE for i in parsed.issues)


def test_multiline_caller_uses_line_index():
    parsed = parse_course_line("[17302012]数据结构与算法 [1-16单周][3-4节] 实验楼114", line_index=2)
    assert parsed.line_index == 2
    assert parsed.ok
```

- [ ] **Step 5: Run course line tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_course_line.py -v
```

Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 6: Implement course line parser**

Create `backend/src/class_table_backend/parsing/course_line.py`:

```python
from __future__ import annotations

import re
from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.parsing.normalize import normalize_expression_text
from class_table_backend.parsing.period_expr import PeriodParseResult, parse_period_expression
from class_table_backend.parsing.week_expr import WeekParseResult, parse_week_expression

_COURSE_RE = re.compile(
    r"^[\[\[](?P<code>[^\]]+)[\]]\s*(?P<name>.+?)\s*"
    r"[\[\[](?P<weeks>[^\]]+)[\]]\s*"
    r"[\[\[](?P<periods>[^\]]+)[\]]\s*"
    r"(?P<room>.*)$"
)


@dataclass(slots=True)
class ParsedCourseLine:
    raw_text: str
    line_index: int = 0
    course_code: str = ""
    course_name: str = ""
    week_text: str = ""
    period_text: str = ""
    room_text: str = ""
    week_result: WeekParseResult | None = None
    period_result: PeriodParseResult | None = None
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        if any(i.severity == "error" for i in self.issues):
            return False
        return bool(self.week_result and self.week_result.ok and self.period_result and self.period_result.ok)


def parse_course_line(raw_line: str, *, line_index: int = 0) -> ParsedCourseLine:
    original = raw_line
    normalized = normalize_expression_text(raw_line)
    parsed = ParsedCourseLine(raw_text=original, line_index=line_index)
    if not normalized:
        parsed.issues.append(
            Issue(
                code=IssueCode.INVALID_COURSE_LINE,
                message="空的课程行",
                severity="error",
                raw_text=original,
                line_index=line_index,
            )
        )
        return parsed

    match = _COURSE_RE.match(normalized)
    if not match:
        parsed.issues.append(
            Issue(
                code=IssueCode.INVALID_COURSE_LINE,
                message=f"无法识别课程行: {normalized}",
                severity="error",
                raw_text=original,
                line_index=line_index,
            )
        )
        return parsed

    parsed.course_code = match.group("code").strip()
    parsed.course_name = match.group("name").strip()
    parsed.week_text = match.group("weeks").strip()
    parsed.period_text = match.group("periods").strip()
    parsed.room_text = match.group("room").strip()
    parsed.week_result = parse_week_expression(parsed.week_text)
    parsed.period_result = parse_period_expression(parsed.period_text)
    for issue in parsed.week_result.issues + parsed.period_result.issues:
        parsed.issues.append(issue)
    return parsed
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_period_expr.py tests/test_course_line.py -v
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: all PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/class_table_backend/parsing backend/tests
git commit -m "feat(backend): parse period expressions and course cell lines"
```

---

### Task 3: Workbook reader + chengdu_wenli_v1 profile

**Covers:** S4, S8

**Files:**
- Create: `backend/src/class_table_backend/parsing/workbook.py`
- Create: `backend/src/class_table_backend/parsing/profiles/__init__.py`
- Create: `backend/src/class_table_backend/parsing/profiles/base.py`
- Create: `backend/src/class_table_backend/parsing/profiles/chengdu_wenli_v1.py`
- Create: `backend/src/class_table_backend/domain/models.py`
- Test: `backend/tests/test_chengdu_wenli_v1_profile.py`
- Test: `backend/tests/test_workbook_reader.py`

**Interfaces:**
- Consumes: `parse_course_line`, `parse_week_expression`, `Issue`, `IssueCode`
- Produces:
  - `workbook.WorkbookCell` (`sheet`, `row`, `col`, `coordinate`, `value`)
  - `workbook.WorkbookData` (`sheets: dict[str, list[list[str]]]`, `merged: dict[str, list[tuple[int,int,int,int]]]`)
  - `workbook.detect_format(data: bytes) -> Literal["xls", "xlsx"] | None`
  - `workbook.read_workbook(data: bytes) -> WorkbookData`
  - `profiles.base.ExtractedOccurrence`, `profiles.base.ExtractionResult`
  - `profiles.chengdu_wenli_v1.extract_chengdu_wenli_v1(workbook: WorkbookData) -> ExtractionResult`
  - `domain.models.SemesterMeta`, `domain.models.ParsedOccurrence`

- [ ] **Step 1: Define domain extraction models**

Create `backend/src/class_table_backend/domain/models.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue
from class_table_backend.parsing.week_expr import WeekRange


@dataclass(slots=True)
class SemesterMeta:
    academic_year: str | None = None
    semester_name: str | None = None
    start_date: str | None = None  # ISO date string
    department: str | None = None
    grade: str | None = None
    major: str | None = None
    class_name: str | None = None
    title: str | None = None


@dataclass(slots=True)
class ParsedOccurrence:
    course_code: str
    course_name: str
    weekday: int  # 1=Monday .. 7=Sunday
    period_start: int
    period_end: int
    week_text: str
    week_ranges: list[WeekRange]
    room_text: str
    sheet: str
    coordinate: str
    line_index: int
    raw_line: str
    daypart: str | None = None  # 上午/下午/晚上
    slot_label: str | None = None
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(i.severity == "error" for i in self.issues)
```

- [ ] **Step 2: Write failing workbook reader tests**

Create `backend/tests/test_workbook_reader.py`:

```python
from pathlib import Path

from class_table_backend.parsing.workbook import detect_format, read_workbook

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = ROOT / "excel样例" / "25计科9(1).xls"


def test_detect_xls_magic():
    data = SAMPLE.read_bytes()
    assert detect_format(data) == "xls"


def test_read_sample_workbook():
    data = SAMPLE.read_bytes()
    wb = read_workbook(data)
    assert "Sheet1" in wb.sheets
    grid = wb.sheets["Sheet1"]
    assert grid[0][1].startswith("成都文理学院")
```

- [ ] **Step 3: Run workbook reader tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_workbook_reader.py -v
```

Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 4: Implement workbook reader**

Create `backend/src/class_table_backend/parsing/workbook.py`:

```python
from __future__ import annotations

import io
from dataclasses import dataclass, field
from typing import Literal

from class_table_backend.domain.issues import Issue, IssueCode


@dataclass(slots=True)
class WorkbookData:
    sheets: dict[str, list[list[str]]] = field(default_factory=dict)
    merged: dict[str, list[tuple[int, int, int, int]]] = field(default_factory=dict)


def detect_format(data: bytes) -> Literal["xls", "xlsx"] | None:
    if data.startswith(b"\xd0\xcf\x11\xe0"):
        return "xls"
    if data.startswith(b"PK\x03\x04"):
        return "xlsx"
    return None


def read_workbook(data: bytes) -> WorkbookData:
    kind = detect_format(data)
    if kind is None:
        raise ValueError(
            Issue(
                code=IssueCode.UNSUPPORTED_FILE_TYPE,
                message="无法识别的文件格式",
                severity="error",
            )
        )
    if kind == "xls":
        return _read_xls(data)
    return _read_xlsx(data)


def _cell_to_str(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _read_xls(data: bytes) -> WorkbookData:
    import xlrd

    try:
        book = xlrd.open_workbook(file_contents=data, formatting_info=True)
    except Exception as exc:  # noqa: BLE001 - surface as malformed workbook
        raise ValueError(
            Issue(
                code=IssueCode.MALFORMED_WORKBOOK,
                message=f"无法读取 xls 工作簿: {exc}",
                severity="error",
            )
        ) from exc

    wb = WorkbookData()
    for sheet_name in book.sheet_names():
        sheet = book.sheet_by_name(sheet_name)
        grid: list[list[str]] = []
        for r in range(sheet.nrows):
            row = [_cell_to_str(sheet.cell_value(r, c)) for c in range(sheet.ncols)]
            grid.append(row)
        wb.sheets[sheet_name] = grid
        wb.merged[sheet_name] = [
            (rlo, rhi, clo, chi) for (rlo, rhi, clo, chi) in getattr(sheet, "merged_cells", [])
        ]
    return wb


def _read_xlsx(data: bytes) -> WorkbookData:
    from openpyxl import load_workbook

    try:
        book = load_workbook(io.BytesIO(data), read_only=False, data_only=True)
    except Exception as exc:  # noqa: BLE001
        raise ValueError(
            Issue(
                code=IssueCode.MALFORMED_WORKBOOK,
                message=f"无法读取 xlsx 工作簿: {exc}",
                severity="error",
            )
        ) from exc

    wb = WorkbookData()
    for sheet_name in book.sheetnames:
        sheet = book[sheet_name]
        grid: list[list[str]] = []
        for row in sheet.iter_rows(values_only=True):
            grid.append([_cell_to_str(v) for v in row])
        wb.sheets[sheet_name] = grid
        merged: list[tuple[int, int, int, int]] = []
        for rng in sheet.merged_cells.ranges:
            # openpyxl ranges are 1-based inclusive; normalize to 0-based half-open
            merged.append((rng.min_row - 1, rng.max_row, rng.min_col - 1, rng.max_col))
        wb.merged[sheet_name] = merged
    return wb
```

- [ ] **Step 5: Write failing profile tests**

Create `backend/tests/test_chengdu_wenli_v1_profile.py`:

```python
from pathlib import Path

from class_table_backend.parsing.profiles.chengdu_wenli_v1 import extract_chengdu_wenli_v1
from class_table_backend.parsing.workbook import read_workbook

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = ROOT / "excel样例" / "25计科9(1).xls"


def test_extract_metadata_and_courses():
    wb = read_workbook(SAMPLE.read_bytes())
    result = extract_chengdu_wenli_v1(wb)

    assert result.meta.academic_year == "2026-2027"
    assert result.meta.semester_name == "第一学期"
    assert result.meta.start_date == "2026-08-31"
    assert result.meta.class_name is not None
    assert "计算机科学与技术9" in (result.meta.class_name or "")

    assert result.occurrences
    sports = [o for o in result.occurrences if o.course_code == "193900"]
    assert sports, "expected 体育Ⅲ occurrence"
    occ = sports[0]
    assert occ.weekday == 1
    assert occ.period_start == 1 and occ.period_end == 2
    assert occ.raw_line.startswith("[193900]")
    assert occ.coordinate  # provenance preserved

    # multi-room course keeps all lines
    fin = [o for o in result.occurrences if o.course_code == "FX3001004"]
    assert len(fin) >= 3
    rooms = {o.room_text for o in fin}
    assert "主教学楼C404" in rooms
    assert "主教学楼C304" in rooms
    assert "主教学楼C301" in rooms


def test_week_parity_from_sample():
    wb = read_workbook(SAMPLE.read_bytes())
    result = extract_chengdu_wenli_v1(wb)
    history = [
        o
        for o in result.occurrences
        if o.course_code == "133951" and o.weekday == 1 and o.period_start == 1
    ]
    assert history
    # 2-4, 8-16双周 on Monday morning
    weeks: set[int] = set()
    for rng in history[0].week_ranges:
        for week in range(rng.start, rng.end + 1):
            if rng.parity.value == "ALL":
                weeks.add(week)
            elif rng.parity.value == "ODD" and week % 2 == 1:
                weeks.add(week)
            elif rng.parity.value == "EVEN" and week % 2 == 0:
                weeks.add(week)
    assert weeks == {2, 4, 8, 10, 12, 14, 16}
```

- [ ] **Step 6: Implement chengdu_wenli_v1 profile**

Create `backend/src/class_table_backend/parsing/profiles/base.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue
from class_table_backend.domain.models import ParsedOccurrence, SemesterMeta


@dataclass(slots=True)
class ExtractionResult:
    meta: SemesterMeta
    occurrences: list[ParsedOccurrence] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
```

Create `backend/src/class_table_backend/parsing/profiles/__init__.py`:

```python
```

Create `backend/src/class_table_backend/parsing/profiles/chengdu_wenli_v1.py`:

```python
from __future__ import annotations

import re

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.domain.models import ParsedOccurrence, SemesterMeta
from class_table_backend.parsing.course_line import parse_course_line
from class_table_backend.parsing.profiles.base import ExtractionResult
from class_table_backend.parsing.workbook import WorkbookData

_WEEKDAY_MAP = {
    "星期一": 1,
    "星期二": 2,
    "星期三": 3,
    "星期四": 4,
    "星期五": 5,
    "星期六": 6,
    "星期日": 7,
    "星期天": 7,
}

_SLOT_TO_DAYPART = {
    "一": "上午",
    "二": "上午",
    "三": "下午",
    "四": "下午",
    "五": "晚上",
    "六": "晚上",
}

_YEAR_SEMESTER_RE = re.compile(r"(?P<year>\d{4}-\d{4})学年(?P<sem>第[一二三四1-4]学期)")
_DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}")


def extract_chengdu_wenli_v1(workbook: WorkbookData) -> ExtractionResult:
    issues: list[Issue] = []
    if not workbook.sheets:
        issues.append(
            Issue(code=IssueCode.UNRECOGNIZED_LAYOUT, message="工作簿没有工作表", severity="error")
        )
        return ExtractionResult(meta=SemesterMeta(), issues=issues)

    sheet_name = next(iter(workbook.sheets))
    grid = workbook.sheets[sheet_name]
    meta = SemesterMeta(title=_find_title(grid))
    if meta.title:
        m = _YEAR_SEMESTER_RE.search(meta.title)
        if m:
            meta.academic_year = m.group("year")
            meta.semester_name = m.group("sem")
    meta.start_date = _find_start_date(grid)
    if not meta.start_date:
        issues.append(
            Issue(
                code=IssueCode.MISSING_SEMESTER_START,
                message="未解析到学期开学日期",
                severity="error",
                sheet=sheet_name,
            )
        )
    _fill_org_meta(grid, meta)

    weekday_by_col = _map_weekday_columns(grid)
    if not weekday_by_col:
        issues.append(
            Issue(
                code=IssueCode.UNRECOGNIZED_LAYOUT,
                message="未识别到星期表头",
                severity="error",
                sheet=sheet_name,
            )
        )
        return ExtractionResult(meta=meta, issues=issues)

    slot_rows = _map_slot_rows(grid)
    occurrences: list[ParsedOccurrence] = []
    for row_idx, slot in slot_rows.items():
        for col_idx, weekday in weekday_by_col.items():
            if col_idx >= len(grid[row_idx]):
                continue
            cell = grid[row_idx][col_idx]
            if not cell or not cell.strip():
                continue
            for line_index, raw_line in enumerate(cell.splitlines()):
                if not raw_line.strip():
                    continue
                parsed = parse_course_line(raw_line, line_index=line_index)
                for issue in parsed.issues:
                    issues.append(
                        Issue(
                            code=issue.code,
                            message=issue.message,
                            severity=issue.severity,
                            sheet=sheet_name,
                            coordinate=_coord(row_idx, col_idx),
                            line_index=line_index,
                            raw_text=raw_line,
                        )
                    )
                if not parsed.ok or parsed.week_result is None or parsed.period_result is None:
                    # still record a shell occurrence for visibility
                    if parsed.course_code or parsed.course_name:
                        occurrences.append(
                            ParsedOccurrence(
                                course_code=parsed.course_code,
                                course_name=parsed.course_name,
                                weekday=weekday,
                                period_start=0,
                                period_end=0,
                                week_text=parsed.week_text,
                                week_ranges=[],
                                room_text=parsed.room_text,
                                sheet=sheet_name,
                                coordinate=_coord(row_idx, col_idx),
                                line_index=line_index,
                                raw_line=raw_line,
                                daypart=slot[1],
                                slot_label=slot[0],
                                issues=[
                                    Issue(
                                        code=i.code,
                                        message=i.message,
                                        severity=i.severity,
                                        sheet=sheet_name,
                                        coordinate=_coord(row_idx, col_idx),
                                        line_index=line_index,
                                        raw_text=raw_line,
                                    )
                                    for i in parsed.issues
                                ],
                            )
                        )
                    continue
                period = parsed.period_result.range
                assert period is not None
                occurrences.append(
                    ParsedOccurrence(
                        course_code=parsed.course_code,
                        course_name=parsed.course_name,
                        weekday=weekday,
                        period_start=period.start,
                        period_end=period.end,
                        week_text=parsed.week_text,
                        week_ranges=list(parsed.week_result.ranges),
                        room_text=parsed.room_text,
                        sheet=sheet_name,
                        coordinate=_coord(row_idx, col_idx),
                        line_index=line_index,
                        raw_line=raw_line,
                        daypart=slot[1],
                        slot_label=slot[0],
                    )
                )
    return ExtractionResult(meta=meta, occurrences=occurrences, issues=issues)


def _find_title(grid: list[list[str]]) -> str | None:
    for row in grid:
        for cell in row:
            if cell and "课表" in cell and "学年" in cell:
                return cell.strip()
    return None


def _find_start_date(grid: list[list[str]]) -> str | None:
    for row in grid:
        for cell in row:
            if not cell:
                continue
            m = _DATE_RE.search(cell.strip())
            if m and len(cell.strip()) == len(m.group(0)):
                return m.group(0)
    return None


def _fill_org_meta(grid: list[list[str]], meta: SemesterMeta) -> None:
    for row in grid:
        for cell in row:
            text = (cell or "").strip()
            if not text:
                continue
            if text.startswith("院") and "：" in text or text.startswith("院(系)/部"):
                # label row may hold values in adjacent cells handled below
                pass
            if "年级：" in text:
                meta.grade = text.split("年级：", 1)[1].strip() or meta.grade
            if "班级：" in text:
                meta.class_name = text.split("班级：", 1)[1].strip() or meta.class_name
            if text.startswith("专业："):
                rest = text.split("专业：", 1)[1].strip()
                if rest:
                    meta.major = rest
            if "计算机科学与技术" in text and meta.class_name is None and "班级" not in text:
                # value cell next to 专业 label; keep as major candidate
                meta.major = meta.major or text
    # targeted pass for label/value pairs in first metadata rows
    for row in grid[:3]:
        joined = [c.strip() for c in row if c and c.strip()]
        for i, cell in enumerate(joined):
            if cell.startswith("院"):
                for nxt in joined[i + 1 :]:
                    if nxt and not nxt.endswith("：") and "年级" not in nxt and "专业" not in nxt:
                        meta.department = nxt
                        break
            if cell.startswith("专业：") and len(cell) > 3:
                meta.major = cell.split("专业：", 1)[1].strip() or meta.major
            if cell.startswith("专业：") or cell == "专业：":
                for nxt in joined[i + 1 :]:
                    if nxt and not nxt.endswith("：") and "年级" not in nxt and "班级" not in nxt:
                        meta.major = meta.major or nxt
                        break


def _map_weekday_columns(grid: list[list[str]]) -> dict[int, int]:
    mapping: dict[int, int] = {}
    for row_idx, row in enumerate(grid):
        for col_idx, cell in enumerate(row):
            text = (cell or "").strip()
            if text in _WEEKDAY_MAP:
                mapping[col_idx] = _WEEKDAY_MAP[text]
        if mapping:
            break
    return mapping


def _map_slot_rows(grid: list[list[str]]) -> dict[int, tuple[str, str]]:
    slots: dict[int, tuple[str, str]] = {}
    for row_idx, row in enumerate(grid):
        if len(row) < 3:
            continue
        label = (row[2] or "").strip()
        if label in _SLOT_TO_DAYPART:
            slots[row_idx] = (label, _SLOT_TO_DAYPART[label])
    return slots


def _coord(row_idx: int, col_idx: int) -> str:
    # spreadsheet-style A1 coordinates (0-based row/col -> 1-based letters)
    n = col_idx + 1
    letters = ""
    while n:
        n, rem = divmod(n - 1, 26)
        letters = chr(65 + rem) + letters
    return f"{letters}{row_idx + 1}"
```

- [ ] **Step 7: Run profile tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_workbook_reader.py tests/test_chengdu_wenli_v1_profile.py -v
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: PASS. If column mapping differs due to merges, adjust `_map_weekday_columns` / `_map_slot_rows` to use the header row containing `星期*` and the period-label column (`一`…`六`) only — do not invent new course rules.

- [ ] **Step 8: Commit**

```bash
git add backend/src backend/tests
git commit -m "feat(backend): read workbooks and extract chengdu_wenli_v1 layout"
```

---

### Task 4: Validation + max_week derivation

**Covers:** S4, S5, S6

**Files:**
- Create: `backend/src/class_table_backend/domain/validation.py`
- Test: `backend/tests/test_validation.py`

**Interfaces:**
- Consumes: `ParsedOccurrence`, `SemesterMeta`, `WeekRange`, `Issue`
- Produces:
  - `validation.ValidationConfig` (`max_week: int | None = None`)
  - `validation.validate_extraction(result: ExtractionResult, config: ValidationConfig) -> tuple[list[ParsedOccurrence], list[Issue], int]`  
    returns `(occurrences_with_row_issues, all_issues, effective_max_week)`

- [ ] **Step 1: Write failing validation tests**

Create `backend/tests/test_validation.py`:

```python
from class_table_backend.domain.models import ParsedOccurrence, SemesterMeta
from class_table_backend.domain.validation import ValidationConfig, validate_extraction
from class_table_backend.parsing.profiles.base import ExtractionResult
from class_table_backend.parsing.week_expr import WeekParity, WeekRange


def make_occ(week_ranges, period_start=1, period_end=2, daypart="上午"):
    return ParsedOccurrence(
        course_code="C1",
        course_name="Course",
        weekday=1,
        period_start=period_start,
        period_end=period_end,
        week_text="raw",
        week_ranges=week_ranges,
        room_text="",
        sheet="Sheet1",
        coordinate="D4",
        line_index=0,
        raw_line="raw",
        daypart=daypart,
    )


def test_max_week_defaults_to_upper_bound():
    occ = make_occ([WeekRange(1, 5, WeekParity.ALL), WeekRange(7, 16, WeekParity.ALL)])
    extraction = ExtractionResult(meta=SemesterMeta(), occurrences=[occ])
    _, issues, max_week = validate_extraction(extraction, ValidationConfig())
    assert max_week == 16
    assert not any(i.severity == "error" for i in issues)


def test_max_week_override_and_out_of_range():
    occ = make_occ([WeekRange(1, 17, WeekParity.ALL)])
    extraction = ExtractionResult(meta=SemesterMeta(), occurrences=[occ])
    _, issues, max_week = validate_extraction(extraction, ValidationConfig(max_week=16))
    assert max_week == 16
    assert any(i.code.value == "WEEK_OUT_OF_RANGE" for i in issues)


def test_period_grid_mismatch_is_warning():
    # morning slot but periods 10-11
    occ = make_occ([WeekRange(1, 16, WeekParity.ALL)], period_start=10, period_end=11, daypart="上午")
    extraction = ExtractionResult(meta=SemesterMeta(), occurrences=[occ])
    _, issues, _ = validate_extraction(extraction, ValidationConfig())
    assert any(i.code.value == "PERIOD_GRID_MISMATCH" and i.severity == "warning" for i in issues)
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_validation.py -v
```

Expected: FAIL

- [ ] **Step 3: Implement validation**

Create `backend/src/class_table_backend/domain/validation.py`:

```python
from __future__ import annotations

from dataclasses import dataclass

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.domain.models import ParsedOccurrence
from class_table_backend.parsing.profiles.base import ExtractionResult

_DAYPART_PERIOD_HINT = {
    "上午": range(1, 5),
    "下午": range(5, 10),
    "晚上": range(10, 13),
}


@dataclass(slots=True)
class ValidationConfig:
    max_week: int | None = None


def _occurrence_max_week(occ: ParsedOccurrence) -> int:
    if not occ.week_ranges:
        return 0
    return max(r.end for r in occ.week_ranges)


def validate_extraction(
    extraction: ExtractionResult,
    config: ValidationConfig | None = None,
) -> tuple[list[ParsedOccurrence], list[Issue], int]:
    config = config or ValidationConfig()
    issues: list[Issue] = list(extraction.issues)
    derived = max((_occurrence_max_week(o) for o in extraction.occurrences), default=0)
    if config.max_week is not None:
        max_week = config.max_week
    elif derived > 0:
        max_week = derived
    else:
        max_week = 0
        issues.append(
            Issue(
                code=IssueCode.MISSING_MAX_WEEK,
                message="无法确定最大学号周次，请在导入配置中提供 max_week",
                severity="error",
            )
        )

    for occ in extraction.occurrences:
        for rng in occ.week_ranges:
            if max_week and rng.end > max_week:
                issues.append(
                    Issue(
                        code=IssueCode.WEEK_OUT_OF_RANGE,
                        message=f"周次 {rng.start}-{rng.end} 超过 max_week={max_week}",
                        severity="warning",
                        sheet=occ.sheet,
                        coordinate=occ.coordinate,
                        line_index=occ.line_index,
                        raw_text=occ.raw_line,
                    )
                )
        if occ.daypart and occ.period_start > 0:
            allowed = _DAYPART_PERIOD_HINT.get(occ.daypart)
            if allowed and occ.period_start not in allowed:
                issues.append(
                    Issue(
                        code=IssueCode.PERIOD_GRID_MISMATCH,
                        message=(
                            f"网格时段为{occ.daypart}，但文本节次为 "
                            f"{occ.period_start}-{occ.period_end}，以文本节次为准"
                        ),
                        severity="warning",
                        sheet=occ.sheet,
                        coordinate=occ.coordinate,
                        line_index=occ.line_index,
                        raw_text=occ.raw_line,
                    )
                )
    return extraction.occurrences, issues, max_week
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_validation.py -v
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/class_table_backend/domain backend/tests
git commit -m "feat(backend): validate extracted occurrences and derive max_week"
```

---

### Task 5: Persistence models + Alembic migration

**Covers:** S6

**Files:**
- Create: `backend/src/class_table_backend/persistence/__init__.py`
- Create: `backend/src/class_table_backend/persistence/db.py`
- Create: `backend/src/class_table_backend/persistence/tables.py`
- Create: `backend/src/class_table_backend/persistence/repositories.py`
- Create: `backend/alembic.ini`
- Create: `backend/src/class_table_backend/persistence/migrations/env.py` (use alembic init then replace)
- Test: `backend/tests/test_repositories.py`

**Interfaces:**
- Consumes: domain models / week ranges
- Produces:
  - `db.get_engine(url: str | None = None)`, `db.get_session_factory(engine)`, `db.session_scope(...)`
  - `tables.Base`, `ImportBatchRow`, `ImportRowRow`, `CourseRow`, `MeetingOccurrenceRow`, `MeetingWeekRow`
  - `repositories.ImportRepository` with:
    - `create_batch(...)-> ImportBatchRow`
    - `get_batch(import_id: str) -> ImportBatchRow | None`
    - `get_batch_by_hash(file_hash: str) -> ImportBatchRow | None`
    - `replace_rows(import_id, rows_payload) -> None`
    - `confirm_rows(import_id, row_ids: list[str] | None) -> ImportBatchRow`

- [ ] **Step 1: Implement DB engine/session + tables**

Create `backend/src/class_table_backend/persistence/__init__.py`:

```python
```

Create `backend/src/class_table_backend/persistence/db.py`:

```python
from __future__ import annotations

import os
from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker


def get_engine(url: str | None = None) -> Engine:
    db_url = url or os.environ.get("DATABASE_URL", "sqlite:///./class_table.db")
    return create_engine(db_url, future=True)


def get_session_factory(engine: Engine | None = None) -> sessionmaker[Session]:
    return sessionmaker(bind=engine or get_engine(), expire_on_commit=False, future=True)


@contextmanager
def session_scope(factory: sessionmaker[Session] | None = None) -> Iterator[Session]:
    session = (factory or get_session_factory())()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
```

Create `backend/src/class_table_backend/persistence/tables.py`:

```python
from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import JSON, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class ImportBatchRow(Base):
    __tablename__ = "import_batches"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    file_hash: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    profile: Mapped[str] = mapped_column(String(64), nullable=False)
    academic_year: Mapped[str | None] = mapped_column(String(16), nullable=True)
    semester_name: Mapped[str | None] = mapped_column(String(32), nullable=True)
    start_date: Mapped[str | None] = mapped_column(String(32), nullable=True)
    class_name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    max_week: Mapped[int | None] = mapped_column(Integer, nullable=True)
    meta_json: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    rows: Mapped[list[ImportRowRow]] = relationship(back_populates="batch", cascade="all, delete-orphan")


class ImportRowRow(Base):
    __tablename__ = "import_rows"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    batch_id: Mapped[str] = mapped_column(ForeignKey("import_batches.id"), index=True)
    row_status: Mapped[str] = mapped_column(String(32), nullable=False)
    selected: Mapped[int] = mapped_column(Integer, default=1)
    course_code: Mapped[str] = mapped_column(String(64), default="")
    course_name: Mapped[str] = mapped_column(String(255), default="")
    weekday: Mapped[int] = mapped_column(Integer, default=0)
    period_start: Mapped[int] = mapped_column(Integer, default=0)
    period_end: Mapped[int] = mapped_column(Integer, default=0)
    week_text: Mapped[str] = mapped_column(String(255), default="")
    week_ranges_json: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)
    room_text: Mapped[str] = mapped_column(String(255), default="")
    sheet: Mapped[str] = mapped_column(String(128), default="")
    coordinate: Mapped[str] = mapped_column(String(32), default="")
    line_index: Mapped[int] = mapped_column(Integer, default=0)
    raw_line: Mapped[str] = mapped_column(Text, default="")
    issues_json: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)

    batch: Mapped[ImportBatchRow] = relationship(back_populates="rows")


class CourseRow(Base):
    __tablename__ = "courses"
    __table_args__ = (UniqueConstraint("course_code", "name", name="uq_course_code_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    course_code: Mapped[str] = mapped_column(String(64), index=True)
    name: Mapped[str] = mapped_column(String(255))


class MeetingOccurrenceRow(Base):
    __tablename__ = "meeting_occurrences"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    course_id: Mapped[int] = mapped_column(ForeignKey("courses.id"), index=True)
    weekday: Mapped[int] = mapped_column(Integer)
    period_start: Mapped[int] = mapped_column(Integer)
    period_end: Mapped[int] = mapped_column(Integer)
    week_text: Mapped[str] = mapped_column(String(255))
    room_text: Mapped[str] = mapped_column(String(255), default="")
    sheet: Mapped[str] = mapped_column(String(128), default="")
    coordinate: Mapped[str] = mapped_column(String(32), default="")
    line_index: Mapped[int] = mapped_column(Integer, default=0)
    import_row_id: Mapped[str | None] = mapped_column(String(36), nullable=True)


class MeetingWeekRow(Base):
    __tablename__ = "meeting_weeks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    meeting_id: Mapped[int] = mapped_column(ForeignKey("meeting_occurrences.id"), index=True)
    week_no: Mapped[int] = mapped_column(Integer, index=True)
```

- [ ] **Step 2: Write failing repository tests**

Create `backend/tests/test_repositories.py`:

```python
from __future__ import annotations

import uuid

import pytest

from class_table_backend.persistence.db import get_session_factory
from class_table_backend.persistence.repositories import ImportRepository
from class_table_backend.persistence.tables import Base, CourseRow, MeetingOccurrenceRow, MeetingWeekRow


@pytest.fixture()
def session():
    from sqlalchemy import create_engine

    engine = create_engine("sqlite://", future=True)
    Base.metadata.create_all(engine)
    factory = get_session_factory(engine)
    with factory() as s:
        yield s


def sample_row_payload():
    return [
        {
            "id": str(uuid.uuid4()),
            "row_status": "PARSED",
            "selected": 1,
            "course_code": "C1",
            "course_name": "Data Structures",
            "weekday": 1,
            "period_start": 3,
            "period_end": 4,
            "week_text": "1-16单周",
            "week_ranges_json": [{"start": 1, "end": 16, "parity": "ODD"}],
            "room_text": "A101",
            "sheet": "Sheet1",
            "coordinate": "D5",
            "line_index": 0,
            "raw_line": "[C1]Data Structures [1-16单周][3-4节] A101",
            "issues_json": [],
        }
    ]


def test_create_batch_and_confirm(session):
    repo = ImportRepository(session)
    batch = repo.create_batch(
        import_id=str(uuid.uuid4()),
        file_hash="a" * 64,
        original_filename="x.xls",
        profile="chengdu_wenli_v1",
        meta={"academic_year": "2026-2027", "semester_name": "第一学期", "max_week": 16},
    )
    repo.replace_rows(batch.id, sample_row_payload())
    session.commit()

    confirmed = repo.confirm_rows(batch.id, row_ids=None)
    assert confirmed.status == "CONFIRMED"
    courses = session.query(CourseRow).all()
    assert len(courses) == 1
    meetings = session.query(MeetingOccurrenceRow).all()
    assert len(meetings) == 1
    weeks = session.query(MeetingWeekRow).all()
    assert len(weeks) == 8  # odd weeks 1..16


def test_confirm_is_idempotent(session):
    repo = ImportRepository(session)
    batch = repo.create_batch(
        import_id=str(uuid.uuid4()),
        file_hash="b" * 64,
        original_filename="y.xls",
        profile="chengdu_wenli_v1",
        meta={"max_week": 16},
    )
    repo.replace_rows(batch.id, sample_row_payload())
    session.commit()
    repo.confirm_rows(batch.id, None)
    session.commit()
    before = session.query(MeetingOccurrenceRow).count()
    again = repo.confirm_rows(batch.id, None)
    session.commit()
    assert again.status == "CONFIRMED"
    assert session.query(MeetingOccurrenceRow).count() == before
```

- [ ] **Step 3: Run repository tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_repositories.py -v
```

Expected: FAIL

- [ ] **Step 4: Implement repositories + alembic wiring**

Create `backend/src/class_table_backend/persistence/repositories.py`:

```python
from __future__ import annotations

import json
import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from class_table_backend.domain.issues import IssueCode
from class_table_backend.persistence.tables import (
    CourseRow,
    ImportBatchRow,
    ImportRowRow,
    MeetingOccurrenceRow,
    MeetingWeekRow,
)


class ImportConfirmError(Exception):
    def __init__(self, code: IssueCode, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class ImportRepository:
    def __init__(self, session: Session):
        self.session = session

    def create_batch(
        self,
        *,
        import_id: str,
        file_hash: str,
        original_filename: str,
        profile: str,
        meta: dict[str, Any],
    ) -> ImportBatchRow:
        batch = ImportBatchRow(
            id=import_id,
            file_hash=file_hash,
            original_filename=original_filename,
            status="PARSED",
            profile=profile,
            academic_year=meta.get("academic_year"),
            semester_name=meta.get("semester_name"),
            start_date=meta.get("start_date"),
            class_name=meta.get("class_name"),
            max_week=meta.get("max_week"),
            meta_json=meta,
        )
        self.session.add(batch)
        self.session.flush()
        return batch

    def get_batch(self, import_id: str) -> ImportBatchRow | None:
        return self.session.get(ImportBatchRow, import_id)

    def get_batch_by_hash(self, file_hash: str) -> ImportBatchRow | None:
        stmt = select(ImportBatchRow).where(ImportBatchRow.file_hash == file_hash).limit(1)
        return self.session.execute(stmt).scalar_one_or_none()

    def replace_rows(self, batch_id: str, rows_payload: list[dict[str, Any]]) -> None:
        existing = self.session.execute(
            select(ImportRowRow).where(ImportRowRow.batch_id == batch_id)
        ).scalars().all()
        for row in existing:
            self.session.delete(row)
        for payload in rows_payload:
            self.session.add(ImportRowRow(batch_id=batch_id, **payload))
        self.session.flush()

    def confirm_rows(self, batch_id: str, row_ids: list[str] | None) -> ImportBatchRow:
        batch = self.get_batch(batch_id)
        if batch is None:
            raise ImportConfirmError(IssueCode.IMPORT_NOT_CONFIRMABLE, "导入批次不存在")
        if batch.status == "CONFIRMED":
            return batch

        stmt = select(ImportRowRow).where(ImportRowRow.batch_id == batch_id)
        rows = list(self.session.execute(stmt).scalars().all())
        selected = rows if row_ids is None else [r for r in rows if r.id in set(row_ids)]
        confirmable = [r for r in selected if r.row_status == "PARSED" and r.selected]
        if not confirmable:
            raise ImportConfirmError(IssueCode.IMPORT_NOT_CONFIRMABLE, "没有可确认的行")

        course_by_key: dict[tuple[str, str], CourseRow] = {}
        for row in confirmable:
            key = (row.course_code, row.course_name)
            course = course_by_key.get(key)
            if course is None:
                course = self.session.execute(
                    select(CourseRow).where(
                        CourseRow.course_code == row.course_code,
                        CourseRow.name == row.course_name,
                    )
                ).scalar_one_or_none()
                if course is None:
                    course = CourseRow(course_code=row.course_code, name=row.course_name)
                    self.session.add(course)
                    self.session.flush()
                course_by_key[key] = course

            meeting = MeetingOccurrenceRow(
                course_id=course.id,
                weekday=row.weekday,
                period_start=row.period_start,
                period_end=row.period_end,
                week_text=row.week_text,
                room_text=row.room_text,
                sheet=row.sheet,
                coordinate=row.coordinate,
                line_index=row.line_index,
                import_row_id=row.id,
            )
            self.session.add(meeting)
            self.session.flush()

            for rng in row.week_ranges_json or []:
                start = int(rng["start"])
                end = int(rng["end"])
                parity = rng.get("parity", "ALL")
                for week in range(start, end + 1):
                    if parity == "ODD" and week % 2 == 0:
                        continue
                    if parity == "EVEN" and week % 2 == 1:
                        continue
                    self.session.add(MeetingWeekRow(meeting_id=meeting.id, week_no=week))

            row.row_status = "CONFIRMED"

        batch.status = "CONFIRMED"
        self.session.flush()
        return batch
```

Initialize Alembic (command generates scaffolding; then pin `env.py` to `Base`):

```bash
cd backend
.venv/Scripts/python.exe -m pip install alembic
.venv/Scripts/python.exe -m alembic init src/class_table_backend/persistence/migrations
```

Edit `alembic.ini` `script_location = src/class_table_backend/persistence/migrations`.  
Edit generated `env.py` to import `Base` from `class_table_backend.persistence.tables` and set `target_metadata = Base.metadata`.  
In `env.py` database URL, read `DATABASE_URL` (default `sqlite:///./class_table.db`).

Generate migration:

```bash
cd backend
.venv/Scripts/python.exe -m alembic revision --autogenerate -m "create import and schedule tables"
.venv/Scripts/python.exe -m alembic upgrade head
```

- [ ] **Step 5: Run repository tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_repositories.py -v
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src backend/alembic.ini backend/tests
git commit -m "feat(backend): add persistence models and import repository"
```

---

### Task 6: Import flow service (upload / preview / confirm)

**Covers:** S6, S7

**Files:**
- Create: `backend/src/class_table_backend/import_flow/__init__.py`
- Create: `backend/src/class_table_backend/import_flow/service.py`
- Create: `backend/src/class_table_backend/import_flow/status.py`
- Test: `backend/tests/test_import_service.py`

**Interfaces:**
- Consumes: workbook reader, profile extractor, validation, `ImportRepository`
- Produces:
  - `status.ImportStatus` enum (`UPLOADED`, `PARSED`, `NEEDS_REVIEW`, `CONFIRMED`, `FAILED`)
  - `service.ImportService`:
    - `ingest(filename: str, data: bytes, *, max_week: int | None = None) -> dict`
    - `preview(import_id: str) -> dict`
    - `confirm(import_id: str, row_ids: list[str] | None = None) -> dict`
    - `list_rows(import_id: str, *, limit: int = 50, offset: int = 0) -> dict`

- [ ] **Step 1: Write failing service tests**

Create `backend/tests/test_import_service.py`:

```python
from __future__ import annotations

from pathlib import Path

import pytest

from class_table_backend.import_flow.service import ImportService
from class_table_backend.persistence.db import get_session_factory
from class_table_backend.persistence.tables import Base, CourseRow, MeetingOccurrenceRow

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = ROOT / "excel样例" / "25计科9(1).xls"


@pytest.fixture()
def service():
    from sqlalchemy import create_engine

    engine = create_engine("sqlite://", future=True)
    Base.metadata.create_all(engine)
    factory = get_session_factory(engine)
    with factory() as session:
        yield ImportService(session)


def test_ingest_preview_has_no_schedule_side_effects(service):
    data = SAMPLE.read_bytes()
    result = service.ingest("25计科9(1).xls", data)
    assert result["import_id"]
    assert result["status"] in {"PARSED", "NEEDS_REVIEW"}
    preview = service.preview(result["import_id"])
    assert preview["rows"]
    assert service.session.query(CourseRow).count() == 0
    assert service.session.query(MeetingOccurrenceRow).count() == 0


def test_confirm_writes_rows_once(service):
    data = SAMPLE.read_bytes()
    ingest = service.ingest("25计科9(1).xls", data)
    service.confirm(ingest["import_id"])
    assert service.session.query(CourseRow).count() > 0
    first = service.session.query(MeetingOccurrenceRow).count()
    service.confirm(ingest["import_id"])
    assert service.session.query(MeetingOccurrenceRow).count() == first


def test_duplicate_upload_returns_existing_or_conflict(service):
    data = SAMPLE.read_bytes()
    a = service.ingest("25计科9(1).xls", data)
    b = service.ingest("copy.xls", data)
    assert b["import_id"] == a["import_id"] or b.get("duplicate_of") == a["import_id"]
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_import_service.py -v
```

Expected: FAIL

- [ ] **Step 3: Implement import service**

Create `backend/src/class_table_backend/import_flow/status.py`:

```python
from __future__ import annotations

from enum import StrEnum


class ImportStatus(StrEnum):
    UPLOADED = "UPLOADED"
    PARSED = "PARSED"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"
    FAILED = "FAILED"
```

Create `backend/src/class_table_backend/import_flow/__init__.py`:

```python
```

Create `backend/src/class_table_backend/import_flow/service.py`:

```python
from __future__ import annotations

import hashlib
import uuid
from typing import Any

from sqlalchemy.orm import Session

from class_table_backend.domain.issues import IssueCode
from class_table_backend.domain.models import ParsedOccurrence
from class_table_backend.domain.validation import ValidationConfig, validate_extraction
from class_table_backend.import_flow.status import ImportStatus
from class_table_backend.parsing.profiles.chengdu_wenli_v1 import extract_chengdu_wenli_v1
from class_table_backend.parsing.workbook import detect_format, read_workbook
from class_table_backend.persistence.repositories import ImportConfirmError, ImportRepository

PROFILE = "chengdu_wenli_v1"
MAX_UPLOAD_BYTES = 5 * 1024 * 1024


class ImportService:
    def __init__(self, session: Session):
        self.session = session
        self.repo = ImportRepository(session)

    def ingest(self, filename: str, data: bytes, *, max_week: int | None = None) -> dict[str, Any]:
        if len(data) > MAX_UPLOAD_BYTES:
            return {
                "import_id": None,
                "status": ImportStatus.FAILED,
                "issues": [
                    {
                        "code": IssueCode.UNSUPPORTED_FILE_TYPE,
                        "message": "文件超过大小限制",
                        "severity": "error",
                    }
                ],
            }
        file_hash = hashlib.sha256(data).hexdigest()
        existing = self.repo.get_batch_by_hash(file_hash)
        if existing is not None:
            return {
                "import_id": existing.id,
                "status": existing.status,
                "duplicate_of": existing.id,
                "issues": [],
            }

        kind = detect_format(data)
        if kind is None:
            batch = self.repo.create_batch(
                import_id=str(uuid.uuid4()),
                file_hash=file_hash,
                original_filename=filename,
                profile=PROFILE,
                meta={"max_week": max_week},
            )
            batch.status = ImportStatus.FAILED
            self.session.flush()
            return {
                "import_id": batch.id,
                "status": batch.status,
                "issues": [
                    {
                        "code": IssueCode.UNSUPPORTED_FILE_TYPE,
                        "message": "不支持的文件类型",
                        "severity": "error",
                    }
                ],
            }

        try:
            workbook = read_workbook(data)
            extraction = extract_chengdu_wenli_v1(workbook)
        except ValueError as exc:
            issue = exc.args[0]
            batch = self.repo.create_batch(
                import_id=str(uuid.uuid4()),
                file_hash=file_hash,
                original_filename=filename,
                profile=PROFILE,
                meta={"max_week": max_week},
            )
            batch.status = ImportStatus.FAILED
            self.session.flush()
            return {
                "import_id": batch.id,
                "status": batch.status,
                "issues": [
                    {
                        "code": getattr(issue, "code", IssueCode.MALFORMED_WORKBOOK),
                        "message": getattr(issue, "message", "工作簿解析失败"),
                        "severity": "error",
                    }
                ],
            }

        occurrences, issues, effective_max_week = validate_extraction(
            extraction, ValidationConfig(max_week=max_week)
        )
        meta = extraction.meta
        batch = self.repo.create_batch(
            import_id=str(uuid.uuid4()),
            file_hash=file_hash,
            original_filename=filename,
            profile=PROFILE,
            meta={
                "academic_year": meta.academic_year,
                "semester_name": meta.semester_name,
                "start_date": meta.start_date,
                "department": meta.department,
                "grade": meta.grade,
                "major": meta.major,
                "class_name": meta.class_name,
                "title": meta.title,
                "max_week": effective_max_week,
            },
        )
        self.repo.replace_rows(batch.id, [self._row_payload(o, issues) for o in occurrences])
        has_error = any(i.severity == "error" for i in issues)
        has_warning = any(i.severity == "warning" for i in issues)
        batch.status = (
            ImportStatus.NEEDS_REVIEW if (has_error or has_warning) else ImportStatus.PARSED
        )
        self.session.flush()
        return {
            "import_id": batch.id,
            "status": batch.status,
            "max_week": effective_max_week,
            "issues": [self._issue_payload(i) for i in issues],
        }

    def preview(self, import_id: str) -> dict[str, Any]:
        batch = self.repo.get_batch(import_id)
        if batch is None:
            return {"import_id": import_id, "status": "NOT_FOUND", "rows": []}
        rows = [self._export_row(r) for r in batch.rows]
        return {
            "import_id": batch.id,
            "status": batch.status,
            "max_week": batch.max_week,
            "meta": batch.meta_json,
            "rows": rows,
        }

    def confirm(self, import_id: str, row_ids: list[str] | None = None) -> dict[str, Any]:
        try:
            batch = self.repo.confirm_rows(import_id, row_ids)
        except ImportConfirmError as exc:
            return {
                "import_id": import_id,
                "status": "FAILED",
                "issues": [{"code": exc.code, "message": exc.message, "severity": "error"}],
            }
        return {
            "import_id": batch.id,
            "status": batch.status,
            "rows_confirmed": sum(1 for r in batch.rows if r.row_status == "CONFIRMED"),
        }

    def list_rows(self, import_id: str, *, limit: int = 50, offset: int = 0) -> dict[str, Any]:
        batch = self.repo.get_batch(import_id)
        if batch is None:
            return {"import_id": import_id, "status": "NOT_FOUND", "rows": [], "total": 0}
        rows = batch.rows[offset : offset + limit]
        return {
            "import_id": import_id,
            "status": batch.status,
            "total": len(batch.rows),
            "rows": [self._export_row(r) for r in rows],
        }

    def _row_payload(self, occ: ParsedOccurrence, all_issues: list) -> dict[str, Any]:
        row_issues = [
            self._issue_payload(i)
            for i in all_issues
            if i.coordinate == occ.coordinate and i.line_index == occ.line_index
        ]
        for i in occ.issues:
            payload = self._issue_payload(i)
            if payload not in row_issues:
                row_issues.append(payload)
        status = "PARSED"
        if any(i.get("severity") == "error" for i in row_issues):
            status = "INVALID"
        elif any(i.get("severity") == "warning" for i in row_issues):
            status = "NEEDS_REVIEW"
        return {
            "id": str(uuid.uuid4()),
            "row_status": status,
            "selected": 0 if status == "INVALID" else 1,
            "course_code": occ.course_code,
            "course_name": occ.course_name,
            "weekday": occ.weekday,
            "period_start": occ.period_start,
            "period_end": occ.period_end,
            "week_text": occ.week_text,
            "week_ranges_json": [
                {"start": r.start, "end": r.end, "parity": r.parity.value} for r in occ.week_ranges
            ],
            "room_text": occ.room_text,
            "sheet": occ.sheet,
            "coordinate": occ.coordinate,
            "line_index": occ.line_index,
            "raw_line": occ.raw_line,
            "issues_json": row_issues,
        }

    def _issue_payload(self, issue) -> dict[str, Any]:
        return {
            "code": getattr(issue, "code", issue.get("code")),
            "message": getattr(issue, "message", issue.get("message")),
            "severity": getattr(issue, "severity", issue.get("severity", "error")),
            "sheet": getattr(issue, "sheet", None),
            "coordinate": getattr(issue, "coordinate", None),
            "line_index": getattr(issue, "line_index", None),
        }

    def _export_row(self, row) -> dict[str, Any]:
        return {
            "row_id": row.id,
            "status": row.row_status,
            "selected": bool(row.selected),
            "course_code": row.course_code,
            "course_name": row.course_name,
            "weekday": row.weekday,
            "period_start": row.period_start,
            "period_end": row.period_end,
            "week_text": row.week_text,
            "week_ranges": row.week_ranges_json,
            "room_text": row.room_text,
            "sheet": row.sheet,
            "coordinate": row.coordinate,
            "line_index": row.line_index,
            "raw_line": row.raw_line,
            "issues": row.issues_json,
        }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_import_service.py -v
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: PASS. If weekday/period assertions in profile tests used earlier fixtures, keep those green too by re-running the full suite.

- [ ] **Step 5: Commit**

```bash
git add backend/src/class_table_backend/import_flow backend/tests
git commit -m "feat(backend): orchestrate import ingest preview and confirm"
```

---

### Task 7: FastAPI routes + end-to-end API tests

**Covers:** S7, S8

**Files:**
- Create: `backend/src/class_table_backend/api/__init__.py`
- Create: `backend/src/class_table_backend/api/app.py`
- Create: `backend/src/class_table_backend/api/routes_imports.py`
- Create: `backend/src/class_table_backend/api/schemas.py`
- Test: `backend/tests/test_api.py`

**Interfaces:**
- Consumes: `ImportService`
- Produces:
  - `api.app.create_app(session_factory=None) -> FastAPI`
  - Routes: `POST /imports`, `GET /imports/{id}`, `POST /imports/{id}/confirm`, `GET /imports/{id}/rows`

- [ ] **Step 1: Write failing API tests**

Create `backend/tests/test_api.py`:

```python
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from class_table_backend.api.app import create_app
from class_table_backend.persistence.tables import Base

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = ROOT / "excel样例" / "25计科9(1).xls"


@pytest.fixture()
def client():
    engine = create_engine("sqlite://", future=True)
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False, future=True)
    app = create_app(session_factory=factory)
    return TestClient(app)


def test_upload_preview_confirm_flow(client):
    data = SAMPLE.read_bytes()
    resp = client.post(
        "/imports",
        files={"file": ("25计科9(1).xls", data, "application/vnd.ms-excel")},
    )
    assert resp.status_code == 200
    body = resp.json()
    import_id = body["import_id"]
    assert import_id

    preview = client.get(f"/imports/{import_id}").json()
    assert preview["rows"]

    rows = client.get(f"/imports/{import_id}/rows", params={"limit": 5}).json()
    assert rows["total"] >= 1

    confirm = client.post(f"/imports/{import_id}/confirm").json()
    assert confirm["status"] == "CONFIRMED"

    again = client.post(f"/imports/{import_id}/confirm").json()
    assert again["status"] == "CONFIRMED"
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_api.py -v
```

Expected: FAIL

- [ ] **Step 3: Implement API**

Create `backend/src/class_table_backend/api/schemas.py`:

```python
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class IssueOut(BaseModel):
    code: str
    message: str
    severity: str = "error"
    sheet: str | None = None
    coordinate: str | None = None
    line_index: int | None = None


class ImportIngestResponse(BaseModel):
    import_id: str | None
    status: str
    max_week: int | None = None
    duplicate_of: str | None = None
    issues: list[IssueOut] = Field(default_factory=list)


class ImportRowOut(BaseModel):
    row_id: str
    status: str
    selected: bool
    course_code: str
    course_name: str
    weekday: int
    period_start: int
    period_end: int
    week_text: str
    week_ranges: list[dict[str, Any]] = Field(default_factory=list)
    room_text: str
    sheet: str
    coordinate: str
    line_index: int
    raw_line: str
    issues: list[dict[str, Any]] = Field(default_factory=list)


class ImportPreviewOut(BaseModel):
    import_id: str
    status: str
    max_week: int | None = None
    meta: dict[str, Any] = Field(default_factory=dict)
    rows: list[ImportRowOut] = Field(default_factory=list)


class ConfirmRequest(BaseModel):
    row_ids: list[str] | None = None


class ConfirmOut(BaseModel):
    import_id: str
    status: str
    rows_confirmed: int | None = None
    issues: list[IssueOut] = Field(default_factory=list)


class RowsPageOut(BaseModel):
    import_id: str
    status: str
    total: int
    rows: list[ImportRowOut] = Field(default_factory=list)
```

Create `backend/src/class_table_backend/api/routes_imports.py`:

```python
from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy.orm import Session

from class_table_backend.api.schemas import (
    ConfirmOut,
    ConfirmRequest,
    ImportIngestResponse,
    ImportPreviewOut,
    RowsPageOut,
)
from class_table_backend.import_flow.service import ImportService
from class_table_backend.persistence.db import get_session_factory
from class_table_backend.persistence.repositories import ImportRepository

router = APIRouter(prefix="/imports", tags=["imports"])


def get_session() -> Session:
    session = get_session_factory()()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


@router.post("", response_model=ImportIngestResponse)
async def create_import(
    file: UploadFile = File(...),
    max_week: int | None = Form(default=None),
    session: Session = Depends(get_session),
) -> ImportIngestResponse:
    data = await file.read()
    service = ImportService(session)
    result = service.ingest(file.filename or "upload", data, max_week=max_week)
    return ImportIngestResponse(**result)


@router.get("/{import_id}", response_model=ImportPreviewOut)
def get_import(import_id: str, session: Session = Depends(get_session)) -> ImportPreviewOut:
    service = ImportService(session)
    result = service.preview(import_id)
    if result.get("status") == "NOT_FOUND":
        raise HTTPException(status_code=404, detail="导入批次不存在")
    return ImportPreviewOut(**result)


@router.post("/{import_id}/confirm", response_model=ConfirmOut)
def confirm_import(
    import_id: str,
    payload: ConfirmRequest | None = None,
    session: Session = Depends(get_session),
) -> ConfirmOut:
    service = ImportService(session)
    result = service.confirm(import_id, None if payload is None else payload.row_ids)
    return ConfirmOut(**result)


@router.get("/{import_id}/rows", response_model=RowsPageOut)
def list_rows(
    import_id: str,
    limit: int = 50,
    offset: int = 0,
    session: Session = Depends(get_session),
) -> RowsPageOut:
    service = ImportService(session)
    result = service.list_rows(import_id, limit=limit, offset=offset)
    if result.get("status") == "NOT_FOUND":
        raise HTTPException(status_code=404, detail="导入批次不存在")
    return RowsPageOut(**result)
```

Create `backend/src/class_table_backend/api/app.py`:

```python
from __future__ import annotations

from fastapi import FastAPI
from sqlalchemy.orm import sessionmaker

from class_table_backend.api.routes_imports import router


def create_app(session_factory: sessionmaker | None = None) -> FastAPI:
    app = FastAPI(title="Class Table Backend", version="0.1.0")
    if session_factory is not None:
        # override default session factory used by routes
        from class_table_backend.persistence import db as db_module

        db_module.get_session_factory = lambda engine=None: session_factory  # type: ignore[assignment]

    app.include_router(router)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    return app
```

Create `backend/src/class_table_backend/api/__init__.py`:

```python
```

- [ ] **Step 4: Run API tests to verify they pass**

```bash
cd backend
.venv/Scripts/python.exe -m pytest tests/test_api.py -v
.venv/Scripts/python.exe -m pytest
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: full suite PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/class_table_backend/api backend/tests
git commit -m "feat(backend): expose import preview and confirm HTTP API"
```

---

### Task 8: Backend docs + final verification

**Covers:** S3, S8

**Files:**
- Create: `backend/README.md`
- Modify: `docs/` only if a short backend pointer is needed (do not rewrite 里程碑文档)

**Interfaces:**
- Consumes: finished API
- Produces: local runbook in Chinese

- [ ] **Step 1: Write backend README**

Create `backend/README.md`:

```markdown
# 课表导入后端

## 本地启动

```bash
cd backend
python -m venv .venv
.venv/Scripts/python.exe -m pip install -e ".[dev]"
$env:DATABASE_URL="sqlite:///./class_table.db"
.venv/Scripts/python.exe -m alembic upgrade head
.venv/Scripts/python.exe -m uvicorn class_table_backend.api.app:create_app --factory --reload --port 8000
```

## API

- `POST /imports`：multipart 字段 `file`，可选表单字段 `max_week`
- `GET /imports/{import_id}`：预览
- `POST /imports/{import_id}/confirm`：确认；body 可选 `{"row_ids": [...] }`
- `GET /imports/{import_id}/rows?limit=&offset=`：行列表

## 测试

```bash
cd backend
.venv/Scripts/python.exe -m pytest
.venv/Scripts/python.exe -m ruff check src tests
```

真实样例：`excel样例/25计科9(1).xls`（仓库根目录）。
```

- [ ] **Step 2: Run full verification**

```bash
cd backend
.venv/Scripts/python.exe -m pytest
.venv/Scripts/python.exe -m ruff check src tests
```

Expected: all tests PASS, ruff clean. Record command output in the handoff summary.

- [ ] **Step 3: Commit**

```bash
git add backend/README.md
git commit -m "docs(backend): add local setup and API notes"
```

---

## Self-Review Notes

1. **Spec coverage:** S1/S2 framing covered by overall plan; S3→Tasks 1,8; S4→Tasks 3,6; S5→Tasks 1,2,4; S6→Tasks 4,5,6; S7→Tasks 6,7; S8→Tasks 3,7,8; S9 is non-goal text (no implementation task, correctly omitted).
2. **Placeholder scan:** no TBD/TODO; Task 5 ships a single final `db.py` implementation.
3. **Type consistency:** `parse_week_expression` / `WeekRange` / `Issue` names are shared across Tasks 1–4; `ImportService` methods match API routes in Task 7.
