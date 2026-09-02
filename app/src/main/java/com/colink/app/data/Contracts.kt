package com.colink.app.data

import com.colink.app.Course
import kotlinx.coroutines.flow.Flow

/** Stable boundary for replacing seed data with Room or a remote sync later. */
interface CourseDataSource {
    fun observeCourses(): Flow<List<Course>>
}

/** Reserved for the future XLS/CSV/JSON import pipeline; not implemented in v1. */
interface CourseImporter {
    suspend fun import(source: ByteArray): Result<List<Course>>
}
