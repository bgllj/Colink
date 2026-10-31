package com.colink.app.parsing

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import jxl.Workbook
import jxl.write.Label
import jxl.write.Number
import jxl.write.WritableSheet
import jxl.write.WritableWorkbook
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkbookJxlFallbackTest {

    private fun jxlFixtureBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val book: WritableWorkbook = Workbook.createWorkbook(out)
        val sheet: WritableSheet = book.createSheet("课表", 0)
        sheet.addCell(Label(1, 0, "成都文理学院2026-2027学年第一学期课表"))
        sheet.addCell(Label(1, 2, "星期一"))
        sheet.addCell(Label(3, 3, "[193900]体育Ⅲ [2-16周][1-2节] 一教楼"))
        sheet.addCell(Number(1, 4, 3.0))
        sheet.addCell(Number(2, 4, 2.5))
        sheet.addCell(jxl.write.Boolean(1, 5, true))
        val day = Date.from(LocalDate.of(2026, 8, 31).atStartOfDay(ZoneId.systemDefault()).toInstant())
        sheet.addCell(jxl.write.DateTime(2, 5, day))
        sheet.mergeCells(1, 0, 3, 0)
        book.write()
        book.close()
        return out.toByteArray()
    }

    @Test
    fun jxlReaderBuildsGridMergedAndStringification() {
        val workbook = readXlsWithJxl(jxlFixtureBytes())
        val grid = workbook.sheets["课表"]!!
        assertEquals("成都文理学院2026-2027学年第一学期课表", grid[0][1])
        assertEquals("星期一", grid[2][1])
        assertEquals("[193900]体育Ⅲ [2-16周][1-2节] 一教楼", grid[3][3])
        assertEquals("3", grid[4][1])
        assertEquals("2.5", grid[4][2])
        assertEquals("TRUE", grid[5][1])
        assertEquals("2026-08-31", grid[5][2])
        assertEquals(listOf(MergeRange(0, 1, 1, 4)), workbook.merged["课表"])
    }
}
