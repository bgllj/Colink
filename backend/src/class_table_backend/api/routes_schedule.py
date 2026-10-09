from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Query

from class_table_backend.api.routes_imports import SessionDep
from class_table_backend.api.schemas import (
    ScheduleCourseOut,
    ScheduleOut,
    ScheduleSemesterOut,
)
from class_table_backend.persistence.repositories import ScheduleRepository

router = APIRouter(prefix="/schedule", tags=["schedule"])


@router.get("", response_model=ScheduleOut)
def get_schedule(
    session: SessionDep,
    week: Annotated[int | None, Query(ge=1)] = None,
) -> ScheduleOut:
    repo = ScheduleRepository(session)
    courses = repo.load_schedule(week=week)
    return ScheduleOut(
        courses=[ScheduleCourseOut.model_validate(course) for course in courses],
        semester=ScheduleSemesterOut.model_validate(repo.load_semester_meta()),
    )
