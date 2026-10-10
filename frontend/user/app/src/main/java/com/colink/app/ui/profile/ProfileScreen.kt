package com.colink.app.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colink.app.UserProfile
import com.colink.app.ui.theme.Green
import com.colink.app.ui.theme.Moss
import com.colink.app.ui.theme.PaperLight
import java.time.LocalDate
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
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingSemesterStart by rememberSaveable { mutableStateOf(false) }
    var draft by remember(profile) { mutableStateOf(profile) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("我的", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("个人信息仅保存在本机", style = MaterialTheme.typography.bodyMedium, color = Moss)
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
                    ProfileSummary(profile, semesterStart, onEdit = { editing = true }, onEditSemesterStart = { editingSemesterStart = true })
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
                    state.selectedDateMillis?.let { millis -> onSemesterStartChange(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) }
                    editingSemesterStart = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingSemesterStart = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun ProfileHero(profile: UserProfile) {
    Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Green), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = .18f), modifier = Modifier.size(58.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
            Spacer(Modifier.size(15.dp))
            Column {
                Text(profile.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(profile.studentId, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .82f))
            }
        }
    }
}

@Composable
private fun ProfileSummary(profile: UserProfile, semesterStart: LocalDate, onEdit: () -> Unit, onEditSemesterStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("学籍信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = PaperLight), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SummaryRow("院系", profile.college)
                SummaryRow("专业", profile.major)
                SummaryRow("年级", profile.grade)
                SummaryRow("班级", profile.className)
            }
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(7.dp)); Text("编辑个人资料")
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = PaperLight), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开学时间", style = MaterialTheme.typography.labelLarge, color = Moss)
                    Text(semesterStart.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("当前日期跟随系统自动更新", style = MaterialTheme.typography.bodySmall, color = Moss)
                }
                OutlinedButton(onClick = onEditSemesterStart) { Text("设置") }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(.32f), style = MaterialTheme.typography.labelLarge, color = Moss)
        Text(value, modifier = Modifier.weight(.68f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileEditor(draft: UserProfile, onDraftChanged: (UserProfile) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("编辑资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ProfileField("姓名", draft.name) { onDraftChanged(draft.copy(name = it)) }
        ProfileField("学号", draft.studentId) { onDraftChanged(draft.copy(studentId = it)) }
        ProfileField("院系", draft.college) { onDraftChanged(draft.copy(college = it)) }
        ProfileField("专业", draft.major) { onDraftChanged(draft.copy(major = it)) }
        ProfileField("年级", draft.grade) { onDraftChanged(draft.copy(grade = it)) }
        ProfileField("班级", draft.className) { onDraftChanged(draft.copy(className = it)) }
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
