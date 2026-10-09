from __future__ import annotations

from datetime import UTC, date, datetime

from sqlalchemy import JSON, Date, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class ImportBatchRow(Base):
    __tablename__ = "import_batch"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    file_hash: Mapped[str] = mapped_column(String(64), index=True)
    original_filename: Mapped[str] = mapped_column(String(255))
    status: Mapped[str] = mapped_column(String(32))
    profile: Mapped[str | None] = mapped_column(String(64), nullable=True)
    academic_year: Mapped[str | None] = mapped_column(String(32), nullable=True)
    semester_name: Mapped[str | None] = mapped_column(String(64), nullable=True)
    start_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    class_name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    max_week: Mapped[int | None] = mapped_column(Integer, nullable=True)
    meta_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )


class ImportRowRow(Base):
    __tablename__ = "import_row"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    batch_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("import_batch.id"), index=True
    )
    row_status: Mapped[str] = mapped_column(String(32))
    selected: Mapped[int] = mapped_column(Integer, default=1)
    course_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    course_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    weekday: Mapped[int | None] = mapped_column(Integer, nullable=True)
    period_start: Mapped[int | None] = mapped_column(Integer, nullable=True)
    period_end: Mapped[int | None] = mapped_column(Integer, nullable=True)
    week_text: Mapped[str | None] = mapped_column(String(255), nullable=True)
    week_ranges_json: Mapped[list | None] = mapped_column(JSON, nullable=True)
    room_text: Mapped[str | None] = mapped_column(String(255), nullable=True)
    sheet: Mapped[str | None] = mapped_column(String(255), nullable=True)
    coordinate: Mapped[str | None] = mapped_column(String(32), nullable=True)
    line_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    raw_line: Mapped[str | None] = mapped_column(Text, nullable=True)
    issues_json: Mapped[list | None] = mapped_column(JSON, nullable=True)


class CourseRow(Base):
    __tablename__ = "course"
    __table_args__ = (UniqueConstraint("course_code", "name", name="uq_course_code_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    course_code: Mapped[str] = mapped_column(String(64))
    name: Mapped[str] = mapped_column(String(255))


class MeetingOccurrenceRow(Base):
    __tablename__ = "meeting_occurrence"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    course_id: Mapped[int] = mapped_column(Integer, ForeignKey("course.id"), index=True)
    weekday: Mapped[int] = mapped_column(Integer)
    period_start: Mapped[int | None] = mapped_column(Integer, nullable=True)
    period_end: Mapped[int | None] = mapped_column(Integer, nullable=True)
    week_text: Mapped[str | None] = mapped_column(String(255), nullable=True)
    room_text: Mapped[str | None] = mapped_column(String(255), nullable=True)
    sheet: Mapped[str | None] = mapped_column(String(255), nullable=True)
    coordinate: Mapped[str | None] = mapped_column(String(32), nullable=True)
    line_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    import_row_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("import_row.id"), nullable=True
    )


class MeetingWeekRow(Base):
    __tablename__ = "meeting_week"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    meeting_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("meeting_occurrence.id"), index=True
    )
    week_no: Mapped[int] = mapped_column(Integer, index=True)


class AdminUserRow(Base):
    __tablename__ = "admin_user"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )
