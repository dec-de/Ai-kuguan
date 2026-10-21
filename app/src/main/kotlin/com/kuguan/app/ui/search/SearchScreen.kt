package com.kuguan.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kuguan.app.data.db.dao.ItemWithLocations
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.domain.item.ItemCategory
import com.kuguan.app.domain.query.LookupText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 搜索页 —— 语音之外的**唯一**录入/查询通道，也是无网时唯一可用的路径（功能表原则 4）。
 *
 * 因此这个页面在无网时必须完全可用：它只读本地 Room，不碰任何网络接口。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun SearchScreen(
    repository: StorageRepository,
    bottomPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    var keywordInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val keywordFlow = remember { MutableStateFlow("") }

    // 用 flatMapLatest：关键词变化时自动取消上一次的 Room 查询并重新订阅。
    //
    // 查询本身是**一条 JOIN SQL**（见 ItemDao.searchWithLocations），
    // 位置随物品一起回来 —— 不再对每个结果各查一次位置（N+1）。
    //
    // 刻意**不用** remember(keywordFlow) 缓存这条 Flow：
    // keywordFlow 由 remember 创建，是恒定的引用，remember 的 key 永远命中，
    // 写上去看起来像缓存、实际等价于 remember { ... }，
    // 会让后来者误以为「换个 key 就能让查询重新订阅」。
    // flatMapLatest 本身就订阅 StateFlow，不需要额外 remember。
    val results by remember {
        keywordFlow.flatMapLatest { keyword ->
            if (keyword.isBlank()) repository.browseWithLocations()
            else repository.searchWithLocations(keyword)
        }
    }.collectAsState(initial = emptyList())
    val categories = remember(results) {
        ItemCategory.counts(results.map { it.name to it.category }).toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }
    val visible = if (keywordInput.isNotBlank() || selectedCategory == null) results
        else results.filter { ItemCategory.of(it.name, it.category) == selectedCategory }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("查找物品") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )

            OutlinedTextField(
                value = keywordInput,
                onValueChange = {
                    keywordInput = it
                    keywordFlow.value = it.trim()
                    if (it.isNotBlank()) selectedCategory = null
                },
                label = { Text("物品名或别名") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = bottomPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (keywordInput.isBlank() && selectedCategory == null && categories.isNotEmpty()) {
                        item { SearchHeading("按类别查看", "${categories.size} 个分类") }
                        items(categories.chunked(2)) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { (name, count) ->
                                    CategoryCard(name, count, Modifier.weight(1f)) { selectedCategory = name }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (selectedCategory != null && keywordInput.isBlank()) {
                        item {
                            TextButton(onClick = { selectedCategory = null }) {
                                Text("全部分类  /  ${selectedCategory.orEmpty()}")
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item { BoxedHint(if (keywordInput.isBlank()) "输入关键词开始搜索" else "没有匹配的物品") }
                    } else {
                        item { SearchHeading(if (keywordInput.isBlank()) "全部物品" else "搜索结果", "${visible.size} 件") }
                    }
                    items(visible, key = { it.id }) { item ->
                        ItemRow(item = item, onClick = { onOpenItem(item.id) })
                    }
                }
        }
    }
}

@Composable
private fun SearchHeading(title: String, count: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(count, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryCard(name: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(categoryIcon(name), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text("$count 件物品", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun categoryIcon(name: String): ImageVector = when (name) {
    "工具五金" -> Icons.Filled.Build
    "电子数码" -> Icons.Filled.Devices
    "厨房餐具" -> Icons.Filled.Kitchen
    "衣物配饰" -> Icons.Filled.Checkroom
    "清洁护理" -> Icons.Filled.CleaningServices
    "文具资料" -> Icons.AutoMirrored.Filled.MenuBook
    "药品健康" -> Icons.Filled.MedicalServices
    "户外运动" -> Icons.Filled.SportsSoccer
    "其他" -> Icons.Filled.Inventory2
    else -> Icons.Filled.Category
}

@Composable
private fun BoxedHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ItemRow(item: ItemWithLocations, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(ItemCategory.of(item.name, item.category)), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
                Text(
                    text = ItemCategory.of(item.name, item.category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Text(
                // 门禁：结果行必须有「编号 · 数量」，多位置时**全部**列出。
                // 一个物品横跨 A-07 / B-05 时只显示一个位置，等于告诉用户
                // 「别的地方没有了」—— 这是搜索页最不该撒的谎。
                text = if (item.locations.isEmpty()) {
                    "当前不在任何位置"
                } else {
                    buildString {
                        append(
                            item.locations.joinToString(" · ") {
                                "${it.locationCode} ×${LookupText.quantity(it.quantity)}"
                            }
                        )
                        if (item.locations.size > 1) {
                            val total = item.locations.sumOf { it.quantity }
                            append("（共 ${LookupText.quantity(total)} 个）")
                        }
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.locations.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            }
        }
    }
}
