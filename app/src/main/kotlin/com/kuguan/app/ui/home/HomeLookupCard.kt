package com.kuguan.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LookupResult
import com.kuguan.app.domain.query.LookupText

/** 语音反查专用结果卡；多位置逐行排版，不限制文本行数。 */
@Composable
internal fun HomeLookupCard(
    result: LookupResult,
    enabledIds: Set<String>? = null,
    stale: Boolean = false,
    onOpenItem: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("位置查询", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            if (stale) Text("库存可能已变化，请重新查询。失效的详情入口已关闭。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            when (result) {
                is LookupResult.Matches -> {
                    Text("找到 ${result.items.size} 件相关物品",
                        style = MaterialTheme.typography.headlineSmall)
                    result.items.forEach { item ->
                        LookupItemContent(item, onOpenItem, enabledIds)
                    }
                }
                is LookupResult.Found -> {
                    LookupItemContent(result.item, onOpenItem, enabledIds)
                }
                is LookupResult.NoLocations -> {
                    Text(result.item.name, style = MaterialTheme.typography.headlineSmall)
                    Text("当前没有存放位置，可能已经全部取出。",
                        style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onOpenItem(result.item.id) },
                        enabled = enabledIds == null || result.item.id in enabledIds) {
                        Text("查看「${result.item.name}」详情")
                    }
                }
                is LookupResult.NotFound -> {
                    Text("没有找到「${result.query}」",
                        style = MaterialTheme.typography.headlineSmall)
                    if (result.suggestions.isNotEmpty()) {
                        Text("你可能想找：", style = MaterialTheme.typography.bodyMedium)
                        result.suggestions.forEach { suggestion ->
                            TextButton(onClick = { onOpenItem(suggestion.id) },
                                enabled = enabledIds == null || suggestion.id in enabledIds) {
                                Text(suggestion.name)
                            }
                        }
                    } else {
                        Text("库里还没有这件物品。", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                LookupResult.NoName -> {
                    Text("要查什么物品？", style = MaterialTheme.typography.headlineSmall)
                    Text("请再说一次物品名称。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun LookupItemContent(item: ItemSnapshot, onOpenItem: (String) -> Unit, enabledIds: Set<String>?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.name, style = MaterialTheme.typography.headlineSmall)
        if (item.locations.isEmpty()) {
            Text("当前没有存放位置，可能已经全部取出。",
                style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("找到 ${item.locations.size} 处 · 合计 ${LookupText.quantity(item.total)} 个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.locations.forEach { location ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Place, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("存放位置", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(location.locationCode, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("${LookupText.quantity(location.quantity)} 个",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        TextButton(onClick = { onOpenItem(item.id) }, enabled = enabledIds == null || item.id in enabledIds) {
            Text("查看「${item.name}」详情")
        }
    }
}
