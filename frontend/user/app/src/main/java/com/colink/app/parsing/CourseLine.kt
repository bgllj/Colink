package com.colink.app.parsing

import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import com.colink.app.domain.Severity

private val COURSE_LINE_PATTERN = Regex(
    "[\\[［](?<code>[^\\]］]+)[\\]］]" +
        "(?<name>[^\\[［\\]］]+)" +
        "\\s*[\\[［](?<weeks>[^\\]］]+)[\\]］]" +
        "\\s*[\\[［](?<periods>[^\\]］]+)[\\]］]" +
        "(?:\\s*(?<room>.+))?"
)

data class ParsedCourseLine(
    val rawText: String,
    val lineIndex: Int,
    val courseCode: String?,
    val courseName: String?,
    val weekText: String?,
    val periodText: String?,
    val roomText: String?,
    val weekResult: WeekParseResult?,
    val periodResult: PeriodParseResult?,
    val issues: List<Issue> = emptyList(),
) {
    val ok: Boolean
        get() = issues.none { it.severity == Severity.error }
}

fun parseCourseLine(rawLine: String, lineIndex: Int = 0): ParsedCourseLine {
    val line = rawLine.trim()
    val match = COURSE_LINE_PATTERN.matchEntire(line)
    if (match == null) {
        return ParsedCourseLine(
            rawText = rawLine,
            lineIndex = lineIndex,
            courseCode = null,
            courseName = null,
            weekText = null,
            periodText = null,
            roomText = null,
            weekResult = null,
            periodResult = null,
            issues = listOf(
                Issue(
                    code = IssueCode.INVALID_COURSE_LINE,
                    message = "无法解析的课程行: ${pyRepr(rawLine)}",
                    rawText = rawLine,
                    lineIndex = lineIndex,
                )
            ),
        )
    }

    val courseCode = match.groups["code"]!!.value.trim()
    val courseName = match.groups["name"]!!.value.trim()
    val weekText = match.groups["weeks"]!!.value.trim()
    val periodText = match.groups["periods"]!!.value.trim()
    val roomText = match.groups["room"]?.value?.trim()

    val weekResult = parseWeekExpression(weekText)
    val periodResult = parsePeriodExpression(periodText)
    val issues = weekResult.issues + periodResult.issues

    return ParsedCourseLine(
        rawText = rawLine,
        lineIndex = lineIndex,
        courseCode = courseCode,
        courseName = courseName,
        weekText = weekText,
        periodText = periodText,
        roomText = roomText,
        weekResult = weekResult,
        periodResult = periodResult,
        issues = issues,
    )
}
