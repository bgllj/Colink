package com.colink.app.importflow

import com.colink.app.data.LocalScheduleStore
import com.colink.app.data.ScheduleLoadResult
import com.colink.app.data.StoredMeeting
import com.colink.app.data.StoredSchedule
import com.colink.app.data.StoredSemester
import com.colink.app.data.toStoredWeekRange
import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import com.colink.app.domain.ParsedOccurrence
import com.colink.app.domain.SemesterMeta
import com.colink.app.domain.Severity
import com.colink.app.domain.ValidationConfig
import com.colink.app.domain.isMinorCourse
import com.colink.app.domain.validateExtraction
import com.colink.app.parsing.WorkbookReadError
import com.colink.app.parsing.WeekRange
import com.colink.app.parsing.detectFormat
import com.colink.app.parsing.profiles.extractChengduWenliV1
import com.colink.app.parsing.readWorkbook
import java.time.Instant
import java.time.LocalDate

const val MAX_UPLOAD_BYTES: Int = 5 * 1024 * 1024

enum class RowStatus {
    PARSED,
    NEEDS_REVIEW,
    INVALID,
}

enum class ImportStatus {
    PARSED,
    NEEDS_REVIEW,
    FAILED,
}

data class PreviewRow(
    val id: String,
    val selected: Boolean,
    val status: RowStatus,
    val courseCode: String?,
    val courseName: String?,
    val weekday: Int,
    val periodStart: Int?,
    val periodEnd: Int?,
    val weekText: String?,
    val weekRanges: List<WeekRange>,
    val roomText: String?,
    val sheet: String,
    val coordinate: String,
    val lineIndex: Int,
    val rawLine: String,
    val issues: List<Issue>,
)

data class ImportPreview(
    val fileName: String,
    val status: ImportStatus,
    val maxWeek: Int?,
    val rows: List<PreviewRow>,
    val issues: List<Issue>,
    val semester: SemesterMeta,
    val ignoreMinor: Boolean,
)

sealed interface ConfirmResult {
    data class Confirmed(
        val rowsConfirmed: Int,
        val rowsSkipped: Int,
        val schedule: StoredSchedule,
    ) : ConfirmResult

    data class Rejected(val code: IssueCode, val message: String) : ConfirmResult
}

class ImportFlowService(
    private val store: LocalScheduleStore,
    private val now: () -> Instant = { Instant.now() },
) {

    fun ingest(
        fileName: String,
        data: ByteArray,
        maxWeekOverride: Int? = null,
        ignoreMinor: Boolean = true,
        onExtractionFailure: (Throwable) -> Unit = {},
    ): ImportPreview {
        if (data.size > MAX_UPLOAD_BYTES) {
            return failed(
                fileName = fileName,
                maxWeek = maxWeekOverride,
                ignoreMinor = ignoreMinor,
                issues = listOf(
                    Issue(
                        code = IssueCode.FILE_TOO_LARGE,
                        message = "上传文件超过大小限制 $MAX_UPLOAD_BYTES 字节",
                    )
                ),
            )
        }

        val fileFormat = detectFormat(data)
        if (fileFormat == null) {
            return failed(
                fileName = fileName,
                maxWeek = maxWeekOverride,
                ignoreMinor = ignoreMinor,
                issues = listOf(
                    Issue(
                        code = IssueCode.UNSUPPORTED_FILE_TYPE,
                        message = "无法识别的工作簿文件类型 (需要 OLE2 .xls 或 zip .xlsx)",
                    )
                ),
            )
        }

        val workbook = try {
            readWorkbook(data)
        } catch (error: WorkbookReadError) {
            return failed(
                fileName = fileName,
                maxWeek = maxWeekOverride,
                ignoreMinor = ignoreMinor,
                issues = listOf(error.issue),
            )
        }

        val extraction = try {
            extractChengduWenliV1(workbook)
        } catch (failure: Exception) {
            onExtractionFailure(failure)
            return failed(
                fileName = fileName,
                maxWeek = maxWeekOverride,
                ignoreMinor = ignoreMinor,
                issues = listOf(
                    Issue(
                        code = IssueCode.MALFORMED_WORKBOOK,
                        message = "课表提取失败: ${describeImportFailure(failure)}",
                    )
                ),
            )
        }

        val unrecognized = extraction.issues.filter { it.code == IssueCode.UNRECOGNIZED_LAYOUT }
        if (unrecognized.isNotEmpty()) {
            return failed(
                fileName = fileName,
                maxWeek = maxWeekOverride,
                ignoreMinor = ignoreMinor,
                issues = unrecognized,
            )
        }

        val (occurrences, allIssues, effectiveMaxWeek) = validateExtraction(
            extraction,
            ValidationConfig(maxWeek = maxWeekOverride),
        )

        val rows = occurrences.mapIndexed { index, occurrence ->
            buildRow(index, occurrence, allIssues, ignoreMinor)
        }
        val batchIssues = allIssues.filter { isBatchLevel(it, occurrences) }
        val hasProblems = allIssues.any { it.severity == Severity.error || it.severity == Severity.warning }

        return ImportPreview(
            fileName = fileName,
            status = if (hasProblems) ImportStatus.NEEDS_REVIEW else ImportStatus.PARSED,
            maxWeek = effectiveMaxWeek,
            rows = rows,
            issues = batchIssues,
            semester = extraction.meta,
            ignoreMinor = ignoreMinor,
        )
    }

    fun confirm(
        preview: ImportPreview,
        rowIds: List<String>? = null,
        replace: Boolean = false,
        startDateOverride: LocalDate? = null,
    ): ConfirmResult {
        if (preview.status == ImportStatus.FAILED) {
            return ConfirmResult.Rejected(
                code = IssueCode.IMPORT_NOT_CONFIRMABLE,
                message = "导入解析失败，无法确认",
            )
        }

        val existing = store.load()
        if (existing is ScheduleLoadResult.Ok && !replace) {
            return ConfirmResult.Rejected(
                code = IssueCode.SCHEDULE_EXISTS,
                message = "已有本地课表，需显式选择替换才能覆盖",
            )
        }

        val candidates = if (rowIds != null) {
            val wanted = rowIds.toSet()
            preview.rows.filter { it.id in wanted }
        } else {
            preview.rows
        }

        // Requested = explicitly listed rows, or the current selection when no ids given.
        // Deselected rows (e.g. ignored minor courses) are excluded by choice, not skipped.
        val requested = candidates.filter { rowIds != null || it.selected }
        val writable = requested.filter { it.status == RowStatus.PARSED }
        if (writable.isEmpty()) {
            return ConfirmResult.Rejected(
                code = IssueCode.IMPORT_NOT_CONFIRMABLE,
                message = "没有可入库的课程行（仅无警告/错误的勾选行可入库）",
            )
        }

        val schedule = StoredSchedule(
            meetings = writable.map { it.toStoredMeeting() },
            semester = StoredSemester(
                startDate = (startDateOverride ?: preview.semester.startDate)?.toString(),
                maxWeek = preview.maxWeek,
                academicYear = preview.semester.academicYear,
                semesterName = preview.semester.semesterName,
                className = preview.semester.className,
            ),
            sourceFileName = preview.fileName,
            importedAt = now().toString(),
        )
        store.save(schedule)
        return ConfirmResult.Confirmed(
            rowsConfirmed = writable.size,
            rowsSkipped = requested.size - writable.size,
            schedule = schedule,
        )
    }

    private fun buildRow(
        index: Int,
        occurrence: ParsedOccurrence,
        allIssues: List<Issue>,
        ignoreMinor: Boolean,
    ): PreviewRow {
        val rowIssues = issuesForOccurrence(occurrence, allIssues).toMutableList()
        val status = rowStatusFor(rowIssues)
        val minor = ignoreMinor && isMinorCourse(occurrence.courseName)
        if (minor) {
            rowIssues.add(
                Issue(
                    code = IssueCode.MINOR_COURSE_EXCLUDED,
                    message = "已按「忽略辅修」自动取消勾选：${occurrence.courseName}",
                    severity = Severity.info,
                    sheet = occurrence.sheet,
                    coordinate = occurrence.coordinate,
                    lineIndex = occurrence.lineIndex,
                )
            )
        }
        return PreviewRow(
            id = "r$index",
            selected = !minor,
            status = status,
            courseCode = occurrence.courseCode,
            courseName = occurrence.courseName,
            weekday = occurrence.weekday,
            periodStart = occurrence.periodStart,
            periodEnd = occurrence.periodEnd,
            weekText = occurrence.weekText,
            weekRanges = occurrence.weekRanges,
            roomText = occurrence.roomText,
            sheet = occurrence.sheet,
            coordinate = occurrence.coordinate,
            lineIndex = occurrence.lineIndex,
            rawLine = occurrence.rawLine,
            issues = rowIssues,
        )
    }

    private fun failed(
        fileName: String,
        maxWeek: Int?,
        ignoreMinor: Boolean,
        issues: List<Issue>,
    ): ImportPreview = ImportPreview(
        fileName = fileName,
        status = ImportStatus.FAILED,
        maxWeek = maxWeek,
        rows = emptyList(),
        issues = issues,
        semester = SemesterMeta(),
        ignoreMinor = ignoreMinor,
    )
}

internal fun describeImportFailure(failure: Throwable): String =
    generateSequence(failure) { it.cause }
        .take(5)
        .joinToString(" <- ") { cause ->
            val message = cause.message
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.take(240)
                ?.takeIf { it.isNotBlank() }
            if (message == null) cause.javaClass.name else "${cause.javaClass.name}: $message"
        }

private fun PreviewRow.toStoredMeeting(): StoredMeeting =
    StoredMeeting(
        courseCode = courseCode,
        courseName = courseName ?: "",
        weekday = weekday,
        periodStart = periodStart,
        periodEnd = periodEnd,
        weekText = weekText,
        weekRanges = weekRanges.map { it.toStoredWeekRange() },
        roomText = roomText,
    )

private fun rowStatusFor(issues: List<Issue>): RowStatus = when {
    issues.any { it.severity == Severity.error } -> RowStatus.INVALID
    issues.any { it.severity == Severity.warning } -> RowStatus.NEEDS_REVIEW
    else -> RowStatus.PARSED
}

private fun issuesForOccurrence(occurrence: ParsedOccurrence, allIssues: List<Issue>): List<Issue> {
    val related = occurrence.issues.toMutableList()
    val seen = related.map { it.code to it.message }.toMutableSet()
    for (issue in allIssues) {
        if (issue.sheet != occurrence.sheet) continue
        if (issue.coordinate != occurrence.coordinate) continue
        if (issue.lineIndex != occurrence.lineIndex) continue
        if (!seen.add(issue.code to issue.message)) continue
        related.add(issue)
    }
    return related
}

private fun isBatchLevel(issue: Issue, occurrences: List<ParsedOccurrence>): Boolean {
    if (issue.sheet == null && issue.coordinate == null && issue.lineIndex == null) return true
    for (occurrence in occurrences) {
        if (
            occurrence.sheet == issue.sheet &&
            occurrence.coordinate == issue.coordinate &&
            occurrence.lineIndex == issue.lineIndex
        ) {
            return false
        }
    }
    return true
}
