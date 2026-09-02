package com.colink.app.ui.schedule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colink.app.Course
import com.colink.app.CourseRepository
import com.colink.app.MaxSemesterWeek
import com.colink.app.semesterWeek
import com.colink.app.semesterWeekOrNull
import com.colink.app.ui.theme.Green
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Ink
import com.colink.app.ui.theme.Line
import com.colink.app.ui.theme.Moss
import com.colink.app.ui.theme.PaperLight
import com.colink.app.ui.theme.Sand
import com.colink.app.weekDates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(semesterStart: LocalDate, modifier: Modifier = Modifier) {
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
    val currentWeek = semesterWeekOrNull(semesterStart, viewDate)
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    val courses = CourseRepository.courses
        .filter { currentWeek != null && it.day == viewDate.dayOfWeek.value && currentWeek in it.weeks }
        .sortedBy { it.start }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp),
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
                    Text(if (courses.isEmpty()) "为自己留一点空白" else "共 ${courses.size} 门课程", style = MaterialTheme.typography.bodyMedium, color = Moss)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = GreenSoft) {
                    Text(currentWeek?.let { "第 $it 周" } ?: "学期外", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = Green, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (courses.isEmpty()) item { EmptyScheduleCard() }
        else items(courses, key = { it.id }) { CourseTimelineCard(it) { selectedCourse = it } }
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

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
            Text(
                text = viewDate.format(dateFormatter),
                style = MaterialTheme.typography.bodyLarge,
                color = Moss,
                modifier = Modifier.clickable(onClick = onDateClick),
            )
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
                Text(week?.let { "第 $it 周" } ?: "学期外", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = Green, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun WeekDateSelector(dates: List<LocalDate>, selectedDate: LocalDate, today: LocalDate, onDateSelected: (LocalDate) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        dates.forEachIndexed { index, date ->
            val selected = date == selectedDate
            val container by animateColorAsState(
                targetValue = if (selected) Green else PaperLight,
                animationSpec = tween(180),
                label = "dateColor",
            )
            val content = if (selected) Color.White else Ink
            Column(
                Modifier.width(52.dp).clip(RoundedCornerShape(18.dp)).background(container).clickable { onDateSelected(date) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("周${weekdayNames[index]}", style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = .8f))
                Spacer(Modifier.height(4.dp))
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (date == today) if (selected) Sand else Green else Color.Transparent))
            }
        }
    }
}

@Composable
private fun CourseTimelineCard(course: Course, onClick: () -> Unit) {
    val accent = when (Math.floorMod(course.id.hashCode(), 3)) { 0 -> Green; 1 -> Moss; else -> Color(0xFFB8794D) }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(74.dp).padding(top = 12.dp)) {
            Text("第 ${course.start}–${course.end} 节", style = MaterialTheme.typography.labelLarge, color = Green, fontWeight = FontWeight.Bold)
            Text("${course.end - course.start + 1} 节课", style = MaterialTheme.typography.labelSmall, color = Moss)
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
private fun EmptyScheduleCard() {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = PaperLight), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 30.dp, horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = GreenSoft, modifier = Modifier.size(52.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Green) } }
            Spacer(Modifier.height(14.dp))
            Text("今天没有课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("去安排一段属于自己的时间吧", style = MaterialTheme.typography.bodyMedium, color = Moss)
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
            DetailRow(Icons.Default.Schedule, "上课时间", "第 ${course.start}–${course.end} 节")
            DetailRow(Icons.Default.Class, "开课周次", formatWeeks(course.weeks))
            DetailRow(Icons.Default.School, "课程代码", course.code)
            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = Line); Spacer(Modifier.height(14.dp))
            Text("课程数据仅保存在本机", style = MaterialTheme.typography.bodySmall, color = Moss)
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
