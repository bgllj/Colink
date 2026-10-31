package com.colink.app.domain

import com.colink.app.parsing.WeekParseResult
import com.colink.app.parsing.WeekRange
import java.time.LocalDate

data class SemesterMeta(
    val academicYear: String? = null,
    val semesterName: String? = null,
    val startDate: LocalDate? = null,
    val department: String? = null,
    val grade: String? = null,
    val major: String? = null,
    val className: String? = null,
    val title: String? = null,
)

data class ParsedOccurrence(
    val courseCode: String?,
    val courseName: String?,
    val weekday: Int,
    val periodStart: Int?,
    val periodEnd: Int?,
    val weekText: String?,
    val weekRanges: List<WeekRange> = emptyList(),
    val roomText: String? = null,
    val sheet: String = "",
    val coordinate: String = "",
    val lineIndex: Int = 0,
    val rawLine: String = "",
    val daypart: String? = null,
    val slotLabel: String? = null,
    val issues: List<Issue> = emptyList(),
) {
    val ok: Boolean
        get() = issues.none { it.severity == Severity.error }

    fun expandedWeeks(): List<Int> =
        WeekParseResult(originalText = weekText ?: "", ranges = weekRanges.toMutableList()).expandedWeeks()
}
