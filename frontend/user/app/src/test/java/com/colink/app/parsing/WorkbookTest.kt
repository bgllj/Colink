package com.colink.app.parsing

import com.colink.app.domain.IssueCode
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

class WorkbookTest {

    private fun xlsBytes(build: (HSSFWorkbook) -> Unit): ByteArray {
        val book = HSSFWorkbook()
        build(book)
        val out = ByteArrayOutputStream()
        book.write(out)
        book.close()
        return out.toByteArray()
    }

    private fun xlsxBytes(build: (XSSFWorkbook) -> Unit): ByteArray {
        val book = XSSFWorkbook()
        build(book)
        val out = ByteArrayOutputStream()
        book.write(out)
        book.close()
        return out.toByteArray()
    }

    private fun readIssue(data: ByteArray): com.colink.app.domain.Issue {
        try {
            readWorkbook(data)
            fail("expected WorkbookReadError")
            throw AssertionError()
        } catch (exc: WorkbookReadError) {
            return exc.issue
        }
    }

    @Test
    fun detectXlsxAndUnknownMagic() {
        assertEquals("xlsx", detectFormat(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + "rest-of-zip".toByteArray()))
        assertNull(detectFormat("not-a-workbook".toByteArray()))
        assertNull(detectFormat(ByteArray(0)))
    }

    @Test
    fun detectXlsMagicOnSyntheticXls() {
        val bytes = xlsBytes { book -> book.createSheet("Sheet1") }
        assertEquals("xls", detectFormat(bytes))
    }

    @Test
    fun a1CoordinateTest() {
        assertEquals("A1", a1Coordinate(0, 0))
        assertEquals("D3", a1Coordinate(2, 3))
        assertEquals("AA1", a1Coordinate(0, 26))
    }

    @Test
    fun readWorkbookRejectsUnsupportedBytes() {
        val issue = readIssue("plain text, not a workbook".toByteArray())
        assertEquals(IssueCode.UNSUPPORTED_FILE_TYPE, issue.code)
    }

    @Test
    fun readWorkbookRejectsEmptyBytes() {
        val issue = readIssue(ByteArray(0))
        assertEquals(IssueCode.MALFORMED_WORKBOOK, issue.code)
    }

    @Test
    fun readWorkbookRejectsGarbageXlsxMagic() {
        val garbage = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + "garbage".toByteArray()
        val issue = readIssue(garbage)
        assertEquals(IssueCode.MALFORMED_WORKBOOK, issue.code)
    }

    @Test
    fun readWorkbookRejectsGarbageOle2Magic() {
        val garbage = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) + "garbage".toByteArray()
        val issue = readIssue(garbage)
        assertEquals(IssueCode.MALFORMED_WORKBOOK, issue.code)
    }

    @Test
    fun readSyntheticXlsxRoundTrip() {
        val bytes = xlsxBytes { book ->
            val sheet = book.createSheet("Timetable")
            sheet.createRow(0).createCell(1).setCellValue("标题行")
            val row2 = sheet.createRow(2)
            row2.createCell(2).setCellValue("星期一")
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(2, 2, 2, 3))
            sheet.createRow(3).createCell(2).setCellValue("课程内容")
        }
        val workbook = readWorkbook(bytes)
        assertEquals("xlsx", detectFormat(bytes))
        val grid = workbook.sheets["Timetable"]!!
        assertEquals("标题行", grid[0][1])
        assertEquals("星期一", grid[2][2])
        assertEquals("课程内容", grid[3][2])
        assertTrue(MergeRange(rlo = 2, rhi = 3, clo = 2, chi = 4) in workbook.merged["Timetable"]!!)
    }

    @Test
    fun readSyntheticXlsRoundTripWithMergedAndMultiline() {
        val bytes = xlsBytes { book ->
            val sheet = book.createSheet("Grid")
            val row0 = sheet.createRow(0)
            row0.createCell(0).setCellValue("日期标题")
            row0.createCell(1).setCellValue("[133951]中国近现代史纲要 [2-4周][1-2节] 主教学楼B103\n[193900]体育Ⅲ [2-16周][1-2节]")
            val row1 = sheet.createRow(1)
            row1.createCell(0).setCellValue("上午")
            row1.createCell(1).setCellValue("一")
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(1, 2, 0, 0))
            sheet.createRow(2).createCell(1).setCellValue("二")
        }
        val workbook = readWorkbook(bytes)
        val grid = workbook.sheets["Grid"]!!
        assertEquals("日期标题", grid[0][0])
        assertEquals(
            "[133951]中国近现代史纲要 [2-4周][1-2节] 主教学楼B103\n[193900]体育Ⅲ [2-16周][1-2节]",
            grid[0][1],
        )
        assertEquals("上午", grid[1][0])
        assertEquals("一", grid[1][1])
        assertEquals("", grid[2][0])
        assertEquals("二", grid[2][1])
        assertTrue(MergeRange(rlo = 1, rhi = 3, clo = 0, chi = 1) in workbook.merged["Grid"]!!)
    }

    @Test
    fun stringifyBooleanDateFloatAndFormulaCells() {
        val bytes = xlsxBytes { book ->
            val sheet = book.createSheet("Types")
            val row = sheet.createRow(0)
            row.createCell(0).setCellValue(true)
            row.createCell(1).setCellValue(false)

            val dateStyle = book.createCellStyle()
            dateStyle.dataFormat = book.creationHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")
            val midnight = row.createCell(2)
            midnight.cellStyle = dateStyle
            midnight.setCellValue(Date.from(LocalDateTime.of(2026, 8, 31, 0, 0).atZone(ZoneId.systemDefault()).toInstant()))
            val withTime = row.createCell(3)
            withTime.cellStyle = dateStyle
            withTime.setCellValue(Date.from(LocalDateTime.of(2026, 9, 1, 8, 30, 15).atZone(ZoneId.systemDefault()).toInstant()))

            row.createCell(4).setCellValue(2.5)
            row.createCell(5).setCellValue(3.0)

            val formula = row.createCell(6)
            formula.cellFormula = "1+2"
            book.creationHelper.createFormulaEvaluator().evaluateFormulaCell(formula)

            row.createCell(7).setCellValue("text")
        }
        val workbook = readWorkbook(bytes)
        val grid = workbook.sheets["Types"]!!
        assertEquals("TRUE", grid[0][0])
        assertEquals("FALSE", grid[0][1])
        assertEquals("2026-08-31", grid[0][2])
        assertEquals("2026-09-01 08:30:15", grid[0][3])
        assertEquals("2.5", grid[0][4])
        assertEquals("3", grid[0][5])
        assertEquals("3", grid[0][6])
        assertEquals("text", grid[0][7])
    }

    @Test
    fun xlsDateFloatAndFormulaCells() {
        val bytes = xlsBytes { book ->
            val sheet = book.createSheet("Types")
            val row = sheet.createRow(0)
            val dateStyle = book.createCellStyle()
            dateStyle.dataFormat = book.creationHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")
            val midnight = row.createCell(0)
            midnight.cellStyle = dateStyle
            midnight.setCellValue(Date.from(LocalDateTime.of(2026, 8, 31, 0, 0).atZone(ZoneId.systemDefault()).toInstant()))
            row.createCell(1).setCellValue(2.5)
            row.createCell(2).setCellValue(3.0)
            val formula = row.createCell(3)
            formula.cellFormula = "4+5"
            book.creationHelper.createFormulaEvaluator().evaluateFormulaCell(formula)
        }
        val workbook = readWorkbook(bytes)
        val grid = workbook.sheets["Types"]!!
        assertEquals("2026-08-31", grid[0][0])
        assertEquals("2.5", grid[0][1])
        assertEquals("3", grid[0][2])
        assertEquals("9", grid[0][3])
    }

    @Test
    fun emptySheetAndMissingCellsBecomeEmptyStrings() {
        val bytes = xlsxBytes { book ->
            val sheet = book.createSheet("Sparse")
            sheet.createRow(0).createCell(2).setCellValue("c")
            sheet.createRow(2).createCell(0).setCellValue("a")
        }
        val workbook = readWorkbook(bytes)
        val grid = workbook.sheets["Sparse"]!!
        assertEquals(3, grid.size)
        assertEquals(listOf("", "", "c"), grid[0])
        assertEquals(listOf("", "", ""), grid[1])
        assertEquals(listOf("a", "", ""), grid[2])
    }

    @Test
    fun formulaCellKeepsCachedStringResult() {
        val bytes = xlsxBytes { book ->
            val sheet = book.createSheet("Cache")
            val row = sheet.createRow(0)
            val formula = row.createCell(0)
            formula.cellFormula = "CONCATENATE(\"a\",\"b\")"
            book.creationHelper.createFormulaEvaluator().evaluateFormulaCell(formula)
            assertEquals(CellType.FORMULA, formula.cellType)
        }
        val workbook = readWorkbook(bytes)
        assertEquals("ab", workbook.sheets["Cache"]!![0][0])
    }
}
