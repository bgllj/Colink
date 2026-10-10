package com.colink.app.data

import com.colink.app.Course

/** Semester metadata returned alongside the confirmed schedule. */
data class SemesterMeta(
    val startDate: String? = null,
    val maxWeek: Int? = null,
)

/** A class that owns exactly one timetable. */
data class ClassInfo(
    val id: String,
    val name: String,
)

/** Stable boundary for swapping the backend client for Room or a local cache later. */
interface CourseDataSource {
    suspend fun fetchClasses(): Result<List<ClassInfo>>
    suspend fun fetchCourses(classId: String): Result<SchedulePayload>
}

data class SchedulePayload(
    val classId: String,
    val className: String,
    val courses: List<Course>,
    val semester: SemesterMeta = SemesterMeta(),
)

sealed interface CourseLoadState {
    data object Loading : CourseLoadState
    data object NoClassSelected : CourseLoadState
    data class Ready(
        val classId: String,
        val className: String,
        val courses: List<Course>,
        val semester: SemesterMeta = SemesterMeta(),
    ) : CourseLoadState
    data class Error(val message: String) : CourseLoadState
}
