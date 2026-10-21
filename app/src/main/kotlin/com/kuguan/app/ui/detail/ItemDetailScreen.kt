package com.kuguan.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.data.repo.EditResult
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.domain.item.ItemAttributes
import com.kuguan.app.domain.item.ItemCategory
import com.kuguan.app.domain.query.LookupText
import com.kuguan.app.ui.theme.SemanticAction
import com.kuguan.app.ui.theme.colorFor
import kotlinx.coroutines.launch

/**
 * 物品详情 —— **唯一可以编辑的地方**，可编辑物品属性与补货提醒阈值。
 *
 * 刻意**不允许改位置**（计划 7.2）：位置只能通过语音变更。
 * 这保证了「位置关系」的唯一写入通道是语音，不会出现
 * 「界面改了位置但操作历史里没有这条记录」的数据不一致。
 * 位置卡片下方有一句明说的提示 —— 不是「没做」，是「这里不能做」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    repository: StorageRepository,
    itemId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<ItemEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }

    var nameInput by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var categoryInput by remember { mutableStateOf("") }
    var expiryInput by remember { mutableStateOf("") }
    var restockInput by remember { mutableStateOf("") }
    var restockMode by remember { mutableStateOf(RestockMode.OFF) }
    var editingRestock by remember { mutableStateOf(false) }
    var restockMessage by remember { mutableStateOf<String?>(null) }
    var restockMessageIsError by remember { mutableStateOf(false) }

    /** 保存结果：成功用常规色，校验/冲突用 error 色。 */
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    val locations by repository.observeLocationsOf(itemId).collectAsState(initial = emptyList())
    val allOperations by repository.observeOperationsOf(itemId).collectAsState(initial = emptyList())
    val operations = remember(allOperations) { inventoryOperations(allOperations) }

    LaunchedEffect(itemId) {
        item = repository.findItem(itemId)
        loaded = true
    }

    // 库里的值 → 输入框。`updatedAt` 进 key：保存成功后重新灌一次，
    // 保证界面显示的就是库里的值（而不是「提交了什么就显示什么」）。
    LaunchedEffect(item?.id, item?.updatedAt) {
        item?.let { current ->
            nameInput = current.name
            aliasInput = ItemAttributes.decodeAliases(current.aliases).joinToString("、")
            categoryInput = current.category.orEmpty()
            expiryInput = current.expiryDate.orEmpty()
            restockMode = RestockMode.fromThreshold(current.restockThreshold)
            restockInput = current.restockThreshold?.takeIf { it > 0.0 }?.let(LookupText::quantity).orEmpty()
        }
    }

    fun saveRestock() {
        val current = item ?: return
        val threshold = when (restockMode) {
            RestockMode.OFF -> null
            RestockMode.ZERO_ONLY -> 0.0
            RestockMode.THRESHOLD -> parsePositiveRestockThreshold(restockInput) ?: run {
                restockMessage = "提醒数量须为大于 0 的有效数字，可填写小数"
                restockMessageIsError = true
                return
            }
        }
        scope.launch {
            when (val result = repository.setReplenishmentThreshold(current.id, threshold)) {
                is EditResult.Saved -> {
                    item = result.item
                    editingRestock = false
                    restockMessage = "补货提醒已保存"
                    restockMessageIsError = false
                }
                is EditResult.Unchanged -> {
                    editingRestock = false
                    restockMessage = "设置没有改动"
                    restockMessageIsError = false
                }
                is EditResult.Rejected -> {
                    restockMessage = result.reason
                    restockMessageIsError = true
                }
            }
        }
    }

    fun save() {
        val current = item ?: return
        // 界面先拦一次（体验），Repository 里还会再验一次（约束）
        if (ItemAttributes.parseExpiry(expiryInput) == ItemAttributes.Expiry.Invalid) {
            message = "有效期要写成 ${ItemAttributes.EXPIRY_PATTERN}，例如 2026-10-18"
            messageIsError = true
            return
        }
        scope.launch {
            val result = repository.editItem(
                itemId = current.id,
                name = nameInput,
                aliases = ItemAttributes.parseAliases(aliasInput),
                category = categoryInput,
                expiryDate = expiryInput,
            )
            when (result) {
                is EditResult.Saved -> {
                    item = result.item
                    editing = false
                    message = if (result.renamedFrom != null) {
                        "已改名；旧名「${result.renamedFrom}」已加入别名，以后说旧名也能找到"
                    } else {
                        "已保存"
                    }
                    messageIsError = false
                }

                is EditResult.Unchanged -> {
                    message = "没有改动"
                    messageIsError = false
                    editing = false
                }

                is EditResult.Rejected -> {
                    message = result.reason
                    messageIsError = true
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移入回收站？") },
            text = { Text("物品和当前在架位置会暂时隐藏，可从历史页的回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.softDeleteItem(itemId)
                        onBack()
                    }
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name ?: "物品") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = item
            if (!loaded) return@Column
            if (current == null) {
                Text(
                    text = "物品不存在或已删除",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Filled.Inventory2, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.name, style = MaterialTheme.typography.headlineSmall)
                        Text(ItemCategory.of(current.name, current.category) + " · " +
                            if (locations.isEmpty()) "当前无在架物品" else "${locations.size} 个存放位置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            DetailCard(title = "存放位置") {
                if (locations.isEmpty()) {
                    Text("当前不在任何位置", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp))
                } else {
                    locations.forEach { record ->
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Place, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp))
                                Text(record.locationCode, style = MaterialTheme.typography.titleMedium)
                            }
                            Text("× ${LookupText.quantity(record.quantity)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text("位置变化请在首页用语音记录，方便保留操作历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
            }

            DetailCard(title = "物品资料") {
                if (!editing) {
                    AttributeLine("别名", ItemAttributes.decodeAliases(current.aliases)
                        .joinToString("、").ifBlank { "未设置" })
                    AttributeLine("类别", ItemCategory.of(current.name, current.category))
                    AttributeLine("有效期", current.expiryDate ?: "未设置")
                    TextButton(onClick = { editing = true; message = null },
                        modifier = Modifier.padding(top = 4.dp)) {
                        Text("编辑资料")
                    }
                } else {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    label = { Text("别名") },
                    supportingText = {
                        Text("多个别名用逗号分开")
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = categoryInput,
                    onValueChange = { categoryInput = it },
                    label = { Text("类别") },
                    supportingText = { Text("留空时按名称自动归类；可填写自定义类别") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = expiryInput,
                    onValueChange = { expiryInput = it },
                    label = { Text("有效期") },
                    placeholder = { Text("yyyy-MM-dd，留空 = 不设") },
                    isError = ItemAttributes.parseExpiry(expiryInput) == ItemAttributes.Expiry.Invalid,
                    supportingText = {
                        Text(
                            if (ItemAttributes.parseExpiry(expiryInput) == ItemAttributes.Expiry.Invalid) {
                                "日期不合法，不会保存"
                            } else {
                                "格式 ${ItemAttributes.EXPIRY_PATTERN}，例如 2026-10-18"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                Button(
                    onClick = { save() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text("保存")
                }
                TextButton(onClick = { editing = false; message = null },
                    modifier = Modifier.fillMaxWidth()) { Text("取消编辑") }
                }

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            DetailCard(title = "补货提醒") {
                if (!editingRestock) {
                    val threshold = current.restockThreshold
                    Text(
                        when {
                            threshold == null -> "已关闭"
                            threshold == 0.0 -> "只在用完时提醒"
                            else -> "剩余 ${LookupText.quantity(threshold)} 个时提醒"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    val total = locations.sumOf { it.quantity }
                    if (threshold != null) {
                        Text(
                            when {
                                total <= 0.0 -> "已用完"
                                threshold == 0.0 -> "现存 ${LookupText.quantity(total)} 个；取光时提醒"
                                threshold > 0.0 && total <= threshold ->
                                    "现存 ${LookupText.quantity(total)} 个，已达到提醒数量 ${LookupText.quantity(threshold)} 个"
                                else -> "现存 ${LookupText.quantity(total)} 个，尚未达到提醒数量"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = {
                        restockMode = RestockMode.fromThreshold(current.restockThreshold)
                        restockInput = current.restockThreshold?.takeIf { it > 0.0 }
                            ?.let(LookupText::quantity).orEmpty()
                        editingRestock = true
                        restockMessage = null
                    }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("编辑补货提醒")
                    }
                } else {
                    RestockMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { restockMode = mode },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = restockMode == mode,
                                onClick = { restockMode = mode })
                            Text(mode.label)
                        }
                    }
                    if (restockMode == RestockMode.THRESHOLD) {
                        OutlinedTextField(
                            value = restockInput,
                            onValueChange = { restockInput = it },
                            label = { Text("提醒数量") },
                            supportingText = { Text("填写大于 0 的数量，可使用小数") },
                            isError = restockInput.isNotBlank() &&
                                parsePositiveRestockThreshold(restockInput) == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().testTag("restock-threshold-input"),
                        )
                    }
                    Button(onClick = { saveRestock() }, modifier = Modifier.fillMaxWidth()) {
                        Text("保存补货提醒")
                    }
                    TextButton(onClick = { editingRestock = false; restockMessage = null },
                        modifier = Modifier.fillMaxWidth()) { Text("取消补货设置") }
                }
                restockMessage?.let {
                    Text(it,
                        color = if (restockMessageIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            DetailCard(title = "使用记录") {
                if (operations.isEmpty()) {
                    Text("暂无取放记录", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp))
                } else {
                    operations.forEach { operation ->
                        ItemOperationRow(operation)
                    }
                }
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("移入回收站")
            }
        }
    }
}

private enum class RestockMode(val label: String) {
    OFF("关闭"), ZERO_ONLY("只在用完时提醒"), THRESHOLD("剩余指定数量时提醒");

    companion object {
        fun fromThreshold(value: Double?): RestockMode = when {
            value == null -> OFF
            value == 0.0 -> ZERO_ONLY
            else -> THRESHOLD
        }
    }
}

internal fun parsePositiveRestockThreshold(input: String): Double? =
    input.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun ItemOperationRow(operation: OperationEntity) {
    val revoked = operation.revokedAt != null
    val accent = colorFor(when {
        revoked -> SemanticAction.Revoked
        operation.type == OperationType.PUT -> SemanticAction.Put
        else -> SemanticAction.Take
    })
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (operation.type == OperationType.PUT) Icons.Filled.ArrowDownward
            else Icons.Filled.ArrowUpward,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                operationAction(operation) + if (revoked) " · 已撤销" else "",
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
            Text(operationLocation(operation), style = MaterialTheme.typography.bodyMedium)
            Text(operationTime(operation.createdAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttributeLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp))
    }
}
