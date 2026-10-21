package com.kuguan.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConfigDirectoryScreen(
    bottomPadding: PaddingValues,
    onVoiceAi: () -> Unit,
    onBackup: () -> Unit,
    onStorage: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(bottomPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("配置", style = MaterialTheme.typography.headlineMedium)
        ConfigEntry("语音与 AI", "阿里云 ASR、DeepSeek 主线路、备用 LLM（非必填）", onVoiceAi)
        ConfigEntry("备份与恢复", "本地导出导入、坚果云、自建服务器", onBackup)
        ConfigEntry("存储与排查", "占用空间、导出排查数据、清理排查数据", onStorage)
    }
}

@Composable
private fun ConfigEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
