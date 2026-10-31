package com.colink.app.data

import android.content.SharedPreferences
import com.colink.app.Course
import com.colink.app.parsing.WeekParity
import com.colink.app.parsing.WeekParseResult
import com.colink.app.parsing.WeekRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StoredWeekRange(
    val start: Int,
    val end: Int,
    val parity: String = "ALL",
)

@Serializable
data class StoredMeeting(
    val courseCode: String? = null,
    val courseName: String,
    val weekday: Int,
    val periodStart: Int? = null,
    val periodEnd: Int? = null,
    val weekText: String? = null,
    val weekRanges: List<StoredWeekRange> = emptyList(),
    val roomText: String? = null,
)

@Serializable
data class StoredSemester(
    val startDate: String? = null,
    val maxWeek: Int? = null,
    val academicYear: String? = null,
    val semesterName: String? = null,
    val className: String? = null,
)

@Serializable
data class StoredSchedule(
    val meetings: List<StoredMeeting>,
    val semester: StoredSemester,
    val sourceFileName: String? = null,
    val importedAt: String,
)

sealed interface ScheduleLoadResult {
    data class Ok(val schedule: StoredSchedule) : ScheduleLoadResult
    data object Empty : ScheduleLoadResult
    data class Corrupt(val message: String) : ScheduleLoadResult
}

interface LocalScheduleStore {
    fun load(): ScheduleLoadResult
    fun save(schedule: StoredSchedule)
    fun clear()
}

class InMemoryLocalScheduleStore : LocalScheduleStore {
    private var stored: StoredSchedule? = null

    override fun load(): ScheduleLoadResult =
        stored?.let { ScheduleLoadResult.Ok(it) } ?: ScheduleLoadResult.Empty

    override fun save(schedule: StoredSchedule) {
        stored = schedule
    }

    override fun clear() {
        stored = null
    }
}

class SharedPrefsLocalScheduleStore(
    private val prefs: SharedPreferences,
    private val json: Json = ScheduleJson.format,
) : LocalScheduleStore {

    override fun load(): ScheduleLoadResult {
        val raw = prefs.getString(KEY_SCHEDULE, null) ?: return ScheduleLoadResult.Empty
        return try {
            ScheduleLoadResult.Ok(json.decodeFromString(StoredSchedule.serializer(), raw))
        } catch (failure: Exception) {
            ScheduleLoadResult.Corrupt(failure.message ?: "课表数据损坏")
        }
    }

    override fun save(schedule: StoredSchedule) {
        prefs.edit()
            .putString(KEY_SCHEDULE, json.encodeToString(StoredSchedule.serializer(), schedule))
            .commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SCHEDULE).commit()
    }

    private companion object {
        const val KEY_SCHEDULE = "local_schedule_json"
    }
}

object ScheduleJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

fun StoredSchedule.toDisplayCourses(): List<Course> =
    meetings.mapIndexed { index, meeting ->
        Course(
            id = "local-meeting-$index",
            name = meeting.courseName,
            code = meeting.courseCode ?: "",
            day = meeting.weekday,
            start = meeting.periodStart,
            end = meeting.periodEnd,
            weeks = meeting.expandedWeeks(),
            room = meeting.roomText?.takeIf { it.isNotBlank() } ?: "待补全",
        )
    }

fun StoredMeeting.expandedWeeks(): Set<Int> =
    WeekParseResult(
        originalText = weekText ?: "",
        ranges = weekRanges.map { it.toWeekRange() }.toMutableList(),
    ).expandedWeeks().toSet()

fun StoredWeekRange.toWeekRange(): WeekRange =
    WeekRange(start = start, end = end, parity = runCatching { WeekParity.valueOf(parity) }.getOrDefault(WeekParity.ALL))

fun WeekRange.toStoredWeekRange(): StoredWeekRange =
    StoredWeekRange(start = start, end = end, parity = parity.name)
