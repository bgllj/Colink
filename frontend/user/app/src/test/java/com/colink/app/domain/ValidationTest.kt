package com.colink.app.domain

import com.colink.app.parsing.WeekParity
import com.colink.app.parsing.WeekRange
import com.colink.app.parsing.profiles.ExtractionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private fun occurrence(
        weekRanges: List<WeekRange>,
        weekText: String = "1-5周",
        rawLine: String = "测试课程 1-5周",
        daypart: String? = null,
        periodStart: Int? = 1,
        periodEnd: Int? = 2,
        sheet: String = "Sheet1",
        coordinate: String = "B3",
        lineIndex: Int = 1,
    ): ParsedOccurrence = ParsedOccurrence(
        courseCode = null,
        courseName = "测试课程",
        weekday = 1,
        periodStart = periodStart,
        periodEnd = periodEnd,
        weekText = weekText,
        weekRanges = weekRanges,
        sheet = sheet,
        coordinate = coordinate,
        lineIndex = lineIndex,
        rawLine = rawLine,
        daypart = daypart,
    )

    @Test
    fun maxWeekDefaultsToWeekRangeUpperBound() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(
                occurrence(listOf(WeekRange(1, 5)), weekText = "1-5周", rawLine = "甲 1-5周"),
                occurrence(listOf(WeekRange(7, 16)), weekText = "7-16周", rawLine = "乙 7-16周"),
            )
        )
        val (occurrences, issues, effectiveMaxWeek) = validateExtraction(extraction)
        assertEquals(16, effectiveMaxWeek)
        assertEquals(extraction.occurrences, occurrences)
        assertTrue(issues.none { it.code == IssueCode.MISSING_MAX_WEEK })
        assertTrue(issues.none { it.code == IssueCode.WEEK_OUT_OF_RANGE })
    }

    @Test
    fun maxWeekOverrideKeepsConfiguredBound() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(
                occurrence(listOf(WeekRange(1, 17)), weekText = "1-17周", rawLine = "甲 1-17周")
            )
        )
        val (_, issues, effectiveMaxWeek) = validateExtraction(extraction, ValidationConfig(maxWeek = 16))
        assertEquals(16, effectiveMaxWeek)
        val outOfRange = issues.filter { it.code == IssueCode.WEEK_OUT_OF_RANGE }
        assertEquals(1, outOfRange.size)
        val issue = outOfRange[0]
        assertEquals(Severity.warning, issue.severity)
        assertEquals("Sheet1", issue.sheet)
        assertEquals("B3", issue.coordinate)
        assertEquals(1, issue.lineIndex)
        assertEquals("甲 1-17周", issue.rawText)
        assertTrue(issues.none { it.code == IssueCode.MISSING_MAX_WEEK })
    }

    @Test
    fun periodGridMismatchWarnsWhenDaypartHintConflicts() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(
                occurrence(
                    listOf(WeekRange(1, 16)),
                    weekText = "1-16周",
                    daypart = "上午",
                    periodStart = 10,
                    periodEnd = 11,
                    rawLine = "甲 10-11节",
                )
            )
        )
        val (_, issues, _) = validateExtraction(extraction)
        val mismatches = issues.filter { it.code == IssueCode.PERIOD_GRID_MISMATCH }
        assertEquals(1, mismatches.size)
        val issue = mismatches[0]
        assertEquals(Severity.warning, issue.severity)
        assertEquals("Sheet1", issue.sheet)
        assertEquals("B3", issue.coordinate)
        assertEquals(1, issue.lineIndex)
        assertEquals("甲 10-11节", issue.rawText)
    }

    @Test
    fun emptyWeeksWithoutOverrideReportsMissingMaxWeek() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(occurrence(emptyList(), weekText = ""))
        )
        val (occurrences, issues, effectiveMaxWeek) = validateExtraction(extraction)
        assertEquals(0, effectiveMaxWeek)
        assertEquals(extraction.occurrences, occurrences)
        val missing = issues.filter { it.code == IssueCode.MISSING_MAX_WEEK }
        assertEquals(1, missing.size)
        assertEquals(Severity.error, missing[0].severity)
    }

    @Test
    fun extractionIssuesAreCollectedWithValidationIssues() {
        val preexisting = Issue(
            code = IssueCode.UNRECOGNIZED_LAYOUT,
            message = "layout",
            sheet = "Sheet1",
        )
        val extraction = ExtractionResult(
            occurrences = mutableListOf(occurrence(emptyList(), weekText = "")),
            issues = mutableListOf(preexisting),
        )
        val (_, issues, _) = validateExtraction(extraction)
        assertTrue(preexisting in issues)
        assertTrue(issues.any { it.code == IssueCode.MISSING_MAX_WEEK })
    }

    @Test
    fun overrideSuppressesMissingMaxWeekWhenNoWeeks() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(occurrence(emptyList(), weekText = ""))
        )
        val (_, issues, effectiveMaxWeek) = validateExtraction(extraction, ValidationConfig(maxWeek = 8))
        assertEquals(8, effectiveMaxWeek)
        assertTrue(issues.none { it.code == IssueCode.MISSING_MAX_WEEK })
    }

    @Test
    fun matchingDaypartAndPeriodsProduceNoMismatch() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(
                occurrence(
                    listOf(WeekRange(1, 16)),
                    weekText = "1-16周",
                    daypart = "上午",
                    periodStart = 2,
                    periodEnd = 3,
                ),
                occurrence(
                    listOf(WeekRange(1, 16)),
                    weekText = "1-16周",
                    daypart = "晚上",
                    periodStart = 11,
                    periodEnd = 12,
                    coordinate = "C4",
                ),
            )
        )
        val (_, issues, _) = validateExtraction(extraction)
        assertTrue(issues.none { it.code == IssueCode.PERIOD_GRID_MISMATCH })
    }

    @Test
    fun outOfRangeWarningPerRange() {
        val extraction = ExtractionResult(
            occurrences = mutableListOf(
                occurrence(
                    listOf(
                        WeekRange(1, 5, parity = WeekParity.ODD),
                        WeekRange(7, 18, parity = WeekParity.ODD),
                    ),
                    weekText = "1-5,7-18单周",
                    rawLine = "甲 1-5,7-18单周",
                )
            )
        )
        val (_, issues, effectiveMaxWeek) = validateExtraction(extraction, ValidationConfig(maxWeek = 16))
        assertEquals(16, effectiveMaxWeek)
        val outOfRange = issues.filter { it.code == IssueCode.WEEK_OUT_OF_RANGE }
        assertEquals(1, outOfRange.size)
        assertEquals(7, outOfRange[0].details["range_start"])
        assertEquals(18, outOfRange[0].details["range_end"])
    }
}
