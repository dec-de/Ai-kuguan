package com.kuguan.app.ui.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kuguan.app.data.reminder.ReminderManager
import com.kuguan.app.domain.reminder.ReminderEntry
import com.kuguan.app.domain.reminder.ReminderKind
import com.kuguan.app.domain.query.LookupText
import kotlinx.coroutines.launch

@Composable
fun ReminderScreen(bottomPadding: PaddingValues, onOpenItem: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { ReminderManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf(emptyList<ReminderEntry>()) }
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(manager) {
        enabled = manager.enabled()
        entries = manager.entries()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(bottomPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("今日提醒", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("每天 20:00 提醒")
                        Switch(checked = enabled, onCheckedChange = { value ->
                            enabled = value
                            scope.launch { manager.setEnabled(value) }
                        })
                    }
                    Text("系统可能延后送达；打开应用后会补发当天未送达的提醒。")
                    if (!permission) {
                        Text("通知权限未开启，无法发送提醒。")
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)))
                        }) { Text("打开系统通知设置") }
                    }
                }
            }
        }
        if (entries.isEmpty()) item { Text("今天没有待处理提醒") }
        items(entries, key = { it.id }) { entry ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text("位置：${entry.location}")
                    reasons(entry).forEach { Text(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenItem(entry.id) }) { Text("查看物品") }
                        if (entry.inventoryKind == null && entry.kind != ReminderKind.LOW_STOCK &&
                            entry.kind != ReminderKind.OUT_OF_STOCK) {
                            Button(onClick = {
                                scope.launch {
                                    manager.dismiss(entry.id)
                                    entries = manager.entries()
                                }
                            }) { Text("已处理") }
                        }
                    }
                }
            }
        }
    }
}

internal fun reasons(entry: ReminderEntry): List<String> {
    val result = mutableListOf<String>()
    when (entry.kind) {
        ReminderKind.EXPIRED -> result += "已过期 ${-entry.days} 天"
        ReminderKind.EXPIRES_30, ReminderKind.EXPIRES_60 -> result += "${entry.days} 天后到期"
        ReminderKind.COLD -> result += if (entry.days > 365) "${entry.days} 天未取用" else "从未取用"
        ReminderKind.LOW_STOCK, ReminderKind.OUT_OF_STOCK -> Unit
    }
    when (entry.inventoryKind ?: entry.kind.takeIf {
        it == ReminderKind.LOW_STOCK || it == ReminderKind.OUT_OF_STOCK
    }) {
        ReminderKind.OUT_OF_STOCK -> result += "已用完"
        ReminderKind.LOW_STOCK -> result += "现存 ${LookupText.quantity(entry.quantity)} 个，已达到提醒数量 ${LookupText.quantity(entry.threshold ?: 0.0)} 个"
        else -> Unit
    }
    return result
}
