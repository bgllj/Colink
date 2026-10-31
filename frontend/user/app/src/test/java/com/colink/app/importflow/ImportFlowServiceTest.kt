package com.colink.app.importflow

import com.colink.app.data.InMemoryLocalScheduleStore
import com.colink.app.data.ScheduleLoadResult
import com.colink.app.domain.IssueCode
import com.colink.app.domain.Severity
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportFlowServiceTest {

    private val store = InMemoryLocalScheduleStore()
    private val service = ImportFlowService(store)

    private val courseLine = "[1001]高等数学 [1-4周][1-2节] 教A101"
    private val warningLine = "[2001]大学物理 [1-16周][1-2节] 教D404"
    private val minorLine = "[FX3001004]财务管理Ⅰ（辅修） [1-4周][3-4节] 教C404"
    private val invalidLine = "这不是课程行"

    private fun workbookBytes(vararg courseCells: String): ByteArray {
        XSSFWorkbook().use { book ->
            val sheet = book.createSheet("课表")
            fun row(index: Int, vararg cells: String) {
                val r = sheet.createRow(index)
                cells.forEachIndexed { col, text -> r.createCell(col).setCellValue(text) }
            }
            row(0, "2025-2026学年 第一学期")
            row(1, "2026-08-31", "班级：计科2501")
            row(2, "节次", "星期一", "星期二", "星期三", "星期四", "星期五")
            courseCells.forEachIndexed { index, cell ->
                row(3 + index, "一二三四五六"[index].toString(), cell)
            }
            val out = ByteArrayOutputStream()
            book.write(out)
            return out.toByteArray()
        }
    }

    @Test
    fun importFailureDescriptionIncludesCauseChainWithoutLineBreaks() {
        val failure = IllegalStateException(
            "extract failed\nretry",
            NoClassDefFoundError("com.colink.app.parsing.profiles.ChengduWenliV1Kt"),
        )

        val description = describeImportFailure(failure)

        assertTrue(description.contains("IllegalStateException: extract failed retry"))
        assertTrue(description.contains("NoClassDefFoundError: com.colink.app.parsing.profiles.ChengduWenliV1Kt"))
        assertFalse(description.contains('\n'))
    }

    @Test
    fun ingestParsesRowsWithoutTouchingStore() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine, minorLine))

        assertEquals(ImportStatus.PARSED, preview.status)
        assertEquals(2, preview.rows.size)
        assertTrue(preview.maxWeek != null && preview.maxWeek!! >= 4)
        assertEquals("2026-08-31", preview.semester.startDate?.toString())
        assertEquals("计科2501", preview.semester.className)
        assertTrue(store.load() is ScheduleLoadResult.Empty)

        val normal = preview.rows.first { it.courseName == "高等数学" }
        assertEquals(RowStatus.PARSED, normal.status)
        assertEquals(1, normal.weekday)
        assertEquals(1, normal.periodStart)
        assertEquals(2, normal.periodEnd)
        assertEquals("1-4周", normal.weekText)
        assertEquals("教A101", normal.roomText)
    }

    @Test
    fun ignoreMinorDeselectsMinorRowsWithInfoIssue() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine, minorLine))
        val minor = preview.rows.first { it.courseName!!.contains("辅修") }

        assertFalse(minor.selected)
        assertEquals(RowStatus.PARSED, minor.status)
        val codes = minor.issues.map { it.code }
        assertTrue(IssueCode.MINOR_COURSE_EXCLUDED in codes)
        assertTrue(minor.issues.filter { it.code == IssueCode.MINOR_COURSE_EXCLUDED }
            .all { it.severity == Severity.info })
        assertTrue(preview.ignoreMinor)
    }

    @Test
    fun ignoreMinorFalseKeepsAllSelected() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine, minorLine), ignoreMinor = false)
        val minor = preview.rows.first { it.courseName!!.contains("辅修") }

        assertTrue(minor.selected)
        assertTrue(minor.issues.none { it.code == IssueCode.MINOR_COURSE_EXCLUDED })
    }

    @Test
    fun confirmWritesScheduleOnceAndRequiresExplicitReplace() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine, minorLine))
        val confirmed = service.confirm(preview) as ConfirmResult.Confirmed

        assertEquals(1, confirmed.rowsConfirmed)
        assertEquals(0, confirmed.rowsSkipped)
        assertEquals("t.xlsx", confirmed.schedule.sourceFileName)
        assertEquals("2026-08-31", confirmed.schedule.semester.startDate)
        assertTrue(store.load() is ScheduleLoadResult.Ok)

        val conflict = service.confirm(preview)
        assertTrue(conflict is ConfirmResult.Rejected)
        assertEquals(IssueCode.SCHEDULE_EXISTS, (conflict as ConfirmResult.Rejected).code)

        val replaced = service.confirm(preview, replace = true) as ConfirmResult.Confirmed
        assertEquals(1, replaced.rowsConfirmed)
        val loaded = (store.load() as ScheduleLoadResult.Ok).schedule
        assertEquals(1, loaded.meetings.size)
    }

    @Test
    fun confirmUsesReviewStartDateOverride() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine))

        val confirmed = service.confirm(
            preview,
            startDateOverride = LocalDate.of(2026, 9, 7),
        ) as ConfirmResult.Confirmed

        assertEquals("2026-09-07", confirmed.schedule.semester.startDate)
    }

    @Test
    fun explicitRowIdsCanReselectMinorRow() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine, minorLine))
        val allIds = preview.rows.map { it.id }

        val confirmed = service.confirm(preview, rowIds = allIds) as ConfirmResult.Confirmed
        assertEquals(2, confirmed.rowsConfirmed)
        val names = (store.load() as ScheduleLoadResult.Ok).schedule.meetings.map { it.courseName }
        assertTrue("财务管理Ⅰ（辅修）" in names)
    }

    @Test
    fun nonParsedRowsAreVisibleButNotWritten() {
        val bytes = workbookBytes(courseLine, warningLine, invalidLine)
        val preview = service.ingest("t.xlsx", bytes, maxWeekOverride = 5)

        assertEquals(ImportStatus.NEEDS_REVIEW, preview.status)
        val byName = preview.rows.associateBy { it.courseName }
        assertEquals(RowStatus.PARSED, byName["高等数学"]?.status)
        assertEquals(RowStatus.NEEDS_REVIEW, byName["大学物理"]?.status)
        val invalid = preview.rows.first { it.status == RowStatus.INVALID }
        assertTrue(invalid.issues.any { it.code == IssueCode.INVALID_COURSE_LINE })
        assertTrue(byName["大学物理"]!!.issues.any { it.code == IssueCode.WEEK_OUT_OF_RANGE })

        val confirmed = service.confirm(preview, rowIds = preview.rows.map { it.id }) as ConfirmResult.Confirmed
        assertEquals(1, confirmed.rowsConfirmed)
        assertEquals(2, confirmed.rowsSkipped)
        val meetings = (store.load() as ScheduleLoadResult.Ok).schedule.meetings
        assertEquals(listOf("高等数学"), meetings.map { it.courseName })
    }

    @Test
    fun failedIngestCannotConfirm() {
        val preview = service.ingest("bad.bin", "not a workbook".toByteArray())
        assertEquals(ImportStatus.FAILED, preview.status)
        assertTrue(preview.rows.isEmpty())
        assertTrue(preview.issues.any { it.code == IssueCode.UNSUPPORTED_FILE_TYPE })

        val result = service.confirm(preview)
        assertTrue(result is ConfirmResult.Rejected)
        assertTrue(store.load() is ScheduleLoadResult.Empty)
    }

    @Test
    fun oversizedUploadIsRejected() {
        val preview = service.ingest("huge.xlsx", ByteArray(MAX_UPLOAD_BYTES + 1))
        assertEquals(ImportStatus.FAILED, preview.status)
        assertTrue(preview.issues.any { it.code == IssueCode.FILE_TOO_LARGE })
    }

    @Test
    fun confirmRejectsWhenNoWritableRows() {
        val preview = service.ingest("t.xlsx", workbookBytes(invalidLine))
        assertEquals(RowStatus.INVALID, preview.rows.single().status)

        val result = service.confirm(preview)
        assertTrue(result is ConfirmResult.Rejected)
        assertEquals(IssueCode.IMPORT_NOT_CONFIRMABLE, (result as ConfirmResult.Rejected).code)
        assertTrue(store.load() is ScheduleLoadResult.Empty)
    }

    @Test
    fun unselectedRowsAreNotWrittenWithoutExplicitIds() {
        val preview = service.ingest("t.xlsx", workbookBytes(courseLine, minorLine))
        val confirmed = service.confirm(preview) as ConfirmResult.Confirmed

        assertEquals(1, confirmed.rowsConfirmed)
        val names = (store.load() as ScheduleLoadResult.Ok).schedule.meetings.map { it.courseName }
        assertEquals(listOf("高等数学"), names)
    }
}
