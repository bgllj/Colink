package com.colink.app.domain

private const val MINOR_MARKER = "辅修"

fun isMinorCourse(courseName: String?): Boolean {
    if (courseName.isNullOrEmpty()) return false
    return courseName.trim().contains(MINOR_MARKER)
}
