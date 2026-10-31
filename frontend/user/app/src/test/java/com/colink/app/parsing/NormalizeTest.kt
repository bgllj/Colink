package com.colink.app.parsing

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeTest {

    @Test
    fun stripsOuterWhitespace() {
        assertEquals("1-16周", normalizeExpressionText("  1-16周  "))
        assertEquals("2-4,8-16", normalizeExpressionText("\t2-4,8-16\n"))
    }

    @Test
    fun fullwidthDigitsBecomeAscii() {
        assertEquals("1-16周", normalizeExpressionText("１-１６周"))
    }

    @Test
    fun fullwidthHyphenAndCommaBecomeAscii() {
        assertEquals("2-4,8-16周", normalizeExpressionText("２－４，８－１６周"))
        assertEquals("2-4,8-16周", normalizeExpressionText("2—4,8～16周"))
    }

    @Test
    fun keepsChineseWeekModifiers() {
        assertEquals("单周", normalizeExpressionText("单周"))
        assertEquals("双周", normalizeExpressionText("双周"))
        assertEquals("2-4双周", normalizeExpressionText("２－４双周"))
    }

    @Test
    fun emptyAndWhitespaceOnly() {
        assertEquals("", normalizeExpressionText(""))
        assertEquals("", normalizeExpressionText("   "))
    }
}
