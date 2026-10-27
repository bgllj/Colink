from __future__ import annotations

from class_table_backend.domain.minor import is_minor_course


def test_name_with_fullwidth_marker_is_minor() -> None:
    assert is_minor_course("财务管理Ⅰ（辅修）")
    assert is_minor_course("硬笔书法（辅修）")


def test_name_with_halfwidth_or_bare_marker_is_minor() -> None:
    assert is_minor_course("艺术学概论(辅修)")
    assert is_minor_course("毛笔小楷 辅修")


def test_normal_and_empty_names_are_not_minor() -> None:
    assert not is_minor_course("高等数学")
    assert not is_minor_course("计算机组成原理")
    assert not is_minor_course("")
    assert not is_minor_course(None)
    assert not is_minor_course("   ")


def test_marker_is_matched_on_stripped_name() -> None:
    assert is_minor_course("  财务管理Ⅰ（辅修）  ")
