package com.colink.app.parsing

import com.colink.app.domain.IssueCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseLineTest {

    @Test
    fun fullCourseLineWithRoom() {
        val line = "[133951]中国近现代史纲要 [2-4, 8-16双周][1-2节] 主教学楼B103"
        val result = parseCourseLine(line)
        assertTrue(result.ok)
        assertEquals(line, result.rawText)
        assertEquals("133951", result.courseCode)
        assertEquals("中国近现代史纲要", result.courseName)
        assertEquals("2-4, 8-16双周", result.weekText)
        assertEquals("1-2节", result.periodText)
        assertEquals("主教学楼B103", result.roomText)
        val weekResult = result.weekResult
        assertTrue(weekResult != null && weekResult.ok)
        assertEquals(
            listOf(
                WeekRange(start = 2, end = 4, parity = WeekParity.EVEN),
                WeekRange(start = 8, end = 16, parity = WeekParity.EVEN),
            ),
            weekResult!!.ranges,
        )
        val periodResult = result.periodResult
        assertTrue(periodResult != null && periodResult.ok)
        assertEquals(PeriodRange(start = 1, end = 2), periodResult!!.range)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun leadingSpacesAndNoRoom() {
        val line = "              [193900]体育Ⅲ [2-16周][1-2节]"
        val result = parseCourseLine(line)
        assertTrue(result.ok)
        assertEquals(line, result.rawText)
        assertEquals("193900", result.courseCode)
        assertEquals("体育Ⅲ", result.courseName)
        assertEquals("2-16周", result.weekText)
        assertEquals("1-2节", result.periodText)
        assertNull(result.roomText)
    }

    @Test
    fun roomWithoutSeparatingWhitespace() {
        // 真实导出存在教室紧贴节次括号的行（26计科4 最新版样例）。
        val line = "[17302030]高等数学[9-10周][3-4节]第四教学楼513"
        val result = parseCourseLine(line)
        assertTrue(result.ok)
        assertEquals("17302030", result.courseCode)
        assertEquals("高等数学", result.courseName)
        assertEquals("9-10周", result.weekText)
        assertEquals("3-4节", result.periodText)
        assertEquals("第四教学楼513", result.roomText)

        val single = parseCourseLine("[080057]军事理论[9周][5-6节]主教学楼B303")
        assertTrue(single.ok)
        assertEquals("9周", single.weekText)
        assertEquals("主教学楼B303", single.roomText)
    }

    @Test
    fun letterCourseCode() {
        val line = "[FX3001004]财务管理Ⅰ（辅修） [1-5, 7-16周][5-7节] 主教学楼C404"
        val result = parseCourseLine(line)
        assertTrue(result.ok)
        assertEquals("FX3001004", result.courseCode)
        assertEquals("财务管理Ⅰ（辅修）", result.courseName)
        assertEquals("1-5, 7-16周", result.weekText)
        assertEquals("5-7节", result.periodText)
        assertEquals("主教学楼C404", result.roomText)
    }

    @Test
    fun nonCourseLineIsInvalid() {
        val result = parseCourseLine("注1：放假另行通知")
        assertFalse(result.ok)
        assertNull(result.weekResult)
        assertNull(result.periodResult)
        assertTrue(result.issues.any { it.code == IssueCode.INVALID_COURSE_LINE })
    }

    @Test
    fun lineIndexPreserved() {
        val result = parseCourseLine("[193900]体育Ⅲ [2-16周][1-2节]", lineIndex = 3)
        assertEquals(3, result.lineIndex)
        val default = parseCourseLine("[193900]体育Ⅲ [2-16周][1-2节]")
        assertEquals(0, default.lineIndex)
    }

    @Test
    fun fullwidthBrackets() {
        val line = "［193900］体育Ⅲ ［2-16周］［1-2节］"
        val result = parseCourseLine(line)
        assertTrue(result.ok)
        assertEquals("193900", result.courseCode)
        assertEquals("体育Ⅲ", result.courseName)
        assertEquals("2-16周", result.weekText)
        assertEquals("1-2节", result.periodText)
    }

    @Test
    fun weekAndPeriodIssuesMerged() {
        val line = "[193900]体育Ⅲ [单双周][8-5节]"
        val result = parseCourseLine(line)
        assertFalse(result.ok)
        val codes = result.issues.map { it.code }.toSet()
        assertTrue(IssueCode.UNSUPPORTED_WEEK_PHRASE in codes)
        assertTrue(IssueCode.REVERSED_PERIOD_RANGE in codes)
    }
}
