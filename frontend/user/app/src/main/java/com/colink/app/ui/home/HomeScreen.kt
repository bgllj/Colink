package com.colink.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colink.app.Course
import com.colink.app.data.CourseLoadState
import com.colink.app.semesterWeekOrNull
import com.colink.app.ui.theme.Green
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Ink
import com.colink.app.ui.theme.Moss
import com.colink.app.ui.theme.PaperLight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val homeBackground = Color(0xFFF6F8F6)
private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)

@Composable
fun HomeScreen(
    profileName: String,
    semesterStart: LocalDate,
    maxWeek: Int,
    loadState: CourseLoadState,
    onRetry: () -> Unit,
    onOpenSchedule: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            today = LocalDate.now()
        }
    }
    val currentWeek = semesterWeekOrNull(semesterStart, today, maxWeek)
    val allCourses = (loadState as? CourseLoadState.Ready)?.courses.orEmpty()
    val todayCourses = allCourses
        .filter { currentWeek != null && it.day == today.dayOfWeek.value && currentWeek in it.weeks }
        .sortedBy { it.start ?: Int.MAX_VALUE }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(homeBackground),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Colink", style = MaterialTheme.typography.titleLarge, color = Ink, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(today.format(dateFormatter), style = MaterialTheme.typography.bodyMedium, color = Moss)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = GreenSoft) {
                    Text(
                        currentWeek?.let { "第 $it 周" } ?: "学期外",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Green,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        item {
            Column {
                Text("${profileName.ifBlank { "同学" }}，今天上什么课？", style = MaterialTheme.typography.headlineSmall, color = Ink, fontWeight = FontWeight.Bold)
            }
        }
        when (loadState) {
            CourseLoadState.Loading -> item { HomeStatus("正在读取本地课表", "请稍候片刻。") }
            CourseLoadState.NoClassSelected -> item {
                HomeActionStatus("还没有导入课表", "选择学校导出的课表文件，解析和保存都在本机完成。", "导入课表", onImportClick)
            }
            is CourseLoadState.Error -> item {
                HomeErrorStatus(loadState.message, onRetry, onImportClick)
            }
            is CourseLoadState.Ready -> when {
                allCourses.isEmpty() -> item {
                    HomeActionStatus("还没有课程安排", "导入课表后，今天的课程会显示在这里。", "导入课表", onImportClick)
                }
                currentWeek == null -> item { HomeStatus("当前日期不在学期范围内", "可以在“我的”中检查开学时间设置。") }
                todayCourses.isEmpty() -> item { HomeStatus("今天没有课程", "今日无课，留一点时间给自己。") }
                else -> {
                    item { FeaturedCourse(todayCourses.first(), todayCourses.size, currentWeek) }
                    if (todayCourses.size > 1) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("后续课程", style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${todayCourses.size - 1} 门", style = MaterialTheme.typography.labelMedium, color = Moss)
                            }
                        }
                        items(todayCourses.drop(1), key = { it.id }) { course -> HomeCourseRow(course) }
                    }
                }
            }
        }
        item {
            TextButton(onClick = onOpenSchedule, modifier = Modifier.fillMaxWidth()) {
                Text("查看完整课表", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(7.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun FeaturedCourse(course: Course, courseCount: Int, week: Int) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Ink),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日第一节", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .76f), modifier = Modifier.weight(1f))
                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White.copy(alpha = .76f), modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(course.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.White.copy(alpha = .72f), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(periodLabel(course), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White.copy(alpha = .72f), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(course.room, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(17.dp))
            HorizontalDivider(color = Color.White.copy(alpha = .18f))
            Spacer(Modifier.height(11.dp))
            Text("今日共 $courseCount 门课程  ·  第 $week 周", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .78f))
        }
    }
}

@Composable
private fun HomeCourseRow(course: Course) {
    Surface(shape = RoundedCornerShape(16.dp), color = PaperLight, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = GreenSoft) {
                Text(periodLabel(course), Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = Green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.bodyLarge, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(course.room, style = MaterialTheme.typography.bodySmall, color = Moss, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeStatus(title: String, description: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = PaperLight, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = GreenSoft, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Green, modifier = Modifier.size(21.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Moss)
        }
    }
}

@Composable
private fun HomeActionStatus(title: String, description: String, actionLabel: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = PaperLight, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Moss)
            Spacer(Modifier.height(13.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun HomeErrorStatus(message: String, onRetry: () -> Unit, onImportClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = PaperLight, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("课表暂时无法读取", style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Moss)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试")
                }
                OutlinedButton(onClick = onImportClick) { Text("重新导入") }
            }
        }
    }
}

private fun periodLabel(course: Course): String {
    val start = course.start
    val end = course.end
    return if (start == null || end == null) "节次未定" else "第 $start–$end 节"
}
