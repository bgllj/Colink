package com.colink.app.parsing

import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// (rlo, rhi, clo, chi) 0-based, end-exclusive — same shape as xlrd's merged_cells.
data class MergeRange(val rlo: Int, val rhi: Int, val clo: Int, val chi: Int)

class WorkbookReadError(val issue: Issue) : IllegalArgumentException(issue.message)

data class WorkbookData(
    val sheets: LinkedHashMap<String, List<List<String>>> = LinkedHashMap(),
    val merged: Map<String, List<MergeRange>> = emptyMap(),
)

private val XLS_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
private val XLSX_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04)

private val DATE_ONLY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun detectFormat(data: ByteArray): String? {
    if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(XLS_MAGIC)) return "xls"
    if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(XLSX_MAGIC)) return "xlsx"
    return null
}

fun a1Coordinate(row: Int, col: Int): String {
    var n = col + 1
    var letters = ""
    while (n > 0) {
        val rem = (n - 1) % 26
        n = (n - 1) / 26
        letters = ('A'.code + rem).toChar() + letters
    }
    return "$letters${row + 1}"
}

fun readWorkbook(data: ByteArray): WorkbookData {
    if (data.isEmpty()) {
        throw WorkbookReadError(Issue(code = IssueCode.MALFORMED_WORKBOOK, message = "工作簿内容为空"))
    }
    return when (detectFormat(data)) {
        "xls" -> readXls(data)
        "xlsx" -> readXlsx(data)
        else -> throw WorkbookReadError(
            Issue(
                code = IssueCode.UNSUPPORTED_FILE_TYPE,
                message = "无法识别的工作簿文件类型 (需要 OLE2 .xls 或 zip .xlsx)",
            )
        )
    }
}

private fun readXls(data: ByteArray): WorkbookData {
    try {
        val book = HSSFWorkbook(ByteArrayInputStream(data))
        return book.use { buildWorkbookData(it) }
    } catch (poiFailure: Exception) {
        // Some school exports carry nonstandard BIFF record ordering that POI's
        // InternalSheet rejects (e.g. "row/cell records found in the wrong place");
        // jxl reads those leniently and covers BIFF8 down to older versions.
        try {
            return readXlsWithJxl(data)
        } catch (_: Exception) {
            throw WorkbookReadError(
                Issue(code = IssueCode.MALFORMED_WORKBOOK, message = "无法解析的 xls 工作簿: $poiFailure")
            )
        }
    }
}

internal fun readXlsWithJxl(data: ByteArray): WorkbookData {
    val book = jxl.Workbook.getWorkbook(ByteArrayInputStream(data))
    try {
        val sheets = LinkedHashMap<String, List<List<String>>>()
        val merged = LinkedHashMap<String, List<MergeRange>>()
        for (sheet in book.sheets) {
            var width = 0
            for (row in 0 until sheet.rows) {
                for (col in 0 until sheet.columns) {
                    if (sheet.getCell(col, row).contents.isNotEmpty()) {
                        width = maxOf(width, col + 1)
                    }
                }
            }
            val grid = ArrayList<List<String>>(sheet.rows)
            for (row in 0 until sheet.rows) {
                val line = ArrayList<String>(width)
                for (col in 0 until width) {
                    line.add(jxlCellText(sheet.getCell(col, row)))
                }
                grid.add(line)
            }
            sheets[sheet.name] = grid
            merged[sheet.name] = sheet.mergedCells.map { range ->
                MergeRange(
                    rlo = range.topLeft.row,
                    rhi = range.bottomRight.row + 1,
                    clo = range.topLeft.column,
                    chi = range.bottomRight.column + 1,
                )
            }
        }
        return WorkbookData(sheets = sheets, merged = merged)
    } finally {
        book.close()
    }
}

private fun jxlCellText(cell: jxl.Cell): String = when (cell) {
    is jxl.DateCell -> {
        val dateTime = LocalDateTime.ofInstant(cell.date.toInstant(), ZoneId.systemDefault())
        if (dateTime.hour == 0 && dateTime.minute == 0 && dateTime.second == 0 && dateTime.nano == 0) {
            DATE_ONLY_FORMAT.format(dateTime)
        } else {
            DATE_TIME_FORMAT.format(dateTime)
        }
    }
    is jxl.BooleanCell -> if (cell.value) "TRUE" else "FALSE"
    is jxl.NumberCell -> formatDouble(cell.value)
    is jxl.LabelCell -> cell.string
    else -> when (cell.type) {
        jxl.CellType.EMPTY -> ""
        else -> cell.contents
    }
}

private fun readXlsx(data: ByteArray): WorkbookData {
    val book = try {
        XSSFWorkbook(ByteArrayInputStream(data))
    } catch (exc: Exception) {
        throw WorkbookReadError(Issue(code = IssueCode.MALFORMED_WORKBOOK, message = "无法解析的 xlsx 工作簿: $exc"))
    }
    return book.use { buildWorkbookData(it) }
}

private fun buildWorkbookData(book: Workbook): WorkbookData {
    val sheets = LinkedHashMap<String, List<List<String>>>()
    val merged = LinkedHashMap<String, List<MergeRange>>()
    for (sheet in book) {
        sheets[sheet.sheetName] = buildGrid(sheet)
        merged[sheet.sheetName] = sheet.mergedRegions.map { region ->
            MergeRange(
                rlo = region.firstRow,
                // POI CellRangeAddress bounds are inclusive; convert to end-exclusive.
                rhi = region.lastRow + 1,
                clo = region.firstColumn,
                chi = region.lastColumn + 1,
            )
        }
    }
    return WorkbookData(sheets = sheets, merged = merged)
}

private fun buildGrid(sheet: Sheet): List<List<String>> {
    val lastRow = sheet.lastRowNum
    var width = 0
    for (rowIndex in 0..lastRow) {
        val row = sheet.getRow(rowIndex) ?: continue
        width = maxOf(width, row.lastCellNum.toInt())
    }
    val grid = ArrayList<List<String>>(lastRow + 1)
    for (rowIndex in 0..lastRow) {
        val row = sheet.getRow(rowIndex)
        val line = ArrayList<String>(width)
        for (colIndex in 0 until width) {
            line.add(row?.getCell(colIndex)?.let { stringifyCell(it) } ?: "")
        }
        grid.add(line)
    }
    return grid
}

private fun stringifyCell(cell: Cell): String {
    return when (cell.cellType) {
        CellType.BLANK -> ""
        CellType.STRING -> cell.stringCellValue
        CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
        CellType.NUMERIC -> stringifyNumeric(cell)
        CellType.FORMULA -> when (cell.cachedFormulaResultType) {
            CellType.STRING -> cell.stringCellValue
            CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
            CellType.NUMERIC -> stringifyNumeric(cell)
            else -> ""
        }
        else -> ""
    }
}

private fun stringifyNumeric(cell: Cell): String {
    val value = cell.numericCellValue
    return if (DateUtil.isCellDateFormatted(cell)) {
        formatDateCell(cell)
    } else {
        formatDouble(value)
    }
}

private fun formatDateCell(cell: Cell): String {
    val dateTime = LocalDateTime.ofInstant(cell.dateCellValue.toInstant(), ZoneId.systemDefault())
    if (dateTime.hour == 0 && dateTime.minute == 0 && dateTime.second == 0 && dateTime.nano == 0) {
        return DATE_ONLY_FORMAT.format(dateTime)
    }
    val base = DATE_TIME_FORMAT.format(dateTime)
    return if (dateTime.nano == 0) base else base + "." + "%06d".format(dateTime.nano / 1000)
}

private fun formatDouble(value: Double): String {
    return if (value.isFinite() && value % 1.0 == 0.0) {
        BigDecimal.valueOf(value).toBigInteger().toString()
    } else {
        value.toString()
    }
}
