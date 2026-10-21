package com.kuguan.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kuguan.app.data.settings.ConnectionTestResult
import com.kuguan.app.data.settings.VoiceConnectionTester
import com.kuguan.app.data.settings.VoiceServiceConfig
import com.kuguan.app.data.settings.VoiceServiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TestedRoute(val values: List<String>, val result: ConnectionTestResult, val at: Long)

@Composable
fun VoiceAiSettingsScreen(
    bottomPadding: PaddingValues,
    settings: VoiceServiceSettings,
    tester: VoiceConnectionTester,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
) {
    val initial = remember(settings) { settings.read() }
    var saved by remember(settings) { mutableStateOf(initial.config) }
    var needsReentry by remember(settings) { mutableStateOf(initial.needsReentry) }
    var asrUrl by rememberSaveable { mutableStateOf(initial.config.asrUrl) }
    var asrModel by rememberSaveable { mutableStateOf(initial.config.asrModel) }
    var asrKey by remember { mutableStateOf(initial.config.asrKey) }
    var primaryUrl by rememberSaveable { mutableStateOf(initial.config.primaryUrl) }
    var primaryModel by rememberSaveable { mutableStateOf(initial.config.primaryModel) }
    var primaryKey by remember { mutableStateOf(initial.config.primaryKey) }
    var fallbackUrl by rememberSaveable { mutableStateOf(initial.config.fallbackUrl) }
    var fallbackModel by rememberSaveable { mutableStateOf(initial.config.fallbackModel) }
    var fallbackKey by remember { mutableStateOf(initial.config.fallbackKey) }
    var asrAdvanced by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var asrTest by remember { mutableStateOf<TestedRoute?>(null) }
    var primaryTest by remember { mutableStateOf<TestedRoute?>(null) }
    var fallbackTest by remember { mutableStateOf<TestedRoute?>(null) }
    val scope = rememberCoroutineScope()

    val draft = VoiceServiceConfig(
        asrUrl = asrUrl.trim(), asrModel = asrModel.trim(), asrKey = asrKey.trim(),
        primaryUrl = primaryUrl.trim(), primaryModel = primaryModel.trim(), primaryKey = primaryKey.trim(),
        fallbackUrl = fallbackUrl.trim(), fallbackModel = fallbackModel.trim(), fallbackKey = fallbackKey.trim(),
    )
    SideEffect { onDirtyChange(draft != saved) }

    fun test(route: String, values: List<String>, run: suspend (VoiceServiceConfig) -> ConnectionTestResult) {
        if (busy.isNotEmpty()) return
        val current = draft
        scope.launch {
            busy = route
            val result = runCatching { withContext(Dispatchers.IO) { run(current) } }
                .getOrElse { ConnectionTestResult(false, "连接测试失败：${it.javaClass.simpleName}") }
            val tested = TestedRoute(values, result, System.currentTimeMillis())
            when (route) {
                "asr" -> asrTest = tested
                "primary" -> primaryTest = tested
                else -> fallbackTest = tested
            }
            busy = ""
        }
    }

    Column(
        Modifier.fillMaxSize().padding(bottomPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回配置") }
            Text("语音与 AI", style = MaterialTheme.typography.headlineMedium)
        }
        Text("每位使用者都在本机填写自己的服务凭据。保存后，下一次语音请求使用新配置。")
        if (needsReentry) Text("配置无法读取，请重新填写并保存。", color = MaterialTheme.colorScheme.error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("阿里云 ASR", style = MaterialTheme.typography.titleLarge)
                SavedStatus(saved.asrKey, asrTest, listOf(asrUrl.trim(), asrModel.trim(), asrKey.trim()))
                SecretField("API Key（必填）", asrKey, { asrKey = it })
                TextButton(onClick = { asrAdvanced = !asrAdvanced }) {
                    Text(if (asrAdvanced) "收起高级设置" else "高级设置：地址与模型")
                }
                if (asrAdvanced) {
                    OutlinedTextField(asrUrl, { asrUrl = it }, label = { Text("WebSocket 地址（wss://）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(asrModel, { asrModel = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(enabled = busy.isEmpty(), onClick = {
                    test("asr", listOf(asrUrl.trim(), asrModel.trim(), asrKey.trim()), tester::testAsr)
                }) { Text(if (busy == "asr") "正在测试…" else "测试 ASR 连接") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DeepSeek 主线路", style = MaterialTheme.typography.titleLarge)
                SavedStatus(saved.primaryKey, primaryTest, listOf(primaryUrl.trim(), primaryModel.trim(), primaryKey.trim()))
                OutlinedTextField(primaryUrl, { primaryUrl = it }, label = { Text("HTTPS API 地址（必填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(primaryModel, { primaryModel = it }, label = { Text("模型（必填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SecretField("API Key（必填）", primaryKey, { primaryKey = it })
                OutlinedButton(enabled = busy.isEmpty(), onClick = {
                    test("primary", listOf(primaryUrl.trim(), primaryModel.trim(), primaryKey.trim()), tester::testPrimary)
                }) { Text(if (busy == "primary") "正在测试…" else "测试主线路连接") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("备用 LLM（非必填）", style = MaterialTheme.typography.titleLarge)
                Text("主线路失败时才尝试；全部留空不会影响正常使用。第三方中转站会收到语音文稿和库存上下文。")
                SavedStatus(saved.fallbackKey, fallbackTest, listOf(fallbackUrl.trim(), fallbackModel.trim(), fallbackKey.trim()))
                OutlinedTextField(fallbackUrl, { fallbackUrl = it }, label = { Text("HTTPS API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fallbackModel, { fallbackModel = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SecretField("API Key", fallbackKey, { fallbackKey = it })
                OutlinedButton(enabled = busy.isEmpty() && draft.fallbackConfigured, onClick = {
                    test("fallback", listOf(fallbackUrl.trim(), fallbackModel.trim(), fallbackKey.trim()), tester::testFallback)
                }) { Text(if (busy == "fallback") "正在测试…" else "测试备用线路连接") }
            }
        }

        Text("连接测试使用当前表单值，可能产生少量服务费用；测试成功不会自动保存。")
        Button(enabled = busy.isEmpty(), onClick = {
            val missing = buildList {
                if (draft.asrKey.isBlank()) add("阿里云 ASR Key")
                if (draft.primaryUrl.isBlank()) add("主线路地址")
                if (draft.primaryModel.isBlank()) add("主线路模型")
                if (draft.primaryKey.isBlank()) add("主线路 Key")
            }
            if (missing.isNotEmpty()) {
                message = "请填写：${missing.joinToString("、")}"
            } else {
                scope.launch {
                    busy = "save"
                    runCatching { withContext(Dispatchers.IO) { settings.save(draft) } }
                        .onSuccess { saved = draft; needsReentry = false; message = "配置已保存，下次请求生效" }
                        .onFailure { message = it.message ?: "保存失败" }
                    busy = ""
                }
            }
        }) { Text(if (busy == "save") "正在保存…" else "保存配置") }
    }
}

@Composable
private fun SavedStatus(savedKey: String, test: TestedRoute?, currentValues: List<String>) {
    val status = when {
        test != null && test.values == currentValues -> {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(test.at))
            "当前表单${if (test.result.success) "连接成功" else "连接失败"} · $time：${test.result.message}"
        }
        savedKey.isBlank() -> "未配置"
        else -> "已保存未测试"
    }
    Text(status, color = if (test?.result?.success == false && test.values == currentValues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (savedKey.isNotBlank()) Text("已保存 Key 末尾 ${if (savedKey.length > 4) savedKey.takeLast(4) else "••••"}")
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) { delay(15_000); visible = false }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            Row {
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
                TextButton(onClick = { onValueChange(""); visible = false }) { Text("清空") }
            }
        },
    )
}
