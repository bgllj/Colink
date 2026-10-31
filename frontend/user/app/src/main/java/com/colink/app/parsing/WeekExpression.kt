package com.colink.app.parsing

import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import com.colink.app.domain.Severity

internal fun pyRepr(text: String): String {
    return if ('"' !in text && '\'' in text) {
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } else {
        "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}

private val UNSUPPORTED_PHRASES = listOf(
    "单双周",
    "隔周",
    "前半学期",
    "后半学期",
    "按通知",
    "节假日",
    "调课",
    "另行",
)

private val TRAILING_MODIFIER_PATTERN = Regex("(单周|双周|单|双)$")
private val SEGMENT_PATTERN = Regex("^(-?\\d+)(?:-(-?\\d+))?$")

enum class WeekParity {
    ALL,
    ODD,
    EVEN,
}

data class WeekRange(
    val start: Int,
    val end: Int,
    val parity: WeekParity = WeekParity.ALL,
)

data class WeekParseResult(
    val originalText: String,
    val ranges: MutableList<WeekRange> = mutableListOf(),
    val issues: MutableList<Issue> = mutableListOf(),
) {
    val ok: Boolean
        get() = issues.none { it.severity == Severity.error }

    fun expandedWeeks(): List<Int> {
        val weeks = mutableSetOf<Int>()
        for (weekRange in ranges) {
            val step: Int
            val start: Int
            when (weekRange.parity) {
                WeekParity.ODD -> {
                    start = if (weekRange.start % 2 == 1) weekRange.start else weekRange.start + 1
                    step = 2
                }
                WeekParity.EVEN -> {
                    start = if (weekRange.start % 2 == 0) weekRange.start else weekRange.start + 1
                    step = 2
                }
                WeekParity.ALL -> {
                    start = weekRange.start
                    step = 1
                }
            }
            var week = start
            while (week <= weekRange.end) {
                weeks.add(week)
                week += step
            }
        }
        return weeks.sorted()
    }
}

private sealed interface SegmentParse {
    data class Parsed(val range: WeekRange) : SegmentParse
    data class Failed(val issue: Issue) : SegmentParse
}

private fun parseSegment(segment: String): SegmentParse {
    val match = SEGMENT_PATTERN.matchEntire(segment)
        ?: return SegmentParse.Failed(
            Issue(
                code = IssueCode.INVALID_WEEK_EXPRESSION,
                message = "无法解析的周次片段: ${pyRepr(segment)}",
                rawText = segment,
            )
        )
    val start = match.groupValues[1].toInt()
    val end = match.groups[2]?.value?.toInt() ?: start
    if (start <= 0 || end <= 0) {
        return SegmentParse.Failed(
            Issue(
                code = IssueCode.INVALID_WEEK_BOUND,
                message = "周次必须为正整数: ${pyRepr(segment)}",
                rawText = segment,
                details = mapOf("start" to start, "end" to end),
            )
        )
    }
    if (start > end) {
        return SegmentParse.Failed(
            Issue(
                code = IssueCode.REVERSED_WEEK_RANGE,
                message = "周次区间起始大于结束: ${pyRepr(segment)}",
                rawText = segment,
                details = mapOf("start" to start, "end" to end),
            )
        )
    }
    return SegmentParse.Parsed(WeekRange(start = start, end = end, parity = WeekParity.ALL))
}

fun parseWeekExpression(text: String): WeekParseResult {
    val originalText = text
    val normalized = normalizeExpressionText(text)

    if (normalized.isEmpty()) {
        return WeekParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.EMPTY_WEEK_EXPRESSION,
                    message = "周次表达式为空",
                    rawText = originalText,
                )
            ),
        )
    }

    for (phrase in UNSUPPORTED_PHRASES) {
        if (phrase in normalized) {
            return WeekParseResult(
                originalText = originalText,
                issues = mutableListOf(
                    Issue(
                        code = IssueCode.UNSUPPORTED_WEEK_PHRASE,
                        message = "不支持的周次表述: $phrase",
                        rawText = originalText,
                        details = mapOf("phrase" to phrase),
                    )
                ),
            )
        }
    }

    var body = normalized
    var parity = WeekParity.ALL

    val modifierMatch = TRAILING_MODIFIER_PATTERN.find(body)
    if (modifierMatch != null) {
        val token = modifierMatch.groupValues[1]
        parity = if (token == "单周" || token == "单") WeekParity.ODD else WeekParity.EVEN
        body = body.substring(0, modifierMatch.range.first)
    }

    if (body.endsWith("周")) {
        body = body.dropLast(1)
    }

    if (body.isEmpty()) {
        return WeekParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.EMPTY_WEEK_EXPRESSION,
                    message = "周次表达式为空",
                    rawText = originalText,
                )
            ),
        )
    }

    val ranges = mutableListOf<WeekRange>()
    val issues = mutableListOf<Issue>()
    for (rawSegment in body.split(",")) {
        val segment = rawSegment.trim()
        if (segment.isEmpty()) {
            issues.add(
                Issue(
                    code = IssueCode.EMPTY_WEEK_EXPRESSION,
                    message = "周次片段为空",
                    rawText = rawSegment,
                )
            )
            continue
        }
        when (val parsed = parseSegment(segment)) {
            is SegmentParse.Failed -> issues.add(parsed.issue)
            is SegmentParse.Parsed -> ranges.add(
                WeekRange(start = parsed.range.start, end = parsed.range.end, parity = parity)
            )
        }
    }

    if (ranges.isEmpty() && issues.isEmpty()) {
        return WeekParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.EMPTY_WEEK_EXPRESSION,
                    message = "周次表达式为空",
                    rawText = originalText,
                )
            ),
        )
    }

    val result = WeekParseResult(originalText = originalText, ranges = ranges, issues = issues)
    if (result.ok && result.expandedWeeks().isEmpty()) {
        result.issues.add(
            Issue(
                code = IssueCode.EMPTY_WEEK_EXPANSION,
                message = "周次表达式展开后为空",
                severity = Severity.warning,
                rawText = originalText,
            )
        )
    }
    return result
}
