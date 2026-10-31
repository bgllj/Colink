package com.colink.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinorTest {

    @Test
    fun nameWithFullwidthMarkerIsMinor() {
        assertTrue(isMinorCourse("财务管理Ⅰ（辅修）"))
        assertTrue(isMinorCourse("硬笔书法（辅修）"))
    }

    @Test
    fun nameWithHalfwidthOrBareMarkerIsMinor() {
        assertTrue(isMinorCourse("艺术学概论(辅修)"))
        assertTrue(isMinorCourse("毛笔小楷 辅修"))
    }

    @Test
    fun normalAndEmptyNamesAreNotMinor() {
        assertFalse(isMinorCourse("高等数学"))
        assertFalse(isMinorCourse("计算机组成原理"))
        assertFalse(isMinorCourse(""))
        assertFalse(isMinorCourse(null))
        assertFalse(isMinorCourse("   "))
    }

    @Test
    fun markerIsMatchedOnStrippedName() {
        assertTrue(isMinorCourse("  财务管理Ⅰ（辅修）  "))
    }
}
