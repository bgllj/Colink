package com.colink.app.parsing

import com.colink.app.domain.IssueCode
import com.colink.app.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekExpressionTest {

    @Test
    fun singleWeek() {
        val result = parseWeekExpression("15周")
        assertTrue(result.ok)
        assertEquals(listOf(WeekRange(start = 15, end = 15, parity = WeekParity.ALL)), result.ranges)
        assertEquals("15周", result.originalText)
    }

    @Test
    fun simpleRange() {
        val result = parseWeekExpression("2-16周")
        assertTrue(result.ok)
        assertEquals(listOf(WeekRange(start = 2, end = 16, parity = WeekParity.ALL)), result.ranges)
    }

    @Test
    fun disjointRanges() {
        val result = parseWeekExpression("1-5, 7-16周")
        assertTrue(result.ok)
        assertEquals(
            listOf(
                WeekRange(start = 1, end = 5, parity = WeekParity.ALL),
                WeekRange(start = 7, end = 16, parity = WeekParity.ALL),
            ),
            result.ranges,
        )
    }

    @Test
    fun trailingDoubleWeekAppliesToAllSegments() {
        val result = parseWeekExpression("2-4,8-16双周")
        assertTrue(result.ok)
        assertEquals(
            listOf(
                WeekRange(start = 2, end = 4, parity = WeekParity.EVEN),
                WeekRange(start = 8, end = 16, parity = WeekParity.EVEN),
            ),
            result.ranges,
        )
        assertEquals(listOf(2, 4, 8, 10, 12, 14, 16), result.expandedWeeks())
    }

    @Test
    fun singleWeekAndRangeWithDoubleWeek() {
        val result = parseWeekExpression("2,6-16双周")
        assertTrue(result.ok)
        assertEquals(
            listOf(
                WeekRange(start = 2, end = 2, parity = WeekParity.EVEN),
                WeekRange(start = 6, end = 16, parity = WeekParity.EVEN),
            ),
            result.ranges,
        )
        assertEquals(listOf(2, 6, 8, 10, 12, 14, 16), result.expandedWeeks())
    }

    @Test
    fun trailingOddWeekAppliesToAllSegments() {
        val result = parseWeekExpression("1-16单周")
        assertTrue(result.ok)
        assertEquals(listOf(WeekRange(start = 1, end = 16, parity = WeekParity.ODD)), result.ranges)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), result.expandedWeeks())
    }

    @Test
    fun listOfSingleWeeks() {
        val result = parseWeekExpression("1,3周")
        assertTrue(result.ok)
        assertEquals(
            listOf(
                WeekRange(start = 1, end = 1, parity = WeekParity.ALL),
                WeekRange(start = 3, end = 3, parity = WeekParity.ALL),
            ),
            result.ranges,
        )
    }

    @Test
    fun reversedRangeIsError() {
        val result = parseWeekExpression("16-2周")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == IssueCode.REVERSED_WEEK_RANGE })
        assertEquals(emptyList<WeekRange>(), result.ranges)
    }

    @Test
    fun unsupportedPhraseIsError() {
        val result = parseWeekExpression("隔周上课")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == IssueCode.UNSUPPORTED_WEEK_PHRASE })
    }

    @Test
    fun emptyIsError() {
        val result = parseWeekExpression("")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == IssueCode.EMPTY_WEEK_EXPRESSION })
        val resultWs = parseWeekExpression("   ")
        assertFalse(resultWs.ok)
        assertTrue(resultWs.issues.any { it.code == IssueCode.EMPTY_WEEK_EXPRESSION })
    }

    @Test
    fun originalTextPreserved() {
        val result = parseWeekExpression("3-11周")
        assertTrue(result.ok)
        assertEquals("3-11周", result.originalText)
        assertEquals(listOf(WeekRange(start = 3, end = 11, parity = WeekParity.ALL)), result.ranges)
    }

    @Test
    fun arbitraryBoundsNotHardcoded() {
        val result = parseWeekExpression("3-11周")
        assertTrue(result.ok)
        assertEquals(listOf(WeekRange(start = 3, end = 11, parity = WeekParity.ALL)), result.ranges)
        assertEquals((3..11).toList(), result.expandedWeeks())
    }

    @Test
    fun nonPositiveBoundIsError() {
        val result = parseWeekExpression("0-5周")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == IssueCode.INVALID_WEEK_BOUND })
        val resultNeg = parseWeekExpression("-3周")
        assertFalse(resultNeg.ok)
        assertTrue(resultNeg.issues.any { it.code == IssueCode.INVALID_WEEK_BOUND })
    }

    @Test
    fun unparseableSegmentIsError() {
        val result = parseWeekExpression("abc周")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == IssueCode.INVALID_WEEK_EXPRESSION })
    }

    @Test
    fun standaloneOddEvenModifiers() {
        val result = parseWeekExpression("2-4单")
        assertTrue(result.ok)
        assertEquals(listOf(WeekRange(start = 2, end = 4, parity = WeekParity.ODD)), result.ranges)
        val resultEven = parseWeekExpression("2-4双")
        assertTrue(resultEven.ok)
        assertEquals(listOf(WeekRange(start = 2, end = 4, parity = WeekParity.EVEN)), resultEven.ranges)
    }

    @Test
    fun emptySegmentIsError() {
        val result = parseWeekExpression("1,,3周")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == IssueCode.EMPTY_WEEK_EXPRESSION })
    }

    @Test
    fun emptyExpansionWarning() {
        val result = parseWeekExpression("3双周")
        assertTrue(result.ok)
        assertEquals(emptyList<Int>(), result.expandedWeeks())
        assertTrue(
            result.issues.any {
                it.code == IssueCode.EMPTY_WEEK_EXPANSION && it.severity == Severity.warning
            }
        )
        assertEquals("warning", result.issues.first { it.code == IssueCode.EMPTY_WEEK_EXPANSION }.severity.name)
        val resultOdd = parseWeekExpression("2单周")
        assertTrue(resultOdd.ok)
        assertEquals(emptyList<Int>(), resultOdd.expandedWeeks())
        assertTrue(
            resultOdd.issues.any {
                it.code == IssueCode.EMPTY_WEEK_EXPANSION && it.severity == Severity.warning
            }
        )
    }

    @Test
    fun danShuangZhouUnsupported() {
        for (text in listOf("1-16单双周", "单双周")) {
            val result = parseWeekExpression(text)
            assertFalse(result.ok)
            assertTrue(
                result.issues.any {
                    it.code == IssueCode.UNSUPPORTED_WEEK_PHRASE && it.rawText == text
                }
            )
        }
    }

    @Test
    fun expandedWeeksSortedUnique() {
        val result = parseWeekExpression("5-6,1-3")
        assertTrue(result.ok)
        assertEquals(listOf(1, 2, 3, 5, 6), result.expandedWeeks())
    }
}
