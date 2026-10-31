package com.colink.app.parsing.profiles

import com.colink.app.domain.IssueCode
import com.colink.app.parsing.MergeRange
import com.colink.app.parsing.WorkbookData
import com.colink.app.parsing.readWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class ChengduWenliV1Test {

    private fun normalWorkbook(): WorkbookData {
        // col 0: daypart (merged), col 1: slot numerals, cols 2-5: weekday columns
        val grid = listOf(
            listOf("成都文理学院2025-2026学年 第一学期课表", "", "", "", "", ""),
            listOf("2026-08-31", "", "", "", "", ""),
            listOf("院（系）：人工智能与大数据学院", "", "", "", "", ""),
            listOf("年级", "2025", "", "", "", ""),
            listOf("专业：计算机科学与技术", "", "", "", "", ""),
            listOf("班级：计算机科学与技术9", "", "", "", "", ""),
            listOf("", "节次", "星期一", "星期二", "星期三", "星期四"),
            listOf(
                "上午",
                "一",
                "[193900]体育Ⅲ [2-16周][1-2节]",
                "[133951]中国近现代史纲要 [2-4, 8-16双周][1-2节] 主教学楼B103\n[A001]高等数学 [1-16周][1-2节] 教学楼A101",
                "",
                "",
            ),
            listOf("", "二", "", "", "[B002]线性代数 [1-8周][1-2节]", ""),
            listOf("下午", "三", "", "", "", ""),
            listOf("", "四", "", "", "", ""),
        )
        val merged = listOf(
            MergeRange(rlo = 7, rhi = 8, clo = 0, chi = 1),
            MergeRange(rlo = 9, rhi = 10, clo = 0, chi = 1),
        )
        return WorkbookData(
            sheets = linkedMapOf("Sheet1" to grid),
            merged = mapOf("Sheet1" to merged),
        )
    }

    @Test
    fun normalLayoutExtractsOccurrencesWithProvenance() {
        val result = extractChengduWenliV1(normalWorkbook())
        assertTrue(result.issues.none { it.code == IssueCode.UNRECOGNIZED_LAYOUT })
        assertTrue(result.issues.none { it.code == IssueCode.MISSING_SEMESTER_START })

        val pe = result.occurrences.filter { it.courseCode == "193900" }
        assertEquals(1, pe.size)
        val sport = pe[0]
        assertEquals(1, sport.weekday)
        assertEquals(1, sport.periodStart)
        assertEquals(2, sport.periodEnd)
        assertEquals("上午", sport.daypart)
        assertEquals("一", sport.slotLabel)
        assertEquals("C8", sport.coordinate)
        assertEquals("Sheet1", sport.sheet)
        assertEquals("[193900]体育Ⅲ [2-16周][1-2节]", sport.rawLine)
        assertEquals(0, sport.lineIndex)
        assertTrue(sport.ok)

        val math = result.occurrences.single { it.courseCode == "A001" }
        assertEquals(2, math.weekday)
        assertEquals("教学楼A101", math.roomText)
        assertEquals(1, math.periodStart)
        assertEquals(2, math.periodEnd)
        assertEquals(1, math.lineIndex)

        val algebra = result.occurrences.single { it.courseCode == "B002" }
        assertEquals(3, algebra.weekday)
        assertEquals(1, algebra.periodStart)
        assertEquals(2, algebra.periodEnd)
        assertEquals("二", algebra.slotLabel)
    }

    @Test
    fun fullWidthAndNonBreakingSpacesAreIgnoredInWeekdayHeader() {
        val workbook = WorkbookData(
            sheets = linkedMapOf(
                "Sheet1" to listOf(
                    listOf("2026-08-31"),
                    listOf("节次", "星期\u3000\u00A0一"),
                    listOf("一", "[A001]高等数学 [1-4周][1-2节] 教A101"),
                ),
            ),
            merged = mapOf("Sheet1" to emptyList()),
        )

        val result = extractChengduWenliV1(workbook)

        assertTrue(result.issues.none { it.code == IssueCode.UNRECOGNIZED_LAYOUT })
        assertEquals(1, result.occurrences.single().weekday)
    }

    @Test
    fun multiLineCellProducesMultipleOccurrences() {
        val result = extractChengduWenliV1(normalWorkbook())
        val cellMates = result.occurrences.filter { it.coordinate == "D8" }
        assertEquals(2, cellMates.size)
        val history = result.occurrences.filter { it.courseCode == "133951" }
        assertEquals(1, history.size)
        val occ = history[0]
        assertEquals(0, occ.lineIndex)
        assertEquals(
            "[133951]中国近现代史纲要 [2-4, 8-16双周][1-2节] 主教学楼B103",
            occ.rawLine,
        )
        assertEquals(setOf(2, 4, 8, 10, 12, 14, 16), occ.expandedWeeks().toSet())
        assertEquals(2, occ.weekday)
        assertEquals(1, occ.periodStart)
        assertEquals(2, occ.periodEnd)

        val mate = result.occurrences.single { it.courseCode == "A001" }
        assertEquals(1, mate.lineIndex)
        assertEquals(2, mate.weekday)
        assertEquals("[A001]高等数学 [1-16周][1-2节] 教学楼A101", mate.rawLine)
    }

    @Test
    fun metaExtractionTitleDateAndLabels() {
        val result = extractChengduWenliV1(normalWorkbook())
        val meta = result.meta
        assertEquals("2025-2026", meta.academicYear)
        assertEquals("第一学期", meta.semesterName)
        assertEquals("成都文理学院2025-2026学年 第一学期课表", meta.title)
        assertEquals(LocalDate.of(2026, 8, 31), meta.startDate)
        assertEquals("人工智能与大数据学院", meta.department)
        assertEquals("2025", meta.grade)
        assertEquals("计算机科学与技术", meta.major)
        assertEquals("计算机科学与技术9", meta.className)
    }

    @Test
    fun missingHeaderReportsUnrecognizedLayout() {
        val workbook = WorkbookData(
            sheets = linkedMapOf("Sheet1" to listOf(listOf("2026-08-31"), listOf("随机说明文字"))),
            merged = mapOf("Sheet1" to emptyList()),
        )
        val result = extractChengduWenliV1(workbook)
        assertTrue(result.issues.any { it.code == IssueCode.UNRECOGNIZED_LAYOUT })
        assertTrue(result.occurrences.isEmpty())
    }

    @Test
    fun missingStartDateIsReported() {
        val workbook = WorkbookData(
            sheets = linkedMapOf("Sheet1" to listOf(listOf("成都文理学院2026-2027学年第一学期课表"))),
            merged = mapOf("Sheet1" to emptyList()),
        )
        val result = extractChengduWenliV1(workbook)
        assertTrue(result.issues.any { it.code == IssueCode.MISSING_SEMESTER_START })
    }

    @Test
    fun daypartFallsBackToSlotMappingWithoutDaypartColumn() {
        val grid = listOf(
            listOf("", "节次", "星期一"),
            listOf("", "一", "[A001]高等数学 [1-16周][1-2节] 教学楼A101"),
            listOf("", "二", "[A002]英语 [1-16周][3-4节] 教学楼A102"),
            listOf("", "三", "[A003]物理 [1-16周][5-6节] 教学楼A103"),
        )
        val workbook = WorkbookData(
            sheets = linkedMapOf("Sheet1" to grid),
            merged = mapOf("Sheet1" to emptyList()),
        )
        val result = extractChengduWenliV1(workbook)
        assertEquals("上午", result.occurrences.single { it.courseCode == "A001" }.daypart)
        assertEquals("上午", result.occurrences.single { it.courseCode == "A002" }.daypart)
        assertEquals("下午", result.occurrences.single { it.courseCode == "A003" }.daypart)
    }

    @Test
    fun invalidCourseLineKeepsRowVisibleWithIssue() {
        val grid = listOf(
            listOf("", "节次", "星期一"),
            listOf("上午", "一", "注1：放假另行通知"),
        )
        val workbook = WorkbookData(
            sheets = linkedMapOf("Sheet1" to grid),
            merged = mapOf("Sheet1" to emptyList()),
        )
        val result = extractChengduWenliV1(workbook)
        assertEquals(1, result.occurrences.size)
        val occ = result.occurrences[0]
        assertTrue(!occ.ok)
        assertTrue(occ.issues.any { it.code == IssueCode.INVALID_COURSE_LINE })
        assertTrue(result.issues.any { it.code == IssueCode.INVALID_COURSE_LINE })
    }

    @Test
    fun endToEndThroughPoiWorkbook() {
        val bytes = xlsxBytes()
        val result = extractChengduWenliV1(readWorkbook(bytes))
        assertEquals("2025-2026", result.meta.academicYear)
        assertEquals(LocalDate.of(2026, 8, 31), result.meta.startDate)
        val history = result.occurrences.single { it.courseCode == "133951" }
        assertEquals(1, history.weekday)
        assertEquals("上午", history.daypart)
        assertEquals("一", history.slotLabel)
        assertEquals("C4", history.coordinate)
        assertNotNull(history.roomText)
    }

    private fun xlsxBytes(): ByteArray {
        val book = XSSFWorkbook()
        val sheet = book.createSheet("Sheet1")
        fun cell(row: Int, col: Int): Cell {
            val existing = sheet.getRow(row) ?: sheet.createRow(row)
            return existing.getCell(col) ?: existing.createCell(col)
        }

        cell(0, 1).setCellValue("成都文理学院2025-2026学年第一学期课表")
        cell(1, 0).setCellValue("2026-08-31")
        cell(2, 1).setCellValue("节次")
        cell(2, 2).setCellValue("星期一")
        cell(2, 3).setCellValue("星期二")
        sheet.addMergedRegion(CellRangeAddress(3, 4, 0, 0))
        cell(3, 0).setCellValue("上午")
        cell(3, 1).setCellValue("一")
        cell(3, 2).setCellValue("[133951]中国近现代史纲要 [2-4, 8-16双周][1-2节] 主教学楼B103")
        cell(4, 1).setCellValue("二")
        cell(4, 3).setCellValue("[A001]高等数学 [1-16周][3-4节] 教学楼A101")
        val out = ByteArrayOutputStream()
        book.write(out)
        book.close()
        return out.toByteArray()
    }
}
