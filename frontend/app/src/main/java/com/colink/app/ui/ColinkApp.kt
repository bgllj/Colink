package com.colink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.colink.app.UserProfile
import com.colink.app.data.BackendConfig
import com.colink.app.data.CourseLoadState
import com.colink.app.data.RemoteCourseDataSource
import com.colink.app.data.toUserMessage
import java.time.LocalDate
import com.colink.app.ui.profile.ProfileScreen
import com.colink.app.ui.schedule.ScheduleScreen
import com.colink.app.ui.theme.ColinkTheme
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Paper

@Composable
fun ColinkApp() {
    ColinkTheme {
        val context = LocalContext.current
        val preferences = remember { context.getSharedPreferences("colink_profile", 0) }
        val systemToday = LocalDate.now()
        val defaultSemesterStart = LocalDate.of(systemToday.year, 9, 1)
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var profile by remember {
            mutableStateOf(
                UserProfile(
                    name = preferences.getString("name", "同学") ?: "同学",
                    studentId = preferences.getString("studentId", "2025XXXXXXXX") ?: "2025XXXXXXXX",
                    college = preferences.getString("college", "计算机科学与技术学院") ?: "计算机科学与技术学院",
                    major = preferences.getString("major", "计算机科学与技术") ?: "计算机科学与技术",
                    grade = preferences.getString("grade", "2025级") ?: "2025级",
                    className = preferences.getString("className", "25计科9(1)") ?: "25计科9(1)",
                ),
            )
        }
        var semesterStart by remember {
            mutableStateOf(
                runCatching {
                    val stored = preferences.getString("semesterStart", null)
                    // 2025-09-01 was the prototype's hard-coded default. Migrate it
                    // to the current academic year's September 1st on first launch.
                    val value = if (stored == null || stored == "2025-09-01") defaultSemesterStart.toString() else stored
                    LocalDate.parse(value)
                }.getOrDefault(defaultSemesterStart),
            )
        }
        var backendBaseUrl by remember {
            mutableStateOf(preferences.getString("backendBaseUrl", BackendConfig.DEFAULT_BASE_URL) ?: BackendConfig.DEFAULT_BASE_URL)
        }
        val courseDataSource = remember { RemoteCourseDataSource(baseUrlProvider = { backendBaseUrl }) }
        var courseLoadState by remember { mutableStateOf<CourseLoadState>(CourseLoadState.Loading) }
        var refreshTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(backendBaseUrl, refreshTick) {
            courseLoadState = CourseLoadState.Loading
            courseLoadState = courseDataSource.fetchCourses().fold(
                onSuccess = { payload ->
                    payload.semester.startDate?.let { remoteStart ->
                        runCatching { LocalDate.parse(remoteStart) }.getOrNull()?.let { parsed ->
                            if (parsed != semesterStart) {
                                semesterStart = parsed
                                preferences.edit().putString("semesterStart", parsed.toString()).apply()
                            }
                        }
                    }
                    CourseLoadState.Ready(payload.courses, payload.semester)
                },
                onFailure = { CourseLoadState.Error(it.toUserMessage()) },
            )
        }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper, GreenSoft)))) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xF9FFFCF6)) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "课表") },
                            label = { Text("课表") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
                            label = { Text("我的") },
                        )
                    }
                },
            ) { padding ->
                AppContent(
                    selectedTab = selectedTab,
                    padding = padding,
                    profile = profile,
                    semesterStart = semesterStart,
                    onSemesterStartChange = { date ->
                        semesterStart = date
                        preferences.edit().putString("semesterStart", date.toString()).apply()
                    },
                    maxWeek = (courseLoadState as? CourseLoadState.Ready)?.semester?.maxWeek ?: 32,
                    backendBaseUrl = backendBaseUrl,
                    onBackendBaseUrlChange = { url ->
                        backendBaseUrl = url
                        preferences.edit().putString("backendBaseUrl", url).apply()
                        refreshTick += 1
                    },
                    courseLoadState = courseLoadState,
                    onRetry = { refreshTick += 1 },
                    onSaveProfile = { updated ->
                        profile = updated
                        preferences.edit()
                            .putString("name", updated.name)
                            .putString("studentId", updated.studentId)
                            .putString("college", updated.college)
                            .putString("major", updated.major)
                            .putString("grade", updated.grade)
                            .putString("className", updated.className)
                            .apply()
                    },
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    selectedTab: Int,
    padding: PaddingValues,
    profile: UserProfile,
    semesterStart: LocalDate,
    onSemesterStartChange: (LocalDate) -> Unit,
    maxWeek: Int,
    backendBaseUrl: String,
    onBackendBaseUrlChange: (String) -> Unit,
    courseLoadState: CourseLoadState,
    onRetry: () -> Unit,
    onSaveProfile: (UserProfile) -> Unit,
) {
    if (selectedTab == 0) {
        ScheduleScreen(
            semesterStart = semesterStart,
            maxWeek = maxWeek,
            loadState = courseLoadState,
            onRetry = onRetry,
            modifier = Modifier.padding(padding),
        )
    } else {
        ProfileScreen(
            profile = profile,
            semesterStart = semesterStart,
            onSemesterStartChange = onSemesterStartChange,
            backendBaseUrl = backendBaseUrl,
            onBackendBaseUrlChange = onBackendBaseUrlChange,
            onSave = onSaveProfile,
            modifier = Modifier.padding(padding),
        )
    }
}
