package com.colink.app.domain

import com.colink.app.parsing.profiles.ExtractionResult
import com.colink.app.parsing.pyRepr

private val DAYPART_PERIOD_HINTS: Map<String, Pair<Int, Int>> = mapOf(
    "上午" to (1 to 4),
    "下午" to (5 to 9),
    "晚上" to (10 to 12),
    "晚间" to (10 to 12),
)

data class ValidationConfig(
    val maxWeek: Int? = null,
)

data class ValidationResult(
    val occurrences: List<ParsedOccurrence>,
    val issues: List<Issue>,
    val effectiveMaxWeek: Int,
)

private fun deriveMaxWeek(occurrences: List<ParsedOccurrence>): Int {
    val weekEnds = occurrences.flatMap { occurrence -> occurrence.weekRanges.map { it.end } }
    return weekEnds.maxOrNull() ?: 0
}

fun validateExtraction(
    extraction: ExtractionResult,
    config: ValidationConfig? = null,
): ValidationResult {
    val cfg = config ?: ValidationConfig()
    val newIssues = mutableListOf<Issue>()

    val effectiveMaxWeek: Int
    if (cfg.maxWeek != null) {
        effectiveMaxWeek = cfg.maxWeek
    } else {
        effectiveMaxWeek = deriveMaxWeek(extraction.occurrences)
        if (effectiveMaxWeek == 0) {
            newIssues.add(
                Issue(
                    code = IssueCode.MISSING_MAX_WEEK,
                    message = "无法从周次表达式推导最大学期周次，且未提供导入配置覆盖",
                    severity = Severity.error,
                )
            )
        }
    }

    if (effectiveMaxWeek > 0) {
        for (occurrence in extraction.occurrences) {
            for (weekRange in occurrence.weekRanges) {
                if (weekRange.end > effectiveMaxWeek) {
                    newIssues.add(
                        Issue(
                            code = IssueCode.WEEK_OUT_OF_RANGE,
                            message = "周次区间 ${weekRange.start}-${weekRange.end} 超出最大学期周次 $effectiveMaxWeek",
                            severity = Severity.warning,
                            sheet = occurrence.sheet,
                            coordinate = occurrence.coordinate,
                            lineIndex = occurrence.lineIndex,
                            rawText = occurrence.rawLine,
                            details = mapOf(
                                "range_start" to weekRange.start,
                                "range_end" to weekRange.end,
                                "max_week" to effectiveMaxWeek,
                            ),
                        )
                    )
                }
            }
        }
    }

    for (occurrence in extraction.occurrences) {
        val hint = DAYPART_PERIOD_HINTS[occurrence.daypart ?: ""] ?: continue
        if (occurrence.periodStart == null) continue
        val (hintStart, hintEnd) = hint
        if (occurrence.periodStart in hintStart..hintEnd) continue
        newIssues.add(
            Issue(
                code = IssueCode.PERIOD_GRID_MISMATCH,
                message = "时段 ${pyRepr(occurrence.daypart!!)} 的节次 ${occurrence.periodStart} " +
                    "不在常规范围 $hintStart-$hintEnd 内；以文本节次为准",
                severity = Severity.warning,
                sheet = occurrence.sheet,
                coordinate = occurrence.coordinate,
                lineIndex = occurrence.lineIndex,
                rawText = occurrence.rawLine,
                details = mapOf(
                    "daypart" to occurrence.daypart,
                    "period_start" to occurrence.periodStart,
                    "period_end" to occurrence.periodEnd,
                    "hint_start" to hintStart,
                    "hint_end" to hintEnd,
                ),
            )
        )
    }

    val allIssues = extraction.issues.toList() + newIssues
    return ValidationResult(extraction.occurrences, allIssues, effectiveMaxWeek)
}
