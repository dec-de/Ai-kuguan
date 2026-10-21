package com.kuguan.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kuguan.app.data.backup.*
import com.kuguan.app.data.backup.cloud.*
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.voice.AppStorageSnapshot
import com.kuguan.app.data.voice.AppStorageUsage
import com.kuguan.app.data.voice.SpeechCaseStore
import com.kuguan.app.data.voice.readableBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class BackupSection { ALL, BACKUP, STORAGE }

@Composable
fun BackupScreen(
    bottomPadding: PaddingValues,
    database: KuguanDatabase,
    section: BackupSection = BackupSection.ALL,
    onBack: (() -> Unit)? = null,
    onRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val service = remember(database) { BackupService(database, context.applicationContext) }
    val coordinator = remember(context) { CloudBackupCoordinator(context.applicationContext) }
    val settings = remember(context) { CloudBackupSettings(context.applicationContext) }
    val cases = remember(context) { SpeechCaseStore(context.filesDir) }
    val storageUsage = remember(context) { AppStorageUsage(context.applicationContext, cases) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember(section) { mutableStateOf(if (section == BackupSection.STORAGE)
        "语音排查数据保存在应用私有目录。" else "两处云端各自独立保存明文 JSON 备份。") }
    var pending by remember { mutableStateOf<StagedBackup?>(null) }
    var counts by remember { mutableStateOf(BackupCounts(0, 0, 0)) }
    var storage by remember { mutableStateOf<AppStorageSnapshot?>(null) }
    var confirmClearCases by remember { mutableStateOf(false) }

    fun refreshStorage() { scope.launch { storage = withContext(Dispatchers.IO) { storageUsage.read() } } }
    LaunchedEffect(section) {
        if (section != BackupSection.BACKUP) storage = withContext(Dispatchers.IO) { storageUsage.read() }
    }

    fun runTask(block: suspend () -> String) { scope.launch {
        busy = true
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess { message = it }.onFailure { message = it.message ?: "操作失败" }
        busy = false
    } }
    fun preview(staged: StagedBackup) { scope.launch { pending?.discard(); counts = service.currentCounts(); pending = staged } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runTask {
            context.contentResolver.openOutputStream(uri, "wt")?.use { service.exportTo(it) } ?: error("无法写入文件")
            "备份已导出"
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { service.stageImport(it) } ?: error("无法读取文件")
            } }.onSuccess(::preview).onFailure { message = "文件无效：${it.message ?: "读取失败"}" }
            busy = false
        }
    }
    val exportCases = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runTask {
            context.contentResolver.openOutputStream(uri, "wt")?.use { cases.exportTo(it) }
                ?: error("无法写入排查文件")
            "排查数据已导出"
        }
    }

    Column(Modifier.fillMaxSize().padding(bottomPadding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回配置")
            }
            Text(if (section == BackupSection.STORAGE) "存储与排查" else "备份与恢复",
                style = MaterialTheme.typography.headlineMedium)
        }
        Text(message)
        if (section != BackupSection.BACKUP) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("应用存储", style = MaterialTheme.typography.titleLarge)
                if (storage == null) {
                    Text("正在计算占用空间…")
                } else {
                    val usage = storage!!
                    Text("应用总占用：${readableBytes(usage.totalBytes)}")
                    Text("应用数据：${readableBytes(usage.dataBytes)}")
                    Text("数据库（含日志文件）：${readableBytes(usage.databaseBytes)}")
                    Text("异常排查文件：${readableBytes(usage.caseBytes)}")
                }
                Text("总占用包含安装包和应用数据。每次语音的 WAV 和处理结果保存在应用私有目录；不会包含在库存备份中。清除后无法恢复，已导出的 ZIP 不受影响。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::refreshStorage) { Text("刷新容量") }
                    OutlinedButton(onClick = { exportCases.launch("收纳语音排查-${LocalDate.now()}.zip") }) { Text("导出排查数据") }
                }
                Button(enabled = !busy && (storage?.caseBytes ?: 0L) > 0L,
                    onClick = { confirmClearCases = true }) { Text("清除异常排查数据") }
            }
        }
        }
        if (section != BackupSection.STORAGE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { export.launch("收纳备份-${LocalDate.now()}.json") }) { Text("导出文件") }
            Button(enabled = !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("导入文件") }
        }
        CloudCard(CloudDestination.NUTSTORE, settings, busy,
            onSave = { d, v -> runTask { settings.save(d, v); CloudBackupScheduler.sync(context, d); "坚果云配置已保存" } },
            onTest = { d, v -> runTask { coordinator.store(d, v).testConnection(); "坚果云连接成功" } },
            onBackup = { d -> runTask { val e = coordinator.backupNow(d); "坚果云备份成功：${e.sizeBytes} 字节" } },
            onList = { d, receive -> runTask { val list = coordinator.list(d); withContext(Dispatchers.Main) { receive(list) }; "坚果云有 ${list.size} 份备份" } },
            onRestore = { d, e -> scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) {
                    val file = coordinator.download(d, e)
                    try { file.inputStream().use { service.stageImport(it) } } finally { file.delete() }
                } }.onSuccess(::preview).onFailure { message = "下载失败：${it.message ?: "校验失败"}" }
                busy = false
            } })
        CloudCard(CloudDestination.SELF_HOSTED, settings, busy,
            onSave = { d, v -> runTask { settings.save(d, v); CloudBackupScheduler.sync(context, d); "自建服务器配置已保存" } },
            onTest = { d, v -> runTask { coordinator.store(d, v).testConnection(); "自建服务器连接成功" } },
            onBackup = { d -> runTask { val e = coordinator.backupNow(d); "自建服务器备份成功：${e.sizeBytes} 字节" } },
            onList = { d, receive -> runTask { val list = coordinator.list(d); withContext(Dispatchers.Main) { receive(list) }; "自建服务器有 ${list.size} 份备份" } },
            onRestore = { d, e -> scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) {
                    val file = coordinator.download(d, e)
                    try { file.inputStream().use { service.stageImport(it) } } finally { file.delete() }
                } }.onSuccess(::preview).onFailure { message = "下载失败：${it.message ?: "校验失败"}" }
                busy = false
            } })
        }
    }
    pending?.let { staged -> AlertDialog(
        onDismissRequest = { staged.discard(); pending = null }, title = { Text("确认覆盖现有数据") },
        text = { Text("将覆盖现有 ${counts.items} 条物品、${counts.locations} 条位置、${counts.operations} 条历史。备份包含 ${staged.preview.items} 条物品、${staged.preview.locations} 条位置、${staged.preview.operations} 条历史。") },
        confirmButton = { TextButton(onClick = { pending = null; runTask {
            service.restoreStaged(staged)
            withContext(Dispatchers.Main) { onRestored() }
            "恢复完成"
        } }) { Text("覆盖并导入") } },
        dismissButton = { TextButton(onClick = { staged.discard(); pending = null }) { Text("取消") } }) }
    if (confirmClearCases) AlertDialog(
        onDismissRequest = { confirmClearCases = false },
        title = { Text("清除异常排查数据？") },
        text = { Text("将删除应用私有目录中的录音 WAV、ASR 和 LLM 结果；库存数据库、历史和云端备份不会删除。") },
        confirmButton = { TextButton(onClick = {
            confirmClearCases = false
            runTask {
                cases.clear()
                val updated = storageUsage.read()
                withContext(Dispatchers.Main) { storage = updated }
                "异常排查数据已清除"
            }
        }) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = { confirmClearCases = false }) { Text("取消") } },
    )
}

@Composable
private fun CloudCard(
    destination: CloudDestination, settings: CloudBackupSettings, busy: Boolean,
    onSave: (CloudDestination, DestinationSettings) -> Unit,
    onTest: (CloudDestination, DestinationSettings) -> Unit,
    onBackup: (CloudDestination) -> Unit,
    onList: (CloudDestination, (List<CloudBackupEntry>) -> Unit) -> Unit,
    onRestore: (CloudDestination, CloudBackupEntry) -> Unit,
) {
    val current = settings.get(destination)
    var account by remember(destination) { mutableStateOf(current.account) }
    var secret by remember(destination) { mutableStateOf(current.secret) }
    var url by remember(destination) { mutableStateOf(current.serverUrl) }
    var enabled by remember(destination) { mutableStateOf(current.enabled) }
    var wifi by remember(destination) { mutableStateOf(current.wifiOnly) }
    var interval by remember(destination) { mutableStateOf(current.intervalMinutes.toString()) }
    var history by remember(destination) { mutableStateOf<List<CloudBackupEntry>>(emptyList()) }
    val title = if (destination == CloudDestination.NUTSTORE) "坚果云 WebDAV" else "自建服务器"
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("云端文件为明文 JSON；有云盘或服务器文件权限的人可以读取物品与历史。")
        if (destination == CloudDestination.NUTSTORE) {
            OutlinedTextField(account, { account = it }, label = { Text("坚果云注册邮箱") }, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS API 地址") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(secret, { secret = it }, label = { Text(if (destination == CloudDestination.NUTSTORE) "第三方应用密码" else "访问令牌") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row { Checkbox(enabled, { enabled = it }); Text("启用自动备份", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(wifi, { wifi = it }); Text("仅 Wi-Fi", Modifier.padding(top = 12.dp)) }
        OutlinedTextField(interval, { interval = it }, label = { Text("间隔（分钟，15 的倍数）") }, modifier = Modifier.fillMaxWidth())
        Text("常用：60 / 360 / 720 / 1440；范围 15–43200，默认 720。")
        val minutes = interval.toLongOrNull()
        if (minutes != null && minutes > 0 && current.lastSizeBytes > 0) {
            Text("估算月上传 ${"%.1f".format(current.lastSizeBytes * 43_200.0 / minutes / 1_000_000)} MB；30 份跨度约 ${"%.1f".format(minutes * 29.0 / 1440)} 天。")
        }
        Text("上次成功：${if (current.lastSuccessAt == 0L) "暂无" else Instant.ofEpochMilli(current.lastSuccessAt).atZone(ZoneId.systemDefault())}；大小 ${current.lastSizeBytes} 字节")
        if (current.lastError.isNotBlank()) Text("上次失败：${current.lastError}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { onSave(destination, current.copy(account = account.trim(), secret = secret, serverUrl = url.trim(), enabled = enabled, wifiOnly = wifi, intervalMinutes = interval.toLongOrNull() ?: 0)) }) { Text("保存") }
            Button(enabled = !busy, onClick = { onTest(destination, current.copy(account = account.trim(), secret = secret, serverUrl = url.trim())) }) { Text("测试连接") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { onBackup(destination) }) { Text("立即备份") }
            Button(enabled = !busy, onClick = { onList(destination) { history = it } }) { Text("查看历史") }
        }
        history.forEach { e -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Text(e.createdAtUtc.ifBlank { e.id.toString() }); Text("${e.sizeBytes} 字节") }
            TextButton(enabled = !busy, onClick = { onRestore(destination, e) }) { Text("恢复") }
        } }
    } }
}
