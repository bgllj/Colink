package com.colink.app

import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

const val MaxSemesterWeek = 17

data class Course(
    val id: String,
    val name: String,
    val code: String,
    val day: Int,
    val start: Int,
    val end: Int,
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

object CourseRepository {
    val courses = listOf(
        Course("pe", "体育Ⅲ", "193900", 1, 1, 2, (2..16).toSet(), "待补充"),
        Course("linux-monday", "Linux操作系统与服务器运维", "17302079", 1, 3, 4, (2..17).toSet(), "第五教学楼205"),
        Course("structure-monday", "数据结构与算法", "17302012", 1, 5, 6, (2..16).toSet(), "第五教学楼211"),
        Course("web-monday", "Web前端开发技术", "17302083", 1, 7, 8, (2..8).toSet(), "第五教学楼210"),

        Course("history-tuesday", "中国近现代史纲要", "133951", 2, 1, 2, setOf(2, 3, 4) + (8..16).filter { it % 2 == 0 }, "主教学楼B103"),
        Course("structure-tuesday", "数据结构与算法", "17302012", 2, 3, 4, (1..16).filter { it % 2 == 1 }.toSet(), "实验楼114-计算机通用实验室"),
        Course("computer-tuesday", "计算机组成原理", "17302024", 2, 5, 6, setOf(2, 3, 4) + (8..16).filter { it % 2 == 0 }, "第四教学楼306"),
        Course("java-tuesday", "Java程序设计", "17302026", 2, 7, 9, (1..5).toSet() + (7..16).toSet(), "第五教学楼201"),
        Course("computer-night-tuesday", "计算机组成原理", "17302024", 2, 10, 11, setOf(4, 5), "主教学楼B108"),

        Course("history-wednesday", "中国近现代史纲要", "133951", 3, 1, 2, (1..5).toSet() + (7..16).toSet(), "主教学楼B402"),
        Course("english-wednesday", "大学外语Ⅲ（英语 普通本科）", "143938", 3, 3, 4, (1..5).toSet() + (7..16).toSet(), "主教学楼A140-智慧教室"),

        Course("web-lab-thursday", "Web前端开发技术", "17302083", 4, 1, 2, (1..4).toSet() + (6..8).toSet(), "实验楼403-计算机通用实验室"),
        Course("english-thursday", "大学外语Ⅲ（英语 普通本科）", "143938", 4, 5, 6, (1..16).filter { it % 2 == 0 }.toSet(), "主教学楼A138-智慧教室"),

        Course("linux-friday", "Linux操作系统与服务器运维", "17302079", 5, 1, 2, setOf(2) + (6..16).filter { it % 2 == 0 }, "实验楼403-计算机通用实验室"),
        Course("computer-friday", "计算机组成原理", "17302024", 5, 3, 4, (1..3).toSet() + (5..16).toSet(), "第四教学楼307"),
        Course("mental-saturday", "大学生心理健康教育Ⅱ", "193903", 6, 1, 4, setOf(15), "主教学楼C114"),
        Course("policy-saturday", "形势与政策Ⅲ", "134932", 6, 5, 6, setOf(1, 3), "主教学楼B114"),
    )
}

fun semesterWeek(start: LocalDate, today: LocalDate = LocalDate.now()): Int =
    ((Duration.between(start.atStartOfDay(), today.atStartOfDay()).toDays() / 7) + 1)
        .toInt()
        .coerceIn(1, MaxSemesterWeek)

/** Returns the real academic week without clamping dates outside the semester. */
fun semesterWeekOrNull(start: LocalDate, date: LocalDate): Int? {
    val week = (ChronoUnit.DAYS.between(start, date) / 7 + 1).toInt()
    return week.takeIf { it in 1..MaxSemesterWeek }
}

fun weekDates(semesterStart: LocalDate, week: Int): List<LocalDate> =
    (0..6).map { semesterStart.plusWeeks((week - 1).toLong()).plusDays(it.toLong()) }
