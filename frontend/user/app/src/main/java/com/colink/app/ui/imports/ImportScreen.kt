package com.colink.app.ui.imports

import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.colink.app.domain.Issue
import com.colink.app.domain.IssueCode
import com.colink.app.domain.Severity
import com.colink.app.importflow.ConfirmResult
import com.colink.app.importflow.ImportFlowService
import com.colink.app.importflow.ImportPreview
import com.colink.app.importflow.ImportStatus
import com.colink.app.importflow.MAX_UPLOAD_BYTES
import com.colink.app.importflow.PreviewRow
import com.colink.app.importflow.RowStatus
import com.colink.app.importflow.describeImportFailure
import com.colink.app.ui.theme.Green
import com.colink.app.ui.theme.GreenSoft
import com.colink.app.ui.theme.Ink
import com.colink.app.ui.theme.Line
import com.colink.app.ui.theme.Moss
import com.colink.app.ui.theme.PaperLight
import com.colink.app.ui.theme.Sand
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WARN = Color(0xFFB8794D)
private val WEEKDAY_NAMES = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
private val PICK_MIME_TYPES = arrayOf(
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)
private val dateDisplayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ImportScreen(
    service: ImportFlowService,
    onDone: () -> Unit,
    onClose: () -> Unit,
    onStartDateConfirmed: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var maxWeekInput by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    var rejectMessage by remember { mutableStateOf<String?>(null) }
    var replaceDialogVisible by remember { mutableStateOf(false) }
    var successSummary by remember { mutableStateOf<String?>(null) }
    var startDateOverride by remember { mutableStateOf<LocalDate?>(null) }
    var startDateDialogVisible by remember { mutableStateOf(false) }

    suspend fun ingestNow(name: String, bytes: ByteArray, maxWeekOverride: Int?) {
        parsing = true
        parseError = null
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                service.ingest(
                    name,
                    bytes,
                    maxWeekOverride = maxWeekOverride,
                    onExtractionFailure = { failure -> logImportFailure("extract", failure) },
                )
            }
        }
        parsing = false
        outcome.fold(
            onSuccess = { result ->
                preview = result
                startDateOverride = null
                selectedIds = result.rows
                    .filter { it.status == RowStatus.PARSED && it.selected }
                    .map { it.id }
                    .toSet()
            },
            onFailure = { error ->
                logImportFailure("ingest", error)
                preview = null
                parseError = describeImportFailure(error)
            },
        )
    }

    suspend fun confirmNow(replace: Boolean) {
        val current = preview ?: return
        val rowIds = selectedIds.toList()
        confirming = true
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                service.confirm(
                    current,
                    rowIds = rowIds,
                    replace = replace,
                    startDateOverride = startDateOverride,
                )
            }
        }
        confirming = false
        outcome.fold(
            onSuccess = { result ->
                replaceDialogVisible = false
                when (result) {
                    is ConfirmResult.Confirmed -> {
                        startDateOverride?.let(onStartDateConfirmed)
                        val parts = mutableListOf("已导入 ${result.rowsConfirmed} 行")
                        if (result.rowsSkipped > 0) parts.add("跳过 ${result.rowsSkipped} 行")
                        val nonImportable = current.rows.count { it.status != RowStatus.PARSED }
                        if (nonImportable > 0) parts.add("$nonImportable 行含警告或错误未入库")
                        successSummary = parts.joinToString("，")
                    }
                    is ConfirmResult.Rejected -> {
                        if (result.code == IssueCode.SCHEDULE_EXISTS) {
                            replaceDialogVisible = true
                        } else {
                            rejectMessage = result.message
                        }
                    }
                }
            },
            onFailure = { error ->
                rejectMessage = error.message ?: "确认导入失败，请重试"
            },
        )
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            parsing = true
            parseError = null
            preview = null
            startDateOverride = null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }
                        ?.takeIf { it.isNotBlank() }
                        ?: "课表文件"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        readBounded(input, MAX_UPLOAD_BYTES + 1)
                    } ?: error("无法读取所选文件")
                    name to bytes
                }
            }
            loaded.fold(
                onSuccess = { (name, bytes) ->
                    fileName = name
                    fileBytes = bytes
                    ingestNow(name, bytes, maxWeekOverride = null)
                },
                onFailure = { error ->
                    parsing = false
                    logImportFailure("read", error)
                    preview = null
                    parseError = describeImportFailure(error)
                },
            )
        }
    }

    val currentPreview = preview
    val currentSuccess = successSummary
    val currentError = parseError

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "导入课表",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("取消") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                currentSuccess != null -> ImportSuccessContent(
                    summary = currentSuccess,
                    onDone = onDone,
                )
                parsing -> ImportParsingContent(fileName = fileName)
                currentError != null -> ImportFailedContent(
                    title = "读取或解析失败",
                    message = currentError,
                    issues = emptyList(),
                    onRepick = { pickFile.launch(PICK_MIME_TYPES) },
                )
                currentPreview == null -> ImportPickContent(
                    onPick = { pickFile.launch(PICK_MIME_TYPES) },
                )
                currentPreview.status == ImportStatus.FAILED -> ImportFailedContent(
                    title = "解析失败",
                    message = "未能从文件中解析出课表，请确认是学校导出的课表 Excel（.xls / .xlsx）",
                    issues = currentPreview.issues,
                    onRepick = { pickFile.launch(PICK_MIME_TYPES) },
                )
                else -> ImportPreviewContent(
                    preview = currentPreview,
                    startDateOverride = startDateOverride,
                    onEditStartDate = { startDateDialogVisible = true },
                    selectedIds = selectedIds,
                    maxWeekInput = maxWeekInput,
                    onMaxWeekInputChange = { maxWeekInput = it.filter { char -> char in '0'..'9' }.take(3) },
                    onToggleRow = { rowId, checked ->
                        selectedIds = if (checked) selectedIds + rowId else selectedIds - rowId
                    },
                    onReparse = {
                        val bytes = fileBytes
                        val name = fileName
                        if (bytes != null && name != null) {
                            scope.launch {
                                ingestNow(name, bytes, maxWeekOverride = maxWeekInput.trim().toIntOrNull())
                            }
                        }
                    },
                    confirming = confirming,
                    onConfirm = { scope.launch { confirmNow(replace = false) } },
                )
            }
        }
    }

    if (replaceDialogVisible) {
        AlertDialog(
            onDismissRequest = { replaceDialogVisible = false },
            title = { Text("替换已有课表") },
            text = { Text("已有本地课表，替换后原课表将被覆盖删除。确认替换？") },
            confirmButton = {
                TextButton(onClick = { scope.launch { confirmNow(replace = true) } }) { Text("替换") }
            },
            dismissButton = {
                TextButton(onClick = { replaceDialogVisible = false }) { Text("取消") }
            },
        )
    }
    if (startDateDialogVisible) {
        val currentStart = startDateOverride ?: preview?.semester?.startDate ?: LocalDate.now()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = currentStart.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { startDateDialogVisible = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        startDateOverride = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    startDateDialogVisible = false
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { startDateDialogVisible = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
    rejectMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { rejectMessage = null },
            title = { Text("无法确认导入") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { rejectMessage = null }) { Text("确定") }
            },
        )
    }
}

@Composable
private fun ImportPickContent(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = PaperLight),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(vertical = 30.dp, horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = CircleShape, color = GreenSoft, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, tint = Green)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("导入课表 Excel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "选择学校下发的课表文件（.xls / .xlsx），解析与导入均在本机完成，离线也能看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Moss,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = onPick) { Text("选择文件") }
            }
        }
    }
}

@Composable
private fun ImportParsingContent(fileName: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在解析课表…", style = MaterialTheme.typography.bodyLarge)
        if (fileName != null) {
            Spacer(Modifier.height(6.dp))
            Text(fileName, style = MaterialTheme.typography.bodySmall, color = Moss)
        }
    }
}

@Composable
private fun ImportFailedContent(
    title: String,
    message: String,
    issues: List<Issue>,
    onRepick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = PaperLight),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(vertical = 28.dp, horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Moss, textAlign = TextAlign.Center)
                if (issues.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    issues.forEach { issue -> IssueEntry(issue) }
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = onRepick) { Text("重新选文件") }
            }
        }
    }
}

@Composable
private fun ImportSuccessContent(summary: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = PaperLight),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(vertical = 30.dp, horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = CircleShape, color = GreenSoft, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("导入成功", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = Moss, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onDone) { Text("返回课表") }
            }
        }
    }
}

@Composable
private fun ImportPreviewContent(
    preview: ImportPreview,
    startDateOverride: LocalDate?,
    onEditStartDate: () -> Unit,
    selectedIds: Set<String>,
    maxWeekInput: String,
    onMaxWeekInputChange: (String) -> Unit,
    onToggleRow: (String, Boolean) -> Unit,
    onReparse: () -> Unit,
    confirming: Boolean,
    onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = PaperLight),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(preview.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        val parsedCount = preview.rows.count { it.status == RowStatus.PARSED }
                        Text(
                            "共 ${preview.rows.size} 行课程 · 可入库 $parsedCount 行",
                            style = MaterialTheme.typography.bodySmall,
                            color = Moss,
                        )
                    }
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = PaperLight),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("学期信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        MetaRow("学年", preview.semester.academicYear ?: "未提供")
                        MetaRow("学期", preview.semester.semesterName ?: "未提供")
                        MetaRow("班级", preview.semester.className ?: "未提供")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MetaRow("开学日期", (startDateOverride ?: preview.semester.startDate)?.format(dateDisplayFormatter) ?: "未提供")
                                if (startDateOverride != null) {
                                    Text("已使用审核时修改的日期", style = MaterialTheme.typography.labelSmall, color = Green)
                                }
                            }
                            OutlinedButton(onClick = onEditStartDate) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("修改")
                            }
                        }
                    }
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = PaperLight),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("解析设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "生效最大周：${preview.maxWeek?.toString() ?: "未确定"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Moss,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = maxWeekInput,
                                onValueChange = onMaxWeekInputChange,
                                label = { Text("最大周数") },
                                placeholder = { Text("留空自动识别") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = onReparse) { Text("重新解析") }
                        }
                    }
                }
            }
            if (preview.issues.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = PaperLight),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text("批次提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            preview.issues.forEach { issue -> IssueEntry(issue) }
                        }
                    }
                }
            }
            item {
                Column {
                    Text("课程行预览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "仅勾选的无警告/错误行会入库；含警告或错误的行保留在列表中但不入库",
                        style = MaterialTheme.typography.labelSmall,
                        color = Moss,
                    )
                }
            }
            if (preview.rows.isEmpty()) {
                item {
                    Text(
                        "未解析到课程行，请检查文件内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WARN,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(preview.rows, key = { it.id }) { row ->
                PreviewRowCard(
                    row = row,
                    checked = row.id in selectedIds,
                    onCheckedChange = { checked -> onToggleRow(row.id, checked) },
                )
            }
        }
        HorizontalDivider(color = Line)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "已选 ${selectedIds.size} 行",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val nonImportable = preview.rows.count { it.status != RowStatus.PARSED }
                if (nonImportable > 0) {
                    Text(
                        "$nonImportable 行含警告或错误，不入库",
                        style = MaterialTheme.typography.labelSmall,
                        color = WARN,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onConfirm,
                enabled = selectedIds.isNotEmpty() && !confirming,
            ) {
                Text(if (confirming) "正在导入…" else "确认导入")
            }
        }
    }
}

@Composable
private fun PreviewRowCard(
    row: PreviewRow,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PaperLight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = row.status == RowStatus.PARSED,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rowTitle(row),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(row.status)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${weekdayName(row.weekday)} ${periodText(row)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
                Text(
                    "周次：${row.weekText?.takeIf { it.isNotBlank() } ?: "未提供"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Moss,
                )
                Text(
                    "教室：${row.roomText?.takeIf { it.isNotBlank() } ?: "待补全"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Moss,
                )
                Text(
                    "来源：${row.sheet}!${row.coordinate}${if (row.lineIndex > 0) " 第${row.lineIndex + 1}行" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Moss,
                )
                if (row.issues.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    row.issues.forEach { issue -> IssueEntry(issue) }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: RowStatus) {
    val label = when (status) {
        RowStatus.PARSED -> "可入库"
        RowStatus.NEEDS_REVIEW -> "含警告，不入库"
        RowStatus.INVALID -> "含错误，不入库"
    }
    val container = when (status) {
        RowStatus.PARSED -> GreenSoft
        RowStatus.NEEDS_REVIEW -> Sand
        RowStatus.INVALID -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    }
    val content = when (status) {
        RowStatus.PARSED -> Green
        RowStatus.NEEDS_REVIEW -> WARN
        RowStatus.INVALID -> MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IssueEntry(issue: Issue) {
    val color = when (issue.severity) {
        Severity.error -> MaterialTheme.colorScheme.error
        Severity.warning -> WARN
        Severity.info -> Moss
    }
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            "[${severityLabel(issue.severity)}] ${issue.message}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Text(issue.code.name, style = MaterialTheme.typography.labelSmall, color = Moss)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Moss,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.7f),
        )
    }
}

private fun severityLabel(severity: Severity): String = when (severity) {
    Severity.error -> "错误"
    Severity.warning -> "警告"
    Severity.info -> "提示"
}

private fun rowTitle(row: PreviewRow): String {
    val name = row.courseName?.takeIf { it.isNotBlank() } ?: "未命名课程"
    val code = row.courseCode?.takeIf { it.isNotBlank() }
    return if (code == null) name else "$name（$code）"
}

private fun weekdayName(weekday: Int): String =
    WEEKDAY_NAMES.getOrElse(weekday - 1) { "星期未知" }

private fun periodText(row: PreviewRow): String {
    val start = row.periodStart
    val end = row.periodEnd
    return when {
        start == null || end == null -> "节次未定"
        start == end -> "第 $start 节"
        else -> "第 $start–$end 节"
    }
}

private fun logImportFailure(stage: String, failure: Throwable) {
    val trace = buildString {
        var current: Throwable? = failure
        repeat(5) {
            val cause = current ?: return@repeat
            append(cause.javaClass.name)
            append('\n')
            cause.stackTrace.take(12).forEach { frame ->
                append("  at ")
                append(frame)
                append('\n')
            }
            current = cause.cause
        }
    }
    Log.e("ColinkImport", "$stage failed\n$trace")
}

/** Caps the read at [limit] bytes so oversized picks surface as FILE_TOO_LARGE instead of OOM. */
private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val read = input.read(chunk, 0, minOf(chunk.size, limit - total))
        if (read < 0) break
        out.write(chunk, 0, read)
        total += read
    }
    return out.toByteArray()
}
