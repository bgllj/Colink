"""initial class-scoped schema

Revision ID: a1c2d3e4f5a6
Revises:
Create Date: 2026-10-10 12:00:00.000000

"""
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "a1c2d3e4f5a6"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "class",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("name", sa.String(length=128), nullable=False),
        sa.Column("grade", sa.String(length=32), nullable=True),
        sa.Column("major", sa.String(length=128), nullable=True),
        sa.Column("department", sa.String(length=128), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_class_name"), "class", ["name"], unique=True)

    op.create_table(
        "admin_user",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("username", sa.String(length=64), nullable=False),
        sa.Column("password_hash", sa.String(length=255), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_admin_user_username"), "admin_user", ["username"], unique=True)

    op.create_table(
        "import_batch",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("file_hash", sa.String(length=64), nullable=False),
        sa.Column("original_filename", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("profile", sa.String(length=64), nullable=True),
        sa.Column("academic_year", sa.String(length=32), nullable=True),
        sa.Column("semester_name", sa.String(length=64), nullable=True),
        sa.Column("start_date", sa.Date(), nullable=True),
        sa.Column("class_id", sa.String(length=36), nullable=True),
        sa.Column("class_name", sa.String(length=128), nullable=True),
        sa.Column("max_week", sa.Integer(), nullable=True),
        sa.Column("meta_json", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["class_id"], ["class.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_import_batch_file_hash"), "import_batch", ["file_hash"], unique=False)
    op.create_index(op.f("ix_import_batch_class_id"), "import_batch", ["class_id"], unique=False)

    op.create_table(
        "import_row",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("batch_id", sa.String(length=36), nullable=False),
        sa.Column("row_status", sa.String(length=32), nullable=False),
        sa.Column("selected", sa.Integer(), nullable=False),
        sa.Column("course_code", sa.String(length=64), nullable=True),
        sa.Column("course_name", sa.String(length=255), nullable=True),
        sa.Column("weekday", sa.Integer(), nullable=True),
        sa.Column("period_start", sa.Integer(), nullable=True),
        sa.Column("period_end", sa.Integer(), nullable=True),
        sa.Column("week_text", sa.String(length=255), nullable=True),
        sa.Column("week_ranges_json", sa.JSON(), nullable=True),
        sa.Column("room_text", sa.String(length=255), nullable=True),
        sa.Column("sheet", sa.String(length=255), nullable=True),
        sa.Column("coordinate", sa.String(length=32), nullable=True),
        sa.Column("line_index", sa.Integer(), nullable=True),
        sa.Column("raw_line", sa.Text(), nullable=True),
        sa.Column("issues_json", sa.JSON(), nullable=True),
        sa.ForeignKeyConstraint(["batch_id"], ["import_batch.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_import_row_batch_id"), "import_row", ["batch_id"], unique=False)

    op.create_table(
        "course",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("class_id", sa.String(length=36), nullable=False),
        sa.Column("course_code", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.ForeignKeyConstraint(["class_id"], ["class.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("class_id", "course_code", "name", name="uq_class_course_code_name"),
    )
    op.create_index(op.f("ix_course_class_id"), "course", ["class_id"], unique=False)

    op.create_table(
        "meeting_occurrence",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("course_id", sa.Integer(), nullable=False),
        sa.Column("weekday", sa.Integer(), nullable=False),
        sa.Column("period_start", sa.Integer(), nullable=True),
        sa.Column("period_end", sa.Integer(), nullable=True),
        sa.Column("week_text", sa.String(length=255), nullable=True),
        sa.Column("room_text", sa.String(length=255), nullable=True),
        sa.Column("sheet", sa.String(length=255), nullable=True),
        sa.Column("coordinate", sa.String(length=32), nullable=True),
        sa.Column("line_index", sa.Integer(), nullable=True),
        sa.Column("import_row_id", sa.String(length=36), nullable=True),
        sa.ForeignKeyConstraint(["course_id"], ["course.id"]),
        sa.ForeignKeyConstraint(["import_row_id"], ["import_row.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        op.f("ix_meeting_occurrence_course_id"),
        "meeting_occurrence",
        ["course_id"],
        unique=False,
    )

    op.create_table(
        "meeting_week",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("meeting_id", sa.Integer(), nullable=False),
        sa.Column("week_no", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(["meeting_id"], ["meeting_occurrence.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        op.f("ix_meeting_week_meeting_id"),
        "meeting_week",
        ["meeting_id"],
        unique=False,
    )
    op.create_index(op.f("ix_meeting_week_week_no"), "meeting_week", ["week_no"], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f("ix_meeting_week_week_no"), table_name="meeting_week")
    op.drop_index(op.f("ix_meeting_week_meeting_id"), table_name="meeting_week")
    op.drop_table("meeting_week")
    op.drop_index(op.f("ix_meeting_occurrence_course_id"), table_name="meeting_occurrence")
    op.drop_table("meeting_occurrence")
    op.drop_index(op.f("ix_course_class_id"), table_name="course")
    op.drop_table("course")
    op.drop_index(op.f("ix_import_row_batch_id"), table_name="import_row")
    op.drop_table("import_row")
    op.drop_index(op.f("ix_import_batch_class_id"), table_name="import_batch")
    op.drop_index(op.f("ix_import_batch_file_hash"), table_name="import_batch")
    op.drop_table("import_batch")
    op.drop_index(op.f("ix_admin_user_username"), table_name="admin_user")
    op.drop_table("admin_user")
    op.drop_index(op.f("ix_class_name"), table_name="class")
    op.drop_table("class")
