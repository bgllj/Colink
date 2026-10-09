package com.colink.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Course(
    val id: String,
    val name: String,
    val code: String,
    val day: Int,
    val start: Int?,
    val end: Int?,
    val weeks: Set<Int>,
    val room: String,
)

data class UserProfile(
    val name: String = "同学",
    val studentId: String = "2025XXXXXXXX",
    val college: String = "计算机科学与技术学院",
    val major: String = "计算机科学与技术",
    val grade: String = "2025级",
    val className: String = "25计科9(1)",
)

/** Returns the real academic week, or null when the date falls outside the semester. */
fun semesterWeekOrNull(start: LocalDate, date: LocalDate, maxWeek: Int = 32): Int? {
    val week = (ChronoUnit.DAYS.between(start, date) / 7 + 1).toInt()
    return week.takeIf { it in 1..maxWeek }
}
