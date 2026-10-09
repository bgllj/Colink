package com.colink.app.data

import com.colink.app.Course

/** Semester metadata returned alongside the confirmed schedule. */
data class SemesterMeta(
    val startDate: String? = null,
    val maxWeek: Int? = null,
)

/** Stable boundary for swapping the backend client for Room or a local cache later. */
interface CourseDataSource {
    suspend fun fetchCourses(): Result<SchedulePayload>
}

data class SchedulePayload(
    val courses: List<Course>,
    val semester: SemesterMeta = SemesterMeta(),
)

sealed interface CourseLoadState {
    data object Loading : CourseLoadState
    data class Ready(val courses: List<Course>, val semester: SemesterMeta = SemesterMeta()) : CourseLoadState
    data class Error(val message: String) : CourseLoadState
}
