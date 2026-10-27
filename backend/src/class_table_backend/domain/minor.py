from __future__ import annotations

_MINOR_MARKER = "辅修"


def is_minor_course(course_name: str | None) -> bool:
    """课程名包含「辅修」即视为辅修课程（仅看名称，不依赖课程号前缀）。"""
    if not course_name:
        return False
    return _MINOR_MARKER in course_name.strip()
