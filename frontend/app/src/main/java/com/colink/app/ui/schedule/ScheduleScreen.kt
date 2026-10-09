package com.colink.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colink.app.Course
import com.colink.app.data.CourseLoadState
import com.colink.app.semesterWeekOrNull
import com.colink.app.ui.theme.Green
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Ink
import com.colink.app.ui.theme.Line
import com.colink.app.ui.theme.Moss
import com.colink.app.ui.theme.PaperLight
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    semesterStart: LocalDate,
    maxWeek: Int = 32,
    loadState: CourseLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var systemNow by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            systemNow = LocalDateTime.now()
            delay(30_000)
        }
    }
    val today = systemNow.toLocalDate()
    var viewDateEpochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val viewDate = LocalDate.ofEpochDay(viewDateEpochDay)
    val currentWeek = semesterWeekOrNull(semesterStart, viewDate, maxWeek)
    var selectedCourse by remember { mutableStateOf<Course?>(null) }

    val allCourses = (loadState as? CourseLoadState.Ready)?.courses
    val courses = allCourses
        .orEmpty()
        .filter { currentWeek != null && it.day == viewDate.dayOfWeek.value && currentWeek in it.weeks }
        .sortedBy { it.start ?: Int.MAX_VALUE }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ScheduleHeader(
                viewDate = viewDate,
                systemNow = systemNow,
                week = currentWeek,
                isToday = viewDate == today,
                onDateClick = { datePickerOpen = true },
                onToday = { viewDateEpochDay = today.toEpochDay() },
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("当天安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(daySubtitle(loadState, courses), style = MaterialTheme.typography.bodyMedium, color = Moss)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = GreenSoft) {
                    Text(
                        currentWeek?.let { "第 $it 周" } ?: "学期外",
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Green,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        when (loadState) {
            is CourseLoadState.Loading -> item { LoadingScheduleCard() }
            is CourseLoadState.Error -> item { ErrorScheduleCard(loadState.message, onRetry) }
            is CourseLoadState.Ready -> when {
                allCourses.isNullOrEmpty() -> item { NoConfirmedScheduleCard() }
                courses.isEmpty() -> item { EmptyScheduleCard() }
                else -> items(courses, key = { it.id }) { CourseTimelineCard(it) { selectedCourse = it } }
            }
        }
    }
    selectedCourse?.let { CourseDetailSheet(it) { selectedCourse = null } }
    if (datePickerOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = viewDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewDateEpochDay = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    datePickerOpen = false
                }) { Text("查看") }
            },
            dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
}

private fun daySubtitle(loadState: CourseLoadState, courses: List<Course>): String = when (loadState) {
    is CourseLoadState.Loading -> "正在从后端加载课表"
    is CourseLoadState.Error -> "课表加载失败"
    is CourseLoadState.Ready -> if (courses.isEmpty()) "为自己留一点空白" else "共 ${courses.size} 门课程"
}

private fun periodLabel(course: Course): String {
    val start = course.start
    val end = course.end
    return if (start == null || end == null) "节次未定" else "第 $start–$end 节"
}

private fun periodCountLabel(course: Course): String {
    val start = course.start
    val end = course.end
    return if (start == null || end == null) "" else "${end - start + 1} 节课"
}

@Composable
private fun ScheduleHeader(
    viewDate: LocalDate,
    systemNow: LocalDateTime,
    week: Int?,
    isToday: Boolean,
    onDateClick: () -> Unit,
    onToday: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Colink", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(3.dp))
            OutlinedButton(
                onClick = onDateClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(17.dp), tint = Green)
                Spacer(Modifier.width(7.dp))
                Text(viewDate.format(dateFormatter), style = MaterialTheme.typography.bodyMedium, color = Moss)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (isToday) "系统时间 ${systemNow.toLocalTime().format(timeFormatter)} · 今日课程" else "系统时间 ${systemNow.toLocalTime().format(timeFormatter)} · 查看日期",
                style = MaterialTheme.typography.labelMedium,
                color = Green,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (!isToday) {
                IconButton(onClick = onToday) { Icon(Icons.Default.CalendarToday, contentDescription = "回到今天", tint = Green) }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = GreenSoft) {
                Text(
                    week?.let { "第 $it 周" } ?: "学期外",
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Green,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CourseTimelineCard(course: Course, onClick: () -> Unit) {
    val accent = when (Math.floorMod(course.id.hashCode(), 3)) { 0 -> Green; 1 -> Moss; else -> Color(0xFFB8794D) }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(74.dp).padding(top = 12.dp)) {
            Text(periodLabel(course), style = MaterialTheme.typography.labelLarge, color = Green, fontWeight = FontWeight.Bold)
            Text(periodCountLabel(course), style = MaterialTheme.typography.labelSmall, color = Moss)
        }
        Box(Modifier.width(2.dp).height(112.dp).background(Line))
        Spacer(Modifier.width(12.dp))
        Card(
            modifier = Modifier.weight(1f).height(112.dp).clickable(onClick = onClick),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PaperLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(5.dp).height(112.dp).background(accent))
                Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                    Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = Moss)
                        Spacer(Modifier.width(4.dp))
                        Text(course.room, style = MaterialTheme.typography.bodyMedium, color = Moss, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("点击查看课程详情", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
        }
    }
}

@Composable
private fun LoadingScheduleCard() {
    StatusCard(
        icon = Icons.Default.Schedule,
        title = "正在加载课表",
        description = "正在从后端读取已确认的课表数据",
    )
}

@Composable
private fun ErrorScheduleCard(message: String, onRetry: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = PaperLight), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 30.dp, horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = GreenSoft, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Green) }
            }
            Spacer(Modifier.height(14.dp))
            Text("课表加载失败", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Moss)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(7.dp))
                Text("重新加载")
            }
        }
    }
}

@Composable
private fun NoConfirmedScheduleCard() {
    StatusCard(
        icon = Icons.Default.CalendarToday,
        title = "暂无已确认课表",
        description = "请先在后端导入课表并确认后，再回到这里查看",
    )
}

@Composable
private fun EmptyScheduleCard() {
    StatusCard(
        icon = Icons.Default.CalendarToday,
        title = "今天没有课程",
        description = "去安排一段属于自己的时间吧",
    )
}

@Composable
private fun StatusCard(icon: ImageVector, title: String, description: String) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = PaperLight), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 30.dp, horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = GreenSoft, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = Green) }
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Moss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailSheet(course: Course, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PaperLight) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("课程详情", style = MaterialTheme.typography.labelLarge, color = Green)
                    Spacer(Modifier.height(4.dp))
                    Text(course.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭课程详情") }
            }
            Spacer(Modifier.height(20.dp))
            DetailRow(Icons.Default.LocationOn, "上课地点", course.room)
            DetailRow(Icons.Default.Schedule, "上课时间", periodLabel(course))
            DetailRow(Icons.Default.Class, "开课周次", formatWeeks(course.weeks))
            DetailRow(Icons.Default.School, "课程代码", course.code)
            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = Line); Spacer(Modifier.height(14.dp))
            Text("课表数据来自后端，仅在本机临时缓存", style = MaterialTheme.typography.bodySmall, color = Moss)
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(13.dp))
        Column { Text(label, style = MaterialTheme.typography.labelMedium, color = Moss); Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
    }
}

private fun formatWeeks(weeks: Set<Int>): String {
    val sorted = weeks.sorted()
    if (sorted.isEmpty()) return "未设置"
    return if (sorted == (sorted.first()..sorted.last()).toList()) "第 ${sorted.first()}–${sorted.last()} 周" else "第 ${sorted.joinToString("、")} 周"
}
