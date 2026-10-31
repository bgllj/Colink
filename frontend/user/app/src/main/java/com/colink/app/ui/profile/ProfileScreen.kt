package com.colink.app.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.colink.app.UserProfile
import com.colink.app.ui.theme.Green
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Ink
import com.colink.app.ui.theme.Moss
import com.colink.app.ui.theme.PaperLight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: UserProfile,
    semesterStart: LocalDate,
    onSemesterStartChange: (LocalDate) -> Unit,
    onSave: (UserProfile) -> Unit,
    modifier: Modifier = Modifier,
    sourceFileName: String? = null,
    importedAt: String? = null,
    onImportClick: () -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingSemesterStart by rememberSaveable { mutableStateOf(false) }
    var draft by remember(profile) { mutableStateOf(profile) }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFFF6F8F6)),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("我的", style = MaterialTheme.typography.headlineMedium, color = Ink, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("个人资料与本地课表设置", style = MaterialTheme.typography.bodyMedium, color = Moss)
        }
        item { ProfileHero(profile) }
        item {
            AnimatedContent(
                targetState = editing,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "profileMode",
            ) { isEditing ->
                if (isEditing) {
                    ProfileEditor(
                        draft = draft,
                        onDraftChanged = { draft = it },
                        onCancel = { draft = profile; editing = false },
                        onSave = { onSave(draft); editing = false },
                    )
                } else {
                    ProfileOverview(
                        profile = profile,
                        semesterStart = semesterStart,
                        sourceFileName = sourceFileName,
                        importedAt = importedAt,
                        onEdit = { editing = true },
                        onEditSemesterStart = { editingSemesterStart = true },
                        onImportClick = onImportClick,
                    )
                }
            }
        }
    }
    if (editingSemesterStart) {
        val state = rememberDatePickerState(initialSelectedDateMillis = semesterStart.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { editingSemesterStart = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis -> onSemesterStartChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) }
                    editingSemesterStart = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingSemesterStart = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun ProfileHero(profile: UserProfile) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Ink), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = .14f), modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp)) }
            }
            Spacer(Modifier.size(15.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name.ifBlank { "同学" }, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("本机资料", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .74f))
            }
        }
    }
}

@Composable
private fun ProfileOverview(
    profile: UserProfile,
    semesterStart: LocalDate,
    sourceFileName: String?,
    importedAt: String?,
    onEdit: () -> Unit,
    onEditSemesterStart: () -> Unit,
    onImportClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsSection(title = "个人设置") {
            SettingsRow(
                icon = Icons.Default.Person,
                title = "显示名称",
                description = "用于首页问候，不同步到服务器",
                onClick = onEdit,
                trailing = "编辑",
            )
        }
        SettingsSection(title = "课表与学期") {
            Column {
                SettingsRow(
                    icon = Icons.Default.CalendarMonth,
                    title = "开学时间",
                    description = semesterStart.format(DateTimeFormatter.ofPattern("yyyy年M月d日")),
                    onClick = onEditSemesterStart,
                )
                androidx.compose.material3.HorizontalDivider(color = Color(0xFFE7ECE8), modifier = Modifier.padding(start = 68.dp, end = 16.dp))
                SettingsRow(
                    icon = Icons.Default.FolderOpen,
                    title = "本地课表",
                    description = sourceFileName?.let { "$it · ${formatImportedAt(importedAt)} 导入" } ?: "尚未导入课表",
                    onClick = onImportClick,
                    trailing = if (sourceFileName == null) "导入" else "更换",
                )
            }
        }
        Text("课表保存在本机，可离线查看。", modifier = Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.bodySmall, color = Moss)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(18.dp), color = PaperLight, modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(11.dp), color = GreenSoft, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.size(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = Moss, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = Green, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Moss, modifier = Modifier.size(20.dp))
    }
}

private fun formatImportedAt(importedAt: String?): String {
    if (importedAt.isNullOrBlank()) return "时间未知"
    return runCatching {
        Instant.parse(importedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(importedAt)
}

@Composable
private fun ProfileEditor(draft: UserProfile, onDraftChanged: (UserProfile) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("编辑资料", style = MaterialTheme.typography.titleLarge, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Edit, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
        }
        Surface(shape = RoundedCornerShape(20.dp), color = PaperLight, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileField("姓名", draft.name) { onDraftChanged(draft.copy(name = it)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存资料") }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
