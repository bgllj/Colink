package com.colink.app.parsing

import com.colink.app.domain.IssueCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodExpressionTest {

    @Test
    fun rangeWithPeriodSuffix() {
        val result = parsePeriodExpression("1-2节")
        assertTrue(result.ok)
        assertEquals(PeriodRange(start = 1, end = 2), result.range)
        assertEquals("1-2节", result.originalText)
    }

    @Test
    fun rangeWithoutPeriodSuffix() {
        val result = parsePeriodExpression("5-8")
        assertTrue(result.ok)
        assertEquals(PeriodRange(start = 5, end = 8), result.range)
    }

    @Test
    fun singlePeriodWithSuffix() {
        val result = parsePeriodExpression("3节")
        assertTrue(result.ok)
        assertEquals(PeriodRange(start = 3, end = 3), result.range)
    }

    @Test
    fun arbitraryPositiveRange() {
        val result = parsePeriodExpression("10-12节")
        assertTrue(result.ok)
        assertEquals(PeriodRange(start = 10, end = 12), result.range)
    }

    @Test
    fun fullwidthDigitsAndHyphen() {
        val result = parsePeriodExpression("１-２节")
        assertTrue(result.ok)
        assertEquals(PeriodRange(start = 1, end = 2), result.range)
    }

    @Test
    fun reversedRangeIsError() {
        val result = parsePeriodExpression("8-5节")
        assertFalse(result.ok)
        assertNull(result.range)
        assertTrue(result.issues.any { it.code == IssueCode.REVERSED_PERIOD_RANGE })
    }

    @Test
    fun emptyIsError() {
        val result = parsePeriodExpression("")
        assertFalse(result.ok)
        assertNull(result.range)
        assertTrue(result.issues.any { it.code == IssueCode.EMPTY_PERIOD_EXPRESSION })
        val resultWs = parsePeriodExpression("   ")
        assertFalse(resultWs.ok)
        assertNull(resultWs.range)
        assertTrue(resultWs.issues.any { it.code == IssueCode.EMPTY_PERIOD_EXPRESSION })
    }

    @Test
    fun nonPositiveIsError() {
        for (text in listOf("0节", "0-2节", "-1", "3-0")) {
            val result = parsePeriodExpression(text)
            assertFalse(result.ok)
            assertNull(result.range)
            assertTrue(result.issues.any { it.code == IssueCode.INVALID_PERIOD_EXPRESSION })
        }
    }

    @Test
    fun unparseableIsError() {
        for (text in listOf("a-b节", "1-2-3节", "第一二节")) {
            val result = parsePeriodExpression(text)
            assertFalse(result.ok)
            assertNull(result.range)
            assertTrue(result.issues.any { it.code == IssueCode.INVALID_PERIOD_EXPRESSION })
        }
    }
}
