package com.kuguan.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.data.repo.HistoryRevokeResult
import com.kuguan.app.data.repo.RestoreResult
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.domain.query.LookupText
import com.kuguan.app.ui.theme.SemanticAction
import com.kuguan.app.ui.theme.colorFor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 空批号按 seq 独立成组；继承批号的改写记录仍属于原批。 */
data class HistoryGroup(val key: String, val batchId: String?, val operations: List<OperationEntity>) {
    val latestSeq: Long get() = operations.maxOf { it.seq }
}

fun groupHistory(operations: List<OperationEntity>): List<HistoryGroup> =
    operations.groupBy { it.batchId?.let { id -> "batch:$id" } ?: "single:${it.seq}" }
        .map { (key, rows) -> HistoryGroup(key, rows.first().batchId, rows.sortedByDescending { it.seq }) }
        .sortedByDescending { it.latestSeq }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: StorageRepository,
    bottomPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val history by repository.observeHistory().collectAsState(initial = emptyList())
    val operations = remember(history) { history.map { it.operation } }
    val itemNames = remember(history) { history.associate { it.operation.id to it.itemName } }
    val deleted by repository.observeDeletedItems().collectAsState(initial = emptyList())
    val groups = remember(operations) { groupHistory(operations) }
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<OperationEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    pending?.let { op ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("撤销第 ${op.seq} 条？") },
            text = { Text("这条记录的库存会回退；其他物品的记录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        message = when (val result = repository.revokeFromHistory(op.seq)) {
                            is HistoryRevokeResult.Revoked -> "第 ${result.operation.seq} 条已撤销"
                            is HistoryRevokeResult.Rejected -> result.reason
                        }
                    }
                }) { Text("确认撤销") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (trash) "回收站" else "历史") },
            navigationIcon = {
                IconButton(onClick = { if (trash) trash = false else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                TextButton(onClick = { trash = !trash; message = null }) {
                    Text(if (trash) "历史" else "回收站（${deleted.size}）")
                }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            message?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary)
            }
            val listPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = bottomPadding.calculateBottomPadding() + 16.dp,
            )
            if (trash) {
                if (deleted.isEmpty()) {
                    HistoryEmpty("回收站是空的")
                } else {
                    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(deleted, key = { it.id }) { item ->
                            TrashRow(item) {
                                scope.launch {
                                    message = when (val result = repository.restoreItem(item.id)) {
                                        RestoreResult.Restored -> "「${item.name}」已恢复；可在搜索页查看位置"
                                        is RestoreResult.Rejected -> result.reason
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (groups.isEmpty()) {
                HistoryEmpty("还没有任何记录\n按住主屏的按钮说一句话试试")
            } else {
                LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { it.key }) { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (group.batchId == null) "单条 · 第 ${group.latestSeq} 条"
                                else "同一批 · ${group.operations.size} 条 · 最新第 ${group.latestSeq} 条",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            group.operations.forEach { op ->
                                OperationRow(op, itemNames[op.id],
                                    onClick = { op.itemId?.let(onOpenItem) },
                                    onRevoke = { pending = op })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmpty(message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.History, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrashRow(item: ItemEntity, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Inventory2, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text("删除于 ${item.deletedAt?.let(::formatTime) ?: "未知"}",
                    style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = onRestore) { Text("恢复") }
        }
    }
}

@Composable
fun OperationRow(
    operation: OperationEntity,
    itemName: String?,
    onClick: () -> Unit,
    onRevoke: () -> Unit = {},
) {
    val revoked = operation.revokedAt != null
    val inventory = operation.type == OperationType.PUT || operation.type == OperationType.TAKE
    val accent = when {
        revoked -> colorFor(SemanticAction.Revoked)
        operation.type == OperationType.PUT -> colorFor(SemanticAction.Put)
        operation.type == OperationType.TAKE -> colorFor(SemanticAction.Take)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(modifier = Modifier.fillMaxWidth()
        .clickable(enabled = !revoked && operation.itemId != null) { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(when (operation.type) {
                OperationType.PUT -> Icons.Filled.ArrowDownward
                OperationType.TAKE -> Icons.Filled.ArrowUpward
                OperationType.DELETE -> Icons.Filled.DeleteOutline
                else -> Icons.Filled.Edit
            }, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(buildString {
                    append(when (operation.type) {
                        OperationType.PUT -> "放入"
                        OperationType.TAKE -> "取出"
                        OperationType.EDIT -> "属性修改"
                        OperationType.CORRECTION -> "纠错"
                        OperationType.DELETE -> "删除"
                        else -> operation.type
                    })
                    itemName?.let { append(" · ").append(it) }
                }, style = MaterialTheme.typography.titleMedium,
                    color = if (revoked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface)
                val detail = if (operation.type == OperationType.EDIT) {
                    operation.rawText.orEmpty()
                } else {
                    listOfNotNull(operation.locationCode,
                        operation.quantity?.let { "× ${LookupText.quantity(it)}" }).joinToString(" · ")
                }
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("第 ${operation.seq} 条 · ${formatTime(operation.createdAt)}" +
                    if (revoked) " · 已撤销" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!revoked && inventory) OutlinedButton(onClick = onRevoke) { Text("撤销") }
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
