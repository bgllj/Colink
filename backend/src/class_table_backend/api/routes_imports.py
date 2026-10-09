from __future__ import annotations

from collections.abc import Iterator
from typing import Annotated, Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.api.schemas import (
    ConfirmOut,
    ConfirmRequest,
    ImportIngestResponse,
    ImportPreviewOut,
    ImportRowOut,
    IssueOut,
    RowsPageOut,
)
from class_table_backend.domain.issues import IssueCode
from class_table_backend.import_flow.service import MAX_UPLOAD_BYTES, ImportService
from class_table_backend.import_flow.status import ImportStatus
from class_table_backend.persistence.db import get_session_factory

router = APIRouter(prefix="/imports", tags=["imports"])

_default_session_factory: sessionmaker[Session] | None = None


def get_session() -> Iterator[Session]:
    global _default_session_factory
    if _default_session_factory is None:
        _default_session_factory = get_session_factory()
    session = _default_session_factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


SessionDep = Annotated[Session, Depends(get_session)]


def _issue_out(data: dict[str, Any]) -> IssueOut:
    return IssueOut.model_validate(data)


def _row_out(data: dict[str, Any]) -> ImportRowOut:
    return ImportRowOut.model_validate(data)


def _meta_from_preview(preview: dict[str, Any]) -> dict[str, Any]:
    start_date = preview.get("start_date")
    if start_date is not None and not isinstance(start_date, str):
        start_date = start_date.isoformat()
    return {
        "file_hash": preview.get("file_hash"),
        "original_filename": preview.get("original_filename"),
        "profile": preview.get("profile"),
        "academic_year": preview.get("academic_year"),
        "semester_name": preview.get("semester_name"),
        "start_date": start_date,
        "class_name": preview.get("class_name"),
    }


def _preview_out(preview: dict[str, Any]) -> ImportPreviewOut:
    return ImportPreviewOut(
        import_id=preview["import_id"],
        status=str(preview["status"]),
        max_week=preview.get("max_week"),
        issues=[_issue_out(issue) for issue in preview.get("issues", [])],
        meta=_meta_from_preview(preview),
        rows=[_row_out(row) for row in preview.get("rows", [])],
    )


def _not_found(import_id: str) -> HTTPException:
    return HTTPException(status_code=404, detail=f"导入批次不存在: {import_id}")


@router.post("", response_model=ImportIngestResponse)
def ingest_import(
    session: SessionDep,
    file: Annotated[UploadFile, File()],
    max_week: Annotated[int | None, Form()] = None,
) -> ImportIngestResponse:
    # Bound the read so an oversized upload cannot exhaust memory; the service
    # still reports FILE_TOO_LARGE for anything above the limit.
    data = file.file.read(MAX_UPLOAD_BYTES + 1)
    result = ImportService(session).ingest(file.filename or "upload", data, max_week=max_week)
    return ImportIngestResponse(
        import_id=result["import_id"],
        status=str(result["status"]),
        max_week=result.get("max_week"),
        duplicate_of=result.get("duplicate_of"),
        issues=[_issue_out(issue) for issue in result.get("issues", [])],
    )


@router.get("/{import_id}", response_model=ImportPreviewOut)
def get_import(import_id: str, session: SessionDep) -> ImportPreviewOut:
    service = ImportService(session)
    try:
        preview = service.preview(import_id)
    except LookupError as exc:
        raise _not_found(import_id) from exc
    return _preview_out(preview)


@router.get("/{import_id}/rows", response_model=RowsPageOut)
def list_import_rows(
    import_id: str,
    session: SessionDep,
    limit: Annotated[int, Query(ge=1, le=500)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> RowsPageOut:
    service = ImportService(session)
    try:
        page = service.list_rows(import_id, limit=limit, offset=offset)
    except LookupError as exc:
        raise _not_found(import_id) from exc
    return RowsPageOut(
        import_id=page["import_id"],
        status=str(page["status"]),
        total=page["total"],
        limit=page["limit"],
        offset=page["offset"],
        rows=[_row_out(row) for row in page["rows"]],
    )


@router.post("/{import_id}/confirm", response_model=ConfirmOut)
def confirm_import(
    import_id: str,
    session: SessionDep,
    body: ConfirmRequest | None = None,
) -> ConfirmOut:
    service = ImportService(session)
    try:
        preview = service.preview(import_id)
    except LookupError as exc:
        raise _not_found(import_id) from exc

    if str(preview["status"]) == str(ImportStatus.FAILED):
        return ConfirmOut(
            import_id=import_id,
            status=str(ImportStatus.FAILED),
            rows_confirmed=0,
            issues=[
                IssueOut(
                    code=str(IssueCode.IMPORT_NOT_CONFIRMABLE),
                    message=f"导入批次状态为 FAILED，无法确认: {import_id}",
                    severity="error",
                )
            ],
        )

    result = service.confirm(import_id, body.row_ids if body is not None else None)
    return ConfirmOut(
        import_id=result["import_id"],
        status=str(result["status"]),
        rows_confirmed=int(result.get("rows_confirmed") or 0),
        issues=[_issue_out(issue) for issue in result.get("issues", [])],
    )
