package com.colink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
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
import com.colink.app.data.CourseLoadState
import com.colink.app.data.ScheduleLoadResult
import com.colink.app.data.SemesterMeta
import com.colink.app.data.SharedPrefsLocalScheduleStore
import com.colink.app.data.toDisplayCourses
import com.colink.app.importflow.ImportFlowService
import com.colink.app.ui.home.HomeScreen
import com.colink.app.ui.imports.ImportScreen
import com.colink.app.ui.profile.ProfileScreen
import com.colink.app.ui.schedule.ScheduleScreen
import com.colink.app.ui.theme.ColinkTheme
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Paper
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ColinkApp() {
    ColinkTheme {
        val context = LocalContext.current
        val preferences = remember { context.getSharedPreferences("colink_profile", 0) }
        val scheduleStore = remember {
            SharedPrefsLocalScheduleStore(context.getSharedPreferences("colink_local_schedule", 0))
        }
        val importService = remember { ImportFlowService(scheduleStore) }
        val systemToday = LocalDate.now()
        val defaultSemesterStart = LocalDate.of(systemToday.year, 9, 1)
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var showImport by rememberSaveable { mutableStateOf(false) }
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
                    stored?.let(LocalDate::parse) ?: defaultSemesterStart
                }.getOrDefault(defaultSemesterStart),
            )
        }
        var semesterStartConfigured by remember {
            mutableStateOf(
                preferences.getBoolean(
                    "semesterStartConfigured",
                    preferences.getString("semesterStart", null)?.let { it != "2025-09-01" } ?: false,
                ),
            )
        }
        var courseLoadState by remember { mutableStateOf<CourseLoadState>(CourseLoadState.Loading) }
        var sourceFileName by remember { mutableStateOf<String?>(null) }
        var importedAt by remember { mutableStateOf<String?>(null) }
        var refreshTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(refreshTick) {
            courseLoadState = CourseLoadState.Loading
            val result = withContext(Dispatchers.IO) { scheduleStore.load() }
            courseLoadState = when (result) {
                is ScheduleLoadResult.Ok -> {
                    val schedule = result.schedule
                    sourceFileName = schedule.sourceFileName
                    importedAt = schedule.importedAt
                    if (!semesterStartConfigured) {
                        schedule.semester.startDate?.let { storedStart ->
                            runCatching { LocalDate.parse(storedStart) }.getOrNull()?.let { parsed ->
                                if (parsed != semesterStart) {
                                    semesterStart = parsed
                                    preferences.edit().putString("semesterStart", parsed.toString()).apply()
                                }
                            }
                        }
                    }
                    CourseLoadState.Ready(
                        classId = "local",
                        className = schedule.semester.className ?: profile.className,
                        courses = schedule.toDisplayCourses(),
                        semester = SemesterMeta(
                            startDate = schedule.semester.startDate,
                            maxWeek = schedule.semester.maxWeek,
                        ),
                    )
                }
                is ScheduleLoadResult.Empty -> {
                    sourceFileName = null
                    importedAt = null
                    CourseLoadState.Ready(
                        classId = "local",
                        className = profile.className,
                        courses = emptyList(),
                        semester = SemesterMeta(),
                    )
                }
                is ScheduleLoadResult.Corrupt -> {
                    sourceFileName = null
                    importedAt = null
                    CourseLoadState.Error("课表数据损坏，请重新导入")
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper, GreenSoft)))) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    if (!showImport) {
                        NavigationBar(containerColor = Color(0xFFFCFDFB)) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                                label = { Text("首页") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "课表") },
                                label = { Text("课表") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
                                label = { Text("我的") },
                            )
                        }
                    }
                },
            ) { padding ->
                if (showImport) {
                    ImportScreen(
                        service = importService,
                        onDone = {
                            showImport = false
                            refreshTick += 1
                        },
                        onClose = {
                            showImport = false
                            refreshTick += 1
                        },
                        onStartDateConfirmed = { date ->
                            semesterStart = date
                            semesterStartConfigured = true
                            preferences.edit()
                                .putString("semesterStart", date.toString())
                                .putBoolean("semesterStartConfigured", true)
                                .apply()
                        },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    AppContent(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        padding = padding,
                        profile = profile,
                        semesterStart = semesterStart,
                        onSemesterStartChange = { date ->
                            semesterStart = date
                            semesterStartConfigured = true
                            preferences.edit()
                                .putString("semesterStart", date.toString())
                                .putBoolean("semesterStartConfigured", true)
                                .apply()
                        },
                        maxWeek = (courseLoadState as? CourseLoadState.Ready)?.semester?.maxWeek ?: 32,
                        courseLoadState = courseLoadState,
                        onRetry = { refreshTick += 1 },
                        onImportClick = { showImport = true },
                        sourceFileName = sourceFileName,
                        importedAt = importedAt,
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
}

@Composable
private fun AppContent(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    padding: PaddingValues,
    profile: UserProfile,
    semesterStart: LocalDate,
    onSemesterStartChange: (LocalDate) -> Unit,
    maxWeek: Int,
    courseLoadState: CourseLoadState,
    onRetry: () -> Unit,
    onImportClick: () -> Unit,
    sourceFileName: String?,
    importedAt: String?,
    onSaveProfile: (UserProfile) -> Unit,
) {
    when (selectedTab) {
        0 -> HomeScreen(
            profileName = profile.name,
            semesterStart = semesterStart,
            maxWeek = maxWeek,
            loadState = courseLoadState,
            onRetry = onRetry,
            onOpenSchedule = { onSelectTab(1) },
            onImportClick = onImportClick,
            modifier = Modifier.padding(padding),
        )
        1 -> ScheduleScreen(
            semesterStart = semesterStart,
            maxWeek = maxWeek,
            loadState = courseLoadState,
            onRetry = onRetry,
            onImportClick = onImportClick,
            modifier = Modifier.padding(padding),
        )
        else -> ProfileScreen(
            profile = profile,
            semesterStart = semesterStart,
            onSemesterStartChange = onSemesterStartChange,
            onSave = onSaveProfile,
            sourceFileName = sourceFileName,
            importedAt = importedAt,
            onImportClick = onImportClick,
            modifier = Modifier.padding(padding),
        )
    }
}
