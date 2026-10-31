package com.colink.app.parsing

import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import com.colink.app.domain.Severity

private val SEGMENT_PATTERN = Regex("^(-?\\d+)(?:-(-?\\d+))?$")

data class PeriodRange(
    val start: Int,
    val end: Int,
)

data class PeriodParseResult(
    val originalText: String,
    val range: PeriodRange? = null,
    val issues: MutableList<Issue> = mutableListOf(),
) {
    val ok: Boolean
        get() = issues.none { it.severity == Severity.error }
}

fun parsePeriodExpression(text: String): PeriodParseResult {
    val originalText = text
    val normalized = normalizeExpressionText(text)

    if (normalized.isEmpty()) {
        return PeriodParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.EMPTY_PERIOD_EXPRESSION,
                    message = "节次表达式为空",
                    rawText = originalText,
                )
            ),
        )
    }

    var body = normalized
    if (body.endsWith("节")) {
        body = body.dropLast(1)
    }

    if (body.isEmpty()) {
        return PeriodParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.EMPTY_PERIOD_EXPRESSION,
                    message = "节次表达式为空",
                    rawText = originalText,
                )
            ),
        )
    }

    val match = SEGMENT_PATTERN.matchEntire(body)
    if (match == null) {
        return PeriodParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.INVALID_PERIOD_EXPRESSION,
                    message = "无法解析的节次表达式: ${pyRepr(body)}",
                    rawText = originalText,
                )
            ),
        )
    }

    val start = match.groupValues[1].toInt()
    val end = match.groups[2]?.value?.toInt() ?: start

    if (start <= 0 || end <= 0) {
        return PeriodParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.INVALID_PERIOD_EXPRESSION,
                    message = "节次必须为正整数: ${pyRepr(body)}",
                    rawText = originalText,
                    details = mapOf("start" to start, "end" to end),
                )
            ),
        )
    }

    if (start > end) {
        return PeriodParseResult(
            originalText = originalText,
            issues = mutableListOf(
                Issue(
                    code = IssueCode.REVERSED_PERIOD_RANGE,
                    message = "节次区间起始大于结束: ${pyRepr(body)}",
                    rawText = originalText,
                    details = mapOf("start" to start, "end" to end),
                )
            ),
        )
    }

    return PeriodParseResult(originalText = originalText, range = PeriodRange(start = start, end = end))
}
