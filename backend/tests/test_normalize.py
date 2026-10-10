from class_table_backend.parsing.normalize import normalize_expression_text


def test_strips_outer_whitespace() -> None:
    assert normalize_expression_text("  1-16周  ") == "1-16周"
    assert normalize_expression_text("\t2-4,8-16\n") == "2-4,8-16"


def test_fullwidth_digits_become_ascii() -> None:
    assert normalize_expression_text("１-１６周") == "1-16周"


def test_fullwidth_hyphen_and_comma_become_ascii() -> None:
    assert normalize_expression_text("２－４，８－１６周") == "2-4,8-16周"
    assert normalize_expression_text("2—4,8～16周") == "2-4,8-16周"


def test_keeps_chinese_week_modifiers() -> None:
    assert normalize_expression_text("单周") == "单周"
    assert normalize_expression_text("双周") == "双周"
    assert normalize_expression_text("２－４双周") == "2-4双周"


def test_empty_and_whitespace_only() -> None:
    assert normalize_expression_text("") == ""
    assert normalize_expression_text("   ") == ""
