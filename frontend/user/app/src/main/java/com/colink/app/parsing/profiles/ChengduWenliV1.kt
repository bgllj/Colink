package com.colink.app.parsing.profiles

import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import com.colink.app.domain.ParsedOccurrence
import com.colink.app.domain.SemesterMeta
import com.colink.app.parsing.MergeRange
import com.colink.app.parsing.WorkbookData
import com.colink.app.parsing.a1Coordinate
import com.colink.app.parsing.parseCourseLine
import java.time.LocalDate

private val WEEKDAY_TOKEN_PATTERN = Regex("(?:星期|周)(?<day>[一二三四五六日天])")
private val TITLE_PATTERN = Regex(
    "(?<year>\\d{4}\\s*[-\u2013\u2014]\\s*\\d{4})\\s*学年\\s*(?<semester>第\\s*[一二三四五六七八九十\\d]+\\s*学期)"
)
private val BARE_DATE_PATTERN = Regex("^\\s*(?<year>\\d{4})[-/](?<month>\\d{1,2})[-/](?<day>\\d{1,2})\\s*$")

private val WEEKDAY_BY_TOKEN = mapOf(
    "一" to 1,
    "二" to 2,
    "三" to 3,
    "四" to 4,
    "五" to 5,
    "六" to 6,
    "日" to 7,
    "天" to 7,
)

private val SLOT_NUMERALS = mapOf(
    "一" to 1,
    "二" to 2,
    "三" to 3,
    "四" to 4,
    "五" to 5,
    "六" to 6,
)

private val DAYPART_TOKENS = setOf("上午", "下午", "晚上", "晚间")

private val DEPARTMENT_LABEL = Regex("院\\s*[（(]\\s*系\\s*[）)]|院系|院\\(系\\)")
private val GRADE_LABEL = Regex("年级")
private val MAJOR_LABEL = Regex("专业")
private val CLASS_LABEL = Regex("班级")
private val LABEL_VALUE_SEPARATOR = Regex("[：:]")

private val LINE_BREAK_PATTERN = Regex("\r\n|[\r\n\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]")
private val TRAILING_BREAK_PATTERN = Regex("(?:\r\n|[\r\n\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029])$")

private data class SlotRow(
    val row: Int,
    val slotNumber: Int,
    val daypart: String?,
    val slotLabel: String?,
)

private data class SheetLayout(
    val headerRow: Int,
    val weekdayColumns: Map<Int, Int>,
    val slotColumn: Int?,
    val daypartColumn: Int?,
    val slotRows: List<SlotRow>,
)

private fun compact(text: String?): String =
    (text ?: "").filterNot { it.isWhitespace() || it == '\u0085' }

private fun cellText(grid: List<List<String>>, row: Int, col: Int): String {
    if (row < 0 || row >= grid.size) return ""
    val line = grid[row]
    if (col < 0 || col >= line.size) return ""
    return line[col]
}

private fun mergedAnchor(merged: List<MergeRange>, row: Int, col: Int): Pair<Int, Int> {
    for ((rlo, rhi, clo, chi) in merged) {
        if (row in rlo until rhi && col in clo until chi) return rlo to clo
    }
    return row to col
}

private fun mergedSpan(merged: List<MergeRange>, row: Int, col: Int): Pair<Int, Int> {
    for ((rlo, rhi, clo, chi) in merged) {
        if (row in rlo until rhi && col in clo until chi) return clo to (chi - 1)
    }
    return col to col
}

private fun parseTitle(text: String): Triple<String?, String?, String?> {
    val match = TITLE_PATTERN.find(text) ?: return Triple(null, null, null)
    val year = compact(match.groups["year"]!!.value)
    val semester = compact(match.groups["semester"]!!.value)
    return Triple(year, semester, text.trim())
}

private fun parseBareDate(text: String): LocalDate? {
    val match = BARE_DATE_PATTERN.find(text) ?: return null
    return try {
        LocalDate.of(
            match.groups["year"]!!.value.toInt(),
            match.groups["month"]!!.value.toInt(),
            match.groups["day"]!!.value.toInt(),
        )
    } catch (exc: Exception) {
        null
    }
}

private fun labelValue(grid: List<List<String>>, row: Int, col: Int, raw: String): String? {
    val parts = LABEL_VALUE_SEPARATOR.split(raw, limit = 2)
    if (parts.size == 2 && parts[1].trim().isNotEmpty()) {
        return parts[1].trim()
    }
    for (nextCol in (col + 1) until grid[row].size) {
        val candidate = cellText(grid, row, nextCol).trim()
        if (candidate.isNotEmpty()) {
            return candidate
        }
    }
    return null
}

private fun extractMeta(grid: List<List<String>>): SemesterMeta {
    var meta = SemesterMeta()
    for (row in grid.indices) {
        for (col in grid[row].indices) {
            val raw = cellText(grid, row, col)
            if (raw.isBlank()) continue

            val (year, semester, title) = parseTitle(raw)
            if (year != null && meta.academicYear == null) {
                meta = meta.copy(academicYear = year, semesterName = semester, title = title)
            }

            val bare = parseBareDate(raw)
            if (bare != null && meta.startDate == null) {
                meta = meta.copy(startDate = bare)
            }

            val compacted = compact(raw)
            if (meta.department == null && DEPARTMENT_LABEL.containsMatchIn(compacted)) {
                val value = labelValue(grid, row, col, raw)
                if (!value.isNullOrEmpty() &&
                    !GRADE_LABEL.containsMatchIn(value) &&
                    !CLASS_LABEL.containsMatchIn(value)
                ) {
                    meta = meta.copy(department = value)
                }
            }
            if (meta.grade == null && GRADE_LABEL.containsMatchIn(compacted)) {
                val value = labelValue(grid, row, col, raw)
                if (!value.isNullOrEmpty()) {
                    meta = meta.copy(grade = value)
                }
            }
            if (meta.major == null && MAJOR_LABEL.containsMatchIn(compacted)) {
                val value = labelValue(grid, row, col, raw)
                if (!value.isNullOrEmpty()) {
                    meta = meta.copy(major = value)
                }
            }
            if (meta.className == null && CLASS_LABEL.containsMatchIn(compacted)) {
                val value = labelValue(grid, row, col, raw)
                if (!value.isNullOrEmpty()) {
                    meta = meta.copy(className = value)
                }
            }
        }
    }
    return meta
}

private fun findWeekdayColumns(
    grid: List<List<String>>,
    merged: List<MergeRange>,
): Pair<Int, Map<Int, Int>>? {
    for (row in grid.indices) {
        val found = LinkedHashMap<Int, Int>()
        for (col in grid[row].indices) {
            val match = WEEKDAY_TOKEN_PATTERN.matchEntire(compact(cellText(grid, row, col))) ?: continue
            val weekday = WEEKDAY_BY_TOKEN[match.groups["day"]!!.value] ?: continue
            val (spanStart, _) = mergedSpan(merged, row, col)
            if (!found.containsKey(weekday)) {
                found[weekday] = spanStart
            }
        }
        if (found.isNotEmpty()) {
            return row to found
        }
    }
    return null
}

private fun findSlotColumn(grid: List<List<String>>, headerRow: Int, maxWeekdayCol: Int): Int? {
    var bestCol: Int? = null
    var bestScore = 0
    for (col in 0 until maxOf(maxWeekdayCol, 0)) {
        var score = 0
        for (row in (headerRow + 1) until grid.size) {
            if (SLOT_NUMERALS.containsKey(compact(cellText(grid, row, col)))) score++
        }
        if (score > bestScore) {
            bestScore = score
            bestCol = col
        }
    }
    return bestCol
}

private fun findDaypartColumn(
    grid: List<List<String>>,
    merged: List<MergeRange>,
    slotColumn: Int?,
): Int? {
    var bestCol: Int? = null
    var bestScore = 0
    val upper = slotColumn ?: if (grid.isNotEmpty()) grid[0].size else 0
    for (col in 0 until maxOf(upper, 0)) {
        var score = 0
        for (row in grid.indices) {
            val (anchorRow, anchorCol) = mergedAnchor(merged, row, col)
            if (DAYPART_TOKENS.contains(compact(cellText(grid, anchorRow, anchorCol)))) score++
        }
        if (score > bestScore) {
            bestScore = score
            bestCol = col
        }
    }
    return bestCol
}

private fun daypartFallback(slotNumber: Int): String? = when (slotNumber) {
    1, 2 -> "上午"
    3, 4 -> "下午"
    5, 6 -> "晚上"
    else -> null
}

private fun sheetLayout(grid: List<List<String>>, merged: List<MergeRange>): SheetLayout? {
    val (headerRow, weekdayColumns) = findWeekdayColumns(grid, merged) ?: return null
    val maxWeekdayCol = weekdayColumns.values.max()
    val slotColumn = findSlotColumn(grid, headerRow, maxWeekdayCol)
    val daypartColumn = findDaypartColumn(grid, merged, slotColumn)

    val slotRows = mutableListOf<SlotRow>()
    for (row in (headerRow + 1) until grid.size) {
        var slotLabel: String? = null
        var slotNumber: Int? = null
        if (slotColumn != null) {
            val compacted = compact(cellText(grid, row, slotColumn))
            if (SLOT_NUMERALS.containsKey(compacted)) {
                slotLabel = compacted
                slotNumber = SLOT_NUMERALS[compacted]
            }
        }
        val number = slotNumber ?: continue
        var daypart: String? = null
        if (daypartColumn != null) {
            val (anchorRow, anchorCol) = mergedAnchor(merged, row, daypartColumn)
            val anchorText = compact(cellText(grid, anchorRow, anchorCol))
            daypart = anchorText.ifEmpty { null }
        }
        if (daypart.isNullOrEmpty()) {
            daypart = daypartFallback(number)
        }
        slotRows.add(SlotRow(row, number, daypart, slotLabel))
    }

    return SheetLayout(
        headerRow = headerRow,
        weekdayColumns = weekdayColumns,
        slotColumn = slotColumn,
        daypartColumn = daypartColumn,
        slotRows = slotRows,
    )
}

private fun occurrenceIssues(issues: List<Issue>, sheet: String, coordinate: String): List<Issue> =
    issues.map { issue ->
        if (issue.sheet == null) issue.copy(sheet = sheet, coordinate = coordinate) else issue
    }

private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = LINE_BREAK_PATTERN.split(text).toMutableList()
    if (TRAILING_BREAK_PATTERN.containsMatchIn(text)) {
        parts.removeAt(parts.lastIndex)
    }
    return parts
}

fun extractChengduWenliV1(workbook: WorkbookData): ExtractionResult {
    val result = ExtractionResult()
    var recognizedAny = false

    for ((sheetName, grid) in workbook.sheets) {
        val merged = workbook.merged[sheetName] ?: emptyList()
        val layout = sheetLayout(grid, merged)
        val sheetMeta = extractMeta(grid)
        if (result.meta.title == null) {
            result.meta = sheetMeta
        } else {
            result.meta = result.meta.copy(
                academicYear = result.meta.academicYear ?: sheetMeta.academicYear,
                semesterName = result.meta.semesterName ?: sheetMeta.semesterName,
                startDate = result.meta.startDate ?: sheetMeta.startDate,
                department = result.meta.department ?: sheetMeta.department,
                grade = result.meta.grade ?: sheetMeta.grade,
                major = result.meta.major ?: sheetMeta.major,
                className = result.meta.className ?: sheetMeta.className,
                title = result.meta.title ?: sheetMeta.title,
            )
        }

        if (layout == null) continue
        recognizedAny = true

        for (slotRow in layout.slotRows) {
            for ((weekday, col) in layout.weekdayColumns.entries.sortedBy { it.key }) {
                val rawCell = cellText(grid, slotRow.row, col)
                if (rawCell.isBlank()) continue
                val coordinate = a1Coordinate(slotRow.row, col)
                for ((lineIndex, rawLine) in splitLines(rawCell).withIndex()) {
                    if (rawLine.isBlank()) continue
                    val parsed = parseCourseLine(rawLine, lineIndex = lineIndex)
                    val weekRanges = parsed.weekResult?.ranges?.toList() ?: emptyList()
                    val periodRange = parsed.periodResult?.range
                    val issues = occurrenceIssues(parsed.issues, sheetName, coordinate)
                    result.occurrences.add(
                        ParsedOccurrence(
                            courseCode = parsed.courseCode,
                            courseName = parsed.courseName,
                            weekday = weekday,
                            periodStart = periodRange?.start,
                            periodEnd = periodRange?.end,
                            weekText = parsed.weekText,
                            weekRanges = weekRanges,
                            roomText = parsed.roomText,
                            sheet = sheetName,
                            coordinate = coordinate,
                            lineIndex = lineIndex,
                            rawLine = rawLine,
                            daypart = slotRow.daypart,
                            slotLabel = slotRow.slotLabel,
                            issues = issues,
                        )
                    )
                    result.issues.addAll(issues)
                }
            }
        }
    }

    if (!recognizedAny) {
        result.issues.add(
            Issue(
                code = IssueCode.UNRECOGNIZED_LAYOUT,
                message = "未能识别课表布局: 缺少星期表头行",
            )
        )
    }
    if (result.meta.startDate == null) {
        result.issues.add(
            Issue(
                code = IssueCode.MISSING_SEMESTER_START,
                message = "未找到开学日期 (期望无标签日期单元格, 例如 2026-08-31)",
            )
        )
    }
    return result
}
