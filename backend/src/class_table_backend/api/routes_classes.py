from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from class_table_backend.api.deps import get_session
from class_table_backend.api.schemas import (
    ClassCreateIn,
    ClassOut,
    ClassUpdateIn,
    ScheduleCourseOut,
    ScheduleOut,
    ScheduleSemesterOut,
    SemesterUpdateIn,
)
from class_table_backend.auth.dependencies import require_admin
from class_table_backend.persistence.repositories import (
    ClassRepository,
    ImportConfirmError,
    ScheduleRepository,
)

SessionDep = Annotated[Session, Depends(get_session)]

public_router = APIRouter(prefix="/classes", tags=["classes"])
admin_router = APIRouter(
    prefix="/classes",
    tags=["classes-admin"],
    dependencies=[Depends(require_admin)],
)


def _class_out(row) -> ClassOut:
    return ClassOut(
        id=row.id,
        name=row.name,
        grade=row.grade,
        major=row.major,
        department=row.department,
    )


@public_router.get("", response_model=list[ClassOut])
def list_classes(session: SessionDep) -> list[ClassOut]:
    return [_class_out(row) for row in ClassRepository(session).list_all()]


@public_router.get("/{class_id}/schedule", response_model=ScheduleOut)
def get_class_schedule(
    class_id: str,
    session: SessionDep,
    week: Annotated[int | None, Query(ge=1)] = None,
) -> ScheduleOut:
    classes = ClassRepository(session)
    cls = classes.get_by_id(class_id)
    if cls is None:
        raise HTTPException(status_code=404, detail=f"班级不存在: {class_id}")
    repo = ScheduleRepository(session)
    courses = repo.load_schedule(class_id, week=week)
    return ScheduleOut(
        class_id=cls.id,
        class_name=cls.name,
        courses=[ScheduleCourseOut.model_validate(course) for course in courses],
        semester=ScheduleSemesterOut.model_validate(repo.load_semester_meta(class_id)),
    )


@admin_router.post("", response_model=ClassOut, status_code=201)
def create_class(body: ClassCreateIn, session: SessionDep) -> ClassOut:
    classes = ClassRepository(session)
    if classes.get_by_name(body.name) is not None:
        raise HTTPException(status_code=409, detail=f"班级已存在: {body.name}")
    row = classes.create(
        name=body.name,
        grade=body.grade,
        major=body.major,
        department=body.department,
    )
    return _class_out(row)


@admin_router.get("", response_model=list[ClassOut])
def admin_list_classes(session: SessionDep) -> list[ClassOut]:
    return [_class_out(row) for row in ClassRepository(session).list_all()]


@admin_router.patch("/{class_id}", response_model=ClassOut)
def update_class(
    class_id: str,
    body: ClassUpdateIn,
    session: SessionDep,
) -> ClassOut:
    classes = ClassRepository(session)
    row = classes.get_by_id(class_id)
    if row is None:
        raise HTTPException(status_code=404, detail=f"班级不存在: {class_id}")
    if body.name is not None and body.name != row.name:
        if classes.get_by_name(body.name) is not None:
            raise HTTPException(status_code=409, detail=f"班级已存在: {body.name}")
        row.name = body.name
    if body.grade is not None:
        row.grade = body.grade
    if body.major is not None:
        row.major = body.major
    if body.department is not None:
        row.department = body.department
    session.flush()
    return _class_out(row)


@admin_router.patch("/{class_id}/semester", response_model=ScheduleSemesterOut)
def update_class_semester(
    class_id: str,
    body: SemesterUpdateIn,
    session: SessionDep,
) -> ScheduleSemesterOut:
    classes = ClassRepository(session)
    if classes.get_by_id(class_id) is None:
        raise HTTPException(status_code=404, detail=f"班级不存在: {class_id}")
    try:
        meta = ScheduleRepository(session).update_start_date(
            class_id, body.start_date
        )
    except ImportConfirmError as exc:
        raise HTTPException(status_code=404, detail=exc.message) from exc
    return ScheduleSemesterOut.model_validate(meta)


@admin_router.delete("/{class_id}", status_code=204)
def delete_class(class_id: str, session: SessionDep) -> None:
    try:
        ClassRepository(session).delete(class_id)
    except ImportConfirmError as exc:
        raise HTTPException(status_code=404, detail=exc.message) from exc
