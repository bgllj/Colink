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
    import_id: str
    status: str
    max_week: int | None = None
    duplicate_of: str | None = None
    issues: list[IssueOut] = Field(default_factory=list)


class ImportRowOut(BaseModel):
    row_id: str
    status: str
    selected: bool = False
    course_code: str | None = None
    course_name: str | None = None
    weekday: int | None = None
    period_start: int | None = None
    period_end: int | None = None
    week_text: str | None = None
    week_ranges: list[dict[str, Any]] = Field(default_factory=list)
    room_text: str | None = None
    sheet: str | None = None
    coordinate: str | None = None
    line_index: int | None = None
    raw_line: str | None = None
    issues: list[IssueOut] = Field(default_factory=list)


class ImportPreviewOut(BaseModel):
    import_id: str
    status: str
    max_week: int | None = None
    issues: list[IssueOut] = Field(default_factory=list)
    meta: dict[str, Any] = Field(default_factory=dict)
    rows: list[ImportRowOut] = Field(default_factory=list)


class ConfirmRequest(BaseModel):
    row_ids: list[str] | None = None


class ConfirmOut(BaseModel):
    import_id: str
    status: str
    rows_confirmed: int = 0
    issues: list[IssueOut] = Field(default_factory=list)


class RowsPageOut(BaseModel):
    import_id: str
    status: str
    total: int
    limit: int = 50
    offset: int = 0
    rows: list[ImportRowOut] = Field(default_factory=list)
