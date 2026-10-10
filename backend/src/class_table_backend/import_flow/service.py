from __future__ import annotations

import hashlib
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.domain.models import ParsedOccurrence
from class_table_backend.domain.validation import ValidationConfig, validate_extraction
from class_table_backend.import_flow.status import ImportStatus
from class_table_backend.parsing.profiles.base import ExtractionResult
from class_table_backend.parsing.profiles.chengdu_wenli_v1 import extract_chengdu_wenli_v1
from class_table_backend.parsing.week_expr import WeekRange
from class_table_backend.parsing.workbook import WorkbookReadError, detect_format, read_workbook
from class_table_backend.persistence.repositories import (
    BATCH_STATUS_FAILED,
    ROW_STATUS_CONFIRMED,
    ImportConfirmError,
    ImportRepository,
)
from class_table_backend.persistence.tables import ImportRowRow

MAX_UPLOAD_BYTES = 5 * 1024 * 1024
PROFILE_NAME = "chengdu_wenli_v1"

ROW_STATUS_PARSED = "PARSED"
ROW_STATUS_NEEDS_REVIEW = "NEEDS_REVIEW"
ROW_STATUS_INVALID = "INVALID"


def _issue_dict(
    *,
    code: str,
    message: str,
    severity: str = "error",
    sheet: str | None = None,
    coordinate: str | None = None,
    line_index: int | None = None,
) -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "severity": severity,
        "sheet": sheet,
        "coordinate": coordinate,
        "line_index": line_index,
    }


def _issue_to_dict(issue: Issue) -> dict[str, Any]:
    return _issue_dict(
        code=str(issue.code),
        message=issue.message,
        severity=issue.severity,
        sheet=issue.sheet,
        coordinate=issue.coordinate,
        line_index=issue.line_index,
    )


def _week_ranges_json(week_ranges: list[WeekRange]) -> list[dict[str, Any]]:
    return [
        {"start": item.start, "end": item.end, "parity": str(item.parity)}
        for item in week_ranges
    ]


def _row_status_for(issues: list[Issue]) -> str:
    if any(issue.severity == "error" for issue in issues):
        return ROW_STATUS_INVALID
    if any(issue.severity == "warning" for issue in issues):
        return ROW_STATUS_NEEDS_REVIEW
    return ROW_STATUS_PARSED


def _issues_for_occurrence(
    occurrence: ParsedOccurrence, all_issues: list[Issue]
) -> list[Issue]:
    related = list(occurrence.issues)
    seen = {(str(issue.code), issue.message) for issue in related}
    for issue in all_issues:
        if issue.sheet != occurrence.sheet:
            continue
        if issue.coordinate != occurrence.coordinate:
            continue
        if issue.line_index != occurrence.line_index:
            continue
        key = (str(issue.code), issue.message)
        if key in seen:
            continue
        related.append(issue)
        seen.add(key)
    return related


def _is_batch_level(issue: Issue, occurrences: list[ParsedOccurrence]) -> bool:
    if issue.sheet is None and issue.coordinate is None and issue.line_index is None:
        return True
    for occurrence in occurrences:
        if (
            occurrence.sheet == issue.sheet
            and occurrence.coordinate == issue.coordinate
            and occurrence.line_index == issue.line_index
        ):
            return False
    return True


def _row_payload(occurrence: ParsedOccurrence, issues: list[Issue]) -> dict[str, Any]:
    return {
        "row_status": _row_status_for(issues),
        "selected": 1,
        "course_code": occurrence.course_code,
        "course_name": occurrence.course_name,
        "weekday": occurrence.weekday,
        "period_start": occurrence.period_start,
        "period_end": occurrence.period_end,
        "week_text": occurrence.week_text,
        "week_ranges_json": _week_ranges_json(occurrence.week_ranges),
        "room_text": occurrence.room_text,
        "sheet": occurrence.sheet,
        "coordinate": occurrence.coordinate,
        "line_index": occurrence.line_index,
        "raw_line": occurrence.raw_line,
        "issues_json": [_issue_to_dict(issue) for issue in issues],
    }


def _export_row(row: ImportRowRow) -> dict[str, Any]:
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
        "week_ranges": row.week_ranges_json or [],
        "room_text": row.room_text,
        "sheet": row.sheet,
        "coordinate": row.coordinate,
        "line_index": row.line_index,
        "raw_line": row.raw_line,
        "issues": row.issues_json or [],
    }


class ImportService:
    def __init__(self, session: Session) -> None:
        self.session = session
        self.repo = ImportRepository(session)

    def ingest(
        self,
        filename: str,
        data: bytes,
        *,
        max_week: int | None = None,
        class_id: str | None = None,
        class_name: str | None = None,
    ) -> dict[str, Any]:
        file_hash = hashlib.sha256(data).hexdigest()

        if len(data) > MAX_UPLOAD_BYTES:
            issue = _issue_dict(
                code=str(IssueCode.FILE_TOO_LARGE),
                message=f"上传文件超过大小限制 {MAX_UPLOAD_BYTES} 字节",
            )
            batch = self.repo.create_batch(
                file_hash=file_hash,
                original_filename=filename,
                status=BATCH_STATUS_FAILED,
                max_week=max_week,
                class_id=class_id,
                class_name=class_name,
                meta_json={"issues": [issue]},
            )
            return {
                "import_id": batch.id,
                "status": ImportStatus.FAILED,
                "max_week": max_week,
                "issues": [issue],
            }

        existing = self.repo.get_batch_by_hash(file_hash)
        if existing is not None:
            return {
                "import_id": existing.id,
                "status": existing.status,
                "duplicate_of": existing.id,
                "issues": [],
            }

        file_format = detect_format(data)
        if file_format is None:
            issue = _issue_to_dict(
                Issue(
                    code=IssueCode.UNSUPPORTED_FILE_TYPE,
                    message="无法识别的工作簿文件类型 (需要 OLE2 .xls 或 zip .xlsx)",
                )
            )
            batch = self.repo.create_batch(
                file_hash=file_hash,
                original_filename=filename,
                status=BATCH_STATUS_FAILED,
                max_week=max_week,
                class_id=class_id,
                class_name=class_name,
                meta_json={"issues": [issue], "file_format": None},
            )
            return {
                "import_id": batch.id,
                "status": ImportStatus.FAILED,
                "max_week": max_week,
                "issues": [issue],
            }

        try:
            workbook = read_workbook(data)
        except WorkbookReadError as exc:
            issue = _issue_to_dict(exc.issue)
            batch = self.repo.create_batch(
                file_hash=file_hash,
                original_filename=filename,
                status=BATCH_STATUS_FAILED,
                max_week=max_week,
                class_id=class_id,
                class_name=class_name,
                meta_json={"issues": [issue], "file_format": file_format},
            )
            return {
                "import_id": batch.id,
                "status": ImportStatus.FAILED,
                "max_week": max_week,
                "issues": [issue],
            }

        try:
            extraction = extract_chengdu_wenli_v1(workbook)
        except Exception as exc:  # noqa: BLE001 - surface profile failures as structured issue
            issue = _issue_dict(
                code=IssueCode.MALFORMED_WORKBOOK,
                message=f"课表提取失败: {exc}",
            )
            batch = self.repo.create_batch(
                file_hash=file_hash,
                original_filename=filename,
                status=BATCH_STATUS_FAILED,
                max_week=max_week,
                class_id=class_id,
                class_name=class_name,
                meta_json={"issues": [issue], "file_format": file_format},
            )
            return {
                "import_id": batch.id,
                "status": ImportStatus.FAILED,
                "max_week": max_week,
                "issues": [issue],
            }

        unrecognized = [
            issue
            for issue in extraction.issues
            if issue.code == IssueCode.UNRECOGNIZED_LAYOUT
        ]
        if unrecognized:
            issues = [_issue_to_dict(issue) for issue in unrecognized]
            batch = self.repo.create_batch(
                file_hash=file_hash,
                original_filename=filename,
                status=BATCH_STATUS_FAILED,
                profile=PROFILE_NAME,
                max_week=max_week,
                class_id=class_id,
                class_name=class_name,
                meta_json={"issues": issues, "file_format": file_format},
            )
            return {
                "import_id": batch.id,
                "status": ImportStatus.FAILED,
                "max_week": max_week,
                "issues": issues,
            }

        return self._persist_extraction(
            filename=filename,
            file_hash=file_hash,
            file_format=file_format,
            extraction=extraction,
            max_week=max_week,
            class_id=class_id,
            class_name=class_name,
        )

    def _persist_extraction(
        self,
        *,
        filename: str,
        file_hash: str,
        file_format: str | None,
        extraction: ExtractionResult,
        max_week: int | None,
        class_id: str | None = None,
        class_name: str | None = None,
    ) -> dict[str, Any]:
        occurrences, all_issues, effective_max_week = validate_extraction(
            extraction,
            ValidationConfig(max_week=max_week),
        )

        rows_payload: list[dict[str, Any]] = []
        for occurrence in occurrences:
            row_issues = _issues_for_occurrence(occurrence, all_issues)
            rows_payload.append(_row_payload(occurrence, row_issues))

        batch_issues = [
            _issue_to_dict(issue)
            for issue in all_issues
            if _is_batch_level(issue, occurrences)
        ]

        has_problems = any(issue.severity in ("error", "warning") for issue in all_issues)
        status = ImportStatus.NEEDS_REVIEW if has_problems else ImportStatus.PARSED

        meta = extraction.meta
        batch = self.repo.create_batch(
            file_hash=file_hash,
            original_filename=filename,
            status=str(status),
            profile=PROFILE_NAME,
            academic_year=meta.academic_year,
            semester_name=meta.semester_name,
            start_date=meta.start_date,
            class_id=class_id,
            class_name=class_name or meta.class_name,
            max_week=effective_max_week,
            meta_json={
                "issues": batch_issues,
                "file_format": file_format,
                "title": meta.title,
                "department": meta.department,
                "grade": meta.grade,
                "major": meta.major,
            },
        )
        self.repo.replace_rows(batch.id, rows_payload)

        return {
            "import_id": batch.id,
            "status": status,
            "max_week": effective_max_week,
            "issues": batch_issues,
        }

    def preview(self, import_id: str) -> dict[str, Any]:
        batch = self.repo.get_batch(import_id)
        if batch is None:
            raise LookupError(f"导入批次不存在: {import_id}")
        rows = self.session.scalars(
            select(ImportRowRow).where(ImportRowRow.batch_id == import_id)
        ).all()
        meta = batch.meta_json or {}
        return {
            "import_id": batch.id,
            "status": batch.status,
            "file_hash": batch.file_hash,
            "original_filename": batch.original_filename,
            "profile": batch.profile,
            "academic_year": batch.academic_year,
            "semester_name": batch.semester_name,
            "start_date": batch.start_date,
            "class_id": batch.class_id,
            "class_name": batch.class_name,
            "max_week": batch.max_week,
            "issues": meta.get("issues", []),
            "rows": [_export_row(row) for row in rows],
        }

    def confirm(
        self,
        import_id: str,
        row_ids: list[str] | None = None,
        *,
        class_id: str | None = None,
        class_name: str | None = None,
        replace: bool = False,
    ) -> dict[str, Any]:
        try:
            batch = self.repo.confirm_rows(
                import_id,
                row_ids,
                class_id=class_id,
                class_name=class_name,
                replace=replace,
            )
        except ImportConfirmError as exc:
            result: dict[str, Any] = {
                "import_id": import_id,
                "status": ImportStatus.FAILED,
                "issues": [
                    _issue_dict(code=exc.code, message=exc.message),
                ],
            }
            if exc.code == "SCHEDULE_EXISTS":
                result["status"] = ImportStatus.CONFLICT
            return result

        rows_confirmed = self.session.scalar(
            select(func.count())
            .select_from(ImportRowRow)
            .where(
                ImportRowRow.batch_id == batch.id,
                ImportRowRow.row_status == ROW_STATUS_CONFIRMED,
            )
        )
        return {
            "import_id": batch.id,
            "status": ImportStatus.CONFIRMED,
            "class_id": batch.class_id,
            "rows_confirmed": int(rows_confirmed or 0),
        }

    def list_rows(
        self,
        import_id: str,
        *,
        limit: int = 50,
        offset: int = 0,
    ) -> dict[str, Any]:
        batch = self.repo.get_batch(import_id)
        if batch is None:
            raise LookupError(f"导入批次不存在: {import_id}")

        total = self.session.scalar(
            select(func.count())
            .select_from(ImportRowRow)
            .where(ImportRowRow.batch_id == import_id)
        )
        rows = self.session.scalars(
            select(ImportRowRow)
            .where(ImportRowRow.batch_id == import_id)
            .offset(offset)
            .limit(limit)
        ).all()
        return {
            "import_id": batch.id,
            "status": batch.status,
            "total": int(total or 0),
            "limit": limit,
            "offset": offset,
            "rows": [_export_row(row) for row in rows],
        }
