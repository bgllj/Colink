package com.colink.app.domain

enum class IssueCode {
    EMPTY_WEEK_EXPRESSION,
    EMPTY_WEEK_EXPANSION,
    INVALID_WEEK_EXPRESSION,
    UNSUPPORTED_WEEK_PHRASE,
    REVERSED_WEEK_RANGE,
    INVALID_WEEK_BOUND,
    EMPTY_PERIOD_EXPRESSION,
    INVALID_PERIOD_EXPRESSION,
    REVERSED_PERIOD_RANGE,
    INVALID_COURSE_LINE,
    UNRECOGNIZED_LAYOUT,
    MISSING_SEMESTER_START,
    MISSING_MAX_WEEK,
    WEEK_OUT_OF_RANGE,
    PERIOD_GRID_MISMATCH,
    UNSUPPORTED_FILE_TYPE,
    MALFORMED_WORKBOOK,
    FILE_TOO_LARGE,
    DUPLICATE_CONFIRM,
    IMPORT_NOT_CONFIRMABLE,
    // Emitted by the backend confirm path (ImportConfirmError), not in issues.py.
    SCHEDULE_EXISTS,
    MINOR_COURSE_EXCLUDED,
}

// Entry names stay lowercase so the name itself is the display/wire form.
enum class Severity {
    error,
    warning,
    info,
}

data class Issue(
    val code: IssueCode,
    val message: String,
    val severity: Severity = Severity.error,
    val sheet: String? = null,
    val coordinate: String? = null,
    val lineIndex: Int? = null,
    val rawText: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)
