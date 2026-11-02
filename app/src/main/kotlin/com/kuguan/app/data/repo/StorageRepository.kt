package com.kuguan.app.data.repo

import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.LocationRecordEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.data.db.dao.ItemDao
import com.kuguan.app.data.db.dao.ItemWithLocations
import com.kuguan.app.data.db.dao.LocationDao
import com.kuguan.app.data.db.dao.LocationSummary
import com.kuguan.app.data.db.dao.HistoryOperationRow
import com.kuguan.app.data.db.dao.OperationDao
import com.kuguan.app.domain.item.ItemAttributes
import com.kuguan.app.domain.normalize.LocationCodeNormalizer
import com.kuguan.app.domain.query.ItemBrief
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LocationQty
import com.kuguan.app.domain.query.LookupResult
import com.kuguan.app.domain.voice.IntentPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 单条操作允许的最大数量。
 *
 * 输入框的 1~99（`QuantityInputRule`）只管手输；语音 / LLM 路径此前**完全没有上限**，
 * 而批量首录天然会出现「200 颗螺丝」。超限不静默截断 —— 那等于替用户猜了一个数字 ——
 * 而是判为非法，整批拒绝。
 *
 * 输入框仍是 99：那是手输场景，且它的截断发生在**可见的输入框里**，
 * 用户按确认前一定看得到自己输了什么，不构成静默改写。
 */
const val MAX_QUANTITY = 999.0

/**
 * 事务边界。
 *
 * 批量写入与整批撤销都必须是原子的 —— 「半批」比「全不写」更糟：
 * 用户以为记全了，实际缺了两条，而核对屏的条数又对不上，他没有理由怀疑。
 *
 * 真实实现走 Room 的 `withTransaction`；单测直接执行（fake DAO 本来就没有回滚语义）。
 * 抽成接口而不是让 Repository 持有 RoomDatabase，是为了让 Repository 继续
 * 只依赖三个 DAO —— 它不该知道 SQLite 的存在。
 */
fun interface TransactionRunner {
    suspend fun runInTransaction(block: suspend () -> Unit)
}

/** 把改写失败从 Room 事务内抛出，确保已撤销的旧操作一起回滚。 */
private class RestateRejected : RuntimeException()

/**
 * 撤销的许可范围 —— 决定「这一条能不能撤」。
 *
 * 原来是 `requireLatest: Boolean`。批量录入让它不够用：那个布尔只能表达
 * 「必须是全局最新」与「随你便」两档，而批量需要第三种 ——
 * **同批内的兄弟可以撤，批外的不行**。再加一个布尔就变四态、其中一态非法。
 */
enum class RevokeScope {
    /**
     * 只允许撤**全局最新**一条未撤销操作。
     *
     * 核对屏「撤销刚才这条」的现状：屏幕上显示的就是刚写进去的那条；
     * 若期间又写入了别的，撤这条会撤错，而界面仍显示「这条已撤销」——
     * 界面与数据不一致是最坏的一类错误，且无人报错。
     */
    LatestOnly,

    /**
     * 允许撤**同一批内**的任意一条，不要求是全局最新。
     *
     * 安全性来自「同批同类型的行是兄弟，不是前提」：同批的 PUT 是纯累加
     * （放 5 又放 3 得 8，撤「放 5」剩 3），撤销可交换，先撤哪条都对。
     * 但**批外的后续操作**会拦下它 —— 尤其是 TAKE，它已经消耗了库存，
     * 撤掉来源会让账目失真（放 5 → 取 2 → 撤「放 5」会把库存算到 −2 再 clamp 成 0）。
     */
    BatchSibling,

}

/**
 * 批量录入里的一行。
 *
 * 与 [StorageRepository.put] 的参数一一对应，只是去掉了 ASR / LLM 原文 ——
 * 那些是**整批共享**的（一句话只有一句原文），放在批上而不是行上。
 */
data class BatchRow(
    val itemName: String,
    val locationCode: String,
    val quantity: Double = 1.0,
    val aliases: List<String> = emptyList(),
    val category: String? = null,
    val expiryDate: String? = null,
) {
    /**
     * 这一行能不能进库。
     *
     * 校验放在**写库之前**而不是写库中间：整批拒绝比写一半再回滚更好解释，
     * 也避免了「事务里抛异常」这种把控制流交给异常的做法。
     */
    val isValid: Boolean
        get() = itemName.isNotBlank() &&
            LocationCodeNormalizer.looksLikeLocationCode(locationCode) &&
            quantity > 0.0 &&
            quantity <= MAX_QUANTITY
}

/**
 * 唯一写库入口。UI / ViewModel 一律经由这里落库，不直接调 DAO。
 *
 * 两条不可动摇的规则在这里落地：
 * 1. **零确认** —— 写库发生在语音解析完成的瞬间，不等核对屏。核对屏纯展示。
 * 2. **位置级数量** —— [put] 里的 quantity 是「该位置的数量」，不是物品总量。
 */
class StorageRepository(
    private val itemDao: ItemDao,
    private val locationDao: LocationDao,
    private val operationDao: OperationDao,
    private val transactionRunner: TransactionRunner = TransactionRunner { block -> block() },
    private val stockStateRefresh: suspend () -> Unit = {},
) {

    // ---------- 读 ----------

    fun observeItemCount(): Flow<Int> = itemDao.observeLiveCount()

    fun observeAllItems(): Flow<List<ItemEntity>> = itemDao.observeAllLive()

    fun observeLocationsOf(itemId: String): Flow<List<LocationRecordEntity>> =
        locationDao.observeByItem(itemId)

    /** 单个 SKU 的完整操作流水；DAO 按 seq 倒序，撤销记录仍可追溯。 */
    fun observeOperationsOf(itemId: String): Flow<List<OperationEntity>> =
        operationDao.observeByItem(itemId, Int.MAX_VALUE)

    fun observeRecentOperations(limit: Int = 200): Flow<List<OperationEntity>> =
        operationDao.observeRecent(limit)

    fun observeHistory(limit: Int = 200): Flow<List<HistoryOperationRow>> =
        operationDao.observeRecentWithNames(limit)

    fun observeDeletedItems(): Flow<List<ItemEntity>> = itemDao.observeDeleted()

    suspend fun findItem(id: String): ItemEntity? = itemDao.findLiveById(id)

    /**
     * 按 id 取物品，**包含已进回收站的**。
     *
     * 纠错/删除的目标校验要回答「第 103 条到底是哪件东西」，
     * 而那条操作对应的物品可能已经因为撤销而进了回收站 —— 用 `findLiveById` 会查不到，
     * 校验就会把一条真实存在的记录误判成「找不到」。
     */
    suspend fun fetchItem(id: String): ItemEntity? = itemDao.findById(id)

    /** 最近 N 条**未撤销**操作（prompt 的纠错上下文，seq 降序）。 */
    suspend fun recentActiveOperations(limit: Int = 3): List<OperationEntity> =
        operationDao.findRecentActive(limit)

    /** 全部在用档案及当前位置；时间来自未撤销的 PUT 流水，未知时保留 null。 */
    suspend fun inventoryForPrompt(): List<IntentPrompt.InventoryItem> {
        val putTimes = operationDao.latestActivePutTimes()
            .associateBy { it.itemId to it.locationCode }
        return browseWithLocations().first().map { item ->
            IntentPrompt.InventoryItem(
                name = item.name,
                aliases = ItemAttributes.decodeAliases(item.aliases),
                category = item.category,
                locations = item.locations.map { location ->
                    IntentPrompt.InventoryLocation(
                        code = location.locationCode,
                        quantity = location.quantity,
                        lastPutAtMs = putTimes[item.id to location.locationCode]?.lastPutAt,
                    )
                },
            )
        }
    }

    /** 按 seq 取操作。纠错/删除的二次校验入口。 */
    suspend fun findOperation(seq: Long): OperationEntity? = operationDao.findBySeq(seq)

    /** 最近一条未撤销操作（「删掉上一条」在 LLM 没给 targetSeq 时的兜底）。 */
    suspend fun latestActiveOperation(): OperationEntity? = operationDao.findLatestActive()

    /**
     * 按名字找活物品，走「精确名 → 数字形态变体 → 别名」——
     * 与 `put` 的 `resolveItem` 同一套匹配，避免「七号电池」查不到「7号电池」。
     */
    suspend fun findLiveItem(name: String): ItemEntity? = findExistingItem(name)

    /** 某物品当前在架的全部位置（query 意图用）。 */
    suspend fun liveLocations(itemId: String): List<LocationRecordEntity> =
        locationDao.findLiveByItem(itemId)

    /**
     * 「X 在哪」的完整回答（M3）—— 语音查询与搜索页共享同一套数据来源。
     *
     * 四种结果分别对应四种真实处境：
     * - [LookupResult.Found]：有档案、有位置 → 一次报全（不是只报第一个）；
     * - [LookupResult.NoLocations]：有档案、位置全空 → 说「取走了」，不说「没有这个物品」；
     * - [LookupResult.NotFound]：真没这个档案 → 给最多 3 条近似名，让用户点；
     * - [LookupResult.NoName]：连名字都没给 → 请用户再说一次。
     *
     * **只读**：不写库、不生成 ConfirmRow。查询永远不该看起来像「记了一条」。
     */
    suspend fun lookupItem(rawName: String): LookupResult {
        val name = rawName.trim()
        if (name.isEmpty()) return LookupResult.NoName

        val item = findExistingItem(name)
        if (item != null) {
            val records = locationDao.findLiveByItem(item.id)
            val snapshot = item.toSnapshot(records)
            return if (records.isEmpty()) {
                LookupResult.NoLocations(snapshot)
            } else {
                LookupResult.Found(snapshot)
            }
        }

        // 单字不做近似：`LIKE '%螺%'` 会把半库东西端出来，那不叫「最接近」
        val similar = if (name.length >= 2) {
            itemDao.findSimilarLive(name, SUGGESTION_LIMIT)
        } else {
            emptyList()
        }
        return LookupResult.NotFound(
            query = name,
            suggestions = similar.map { ItemBrief(it.id, it.name) },
        )
    }

    /** 语音查询可包含多个模型候选；只返回数据库中真实存在的活档案。 */
    suspend fun lookupCandidates(rawNames: List<String>): LookupResult {
        val names = rawNames.map(String::trim).filter(String::isNotEmpty).distinct()
        if (names.isEmpty()) return LookupResult.NoName

        val all = browseWithLocations().first()
        val selected = linkedMapOf<String, ItemWithLocations>()
        for (name in names) {
            val variants = ItemAttributes.variants(name)
            all.filter { item ->
                val aliases = ItemAttributes.decodeAliases(item.aliases)
                variants.any { it == item.name || it in aliases }
            }.forEach { selected.putIfAbsent(it.id, it) }
            // 泛称「电池」可以命中「7号电池」；具体「7号电池」不会反向命中「电池」。
            if (name.length >= 2) {
                all.filter { item ->
                    val aliases = ItemAttributes.decodeAliases(item.aliases)
                    variants.any { variant ->
                        item.name.contains(variant) || aliases.any { it.contains(variant) }
                    }
                }.forEach { selected.putIfAbsent(it.id, it) }
            }
        }

        val snapshots = selected.values.map { item ->
            ItemSnapshot(item.id, item.name, item.category,
                item.locations.map { LocationQty(it.locationCode, it.quantity) })
        }
        return when (snapshots.size) {
            0 -> lookupItem(names.first())
            1 -> snapshots.single().let {
                if (it.locations.isEmpty()) LookupResult.NoLocations(it) else LookupResult.Found(it)
            }
            else -> LookupResult.Matches(snapshots)
        }
    }

    /**
     * 搜索页的数据源：一条 SQL 连位置查完，按物品分组。
     *
     * 返回 [ItemWithLocations]（物品 + **全部**在架位置），
     * 搜索行因此可以显示「A-07 ×2 · B-05 ×1」，而不是只显示第一个位置。
     * 关键词为空时不发查询 —— 空输入不该等价于「全库列表」。
     */
    fun searchWithLocations(keyword: String): Flow<List<ItemWithLocations>> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return flowOf(emptyList())
        return queryWithLocations(trimmed)
    }

    /** 分类浏览专用：返回全部活物品及位置，不改变搜索的空关键词语义。 */
    fun browseWithLocations(): Flow<List<ItemWithLocations>> = queryWithLocations("")

    private fun queryWithLocations(trimmed: String): Flow<List<ItemWithLocations>> {
        val variants = ItemAttributes.variants(trimmed)
        return itemDao.searchWithLocations(
            keyword = trimmed,
            variantA = variants.getOrElse(1) { trimmed },
            variantB = variants.getOrElse(2) { trimmed },
        ).map { rows ->
            rows.groupBy { it.id }.map { (_, rowsOfItem) ->
                val head = rowsOfItem.first()
                ItemWithLocations(
                    id = head.id,
                    name = head.name,
                    aliases = head.aliases,
                    category = head.category,
                    expiryDate = head.expiryDate,
                    lastUsedAt = head.lastUsedAt,
                    locations = rowsOfItem.mapNotNull { row ->
                        row.locationCode?.let { LocationSummary(it, row.quantity ?: 0.0) }
                    },
                )
            }
        }
    }

    suspend fun countLiveItems(): Int = itemDao.countLive()

    // ---------- 写：放入 / 取出 ----------

    /**
     * 放入：把 [quantity] 个 [itemName] 记到 [locationCode]。
     *
     * 「零确认」的核心写路径：不弹确认、不校验编号是否已存在、不做同类归拢。
     * 同一物品同一位置重复放入是**累加**（唯一索引 upsert 后覆盖，所以先读旧值）。
     *
     * @param batchId 批量录入时传入，同批各行共用；单条录入留空。
     */
    suspend fun put(
        itemName: String,
        locationCode: String,
        quantity: Double = 1.0,
        aliases: List<String> = emptyList(),
        category: String? = null,
        expiryDate: String? = null,
        asrText: String? = null,
        rawText: String? = null,
        batchId: String? = null,
        notifyStock: Boolean = true,
    ): OperationEntity {
        val now = System.currentTimeMillis()
        val item = resolveItem(itemName, aliases, category, expiryDate, now)
        val existing = locationDao.findLive(item.id, locationCode)

        // 同一位置重复放入 → 累加数量（位置级语义：「A-07 里有 2 节」再放 1 节 = 3 节）
        val newQty = (existing?.quantity ?: 0.0) + quantity
        locationDao.upsert(
            LocationRecordEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                itemId = item.id,
                locationCode = locationCode,
                quantity = newQty,
                deletedAt = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )

        val operation = appendOperation(
            type = OperationType.PUT,
            itemId = item.id,
            locationCode = locationCode,
            quantity = quantity,
            asrText = asrText,
            rawText = rawText,
            batchId = batchId,
        )
        if (notifyStock) stockStateRefresh()
        return operation
    }

    /**
     * 取出：从 [locationCode] 取出 [quantity] 个。
     *
     * 取出后该位置数量归零 → **软删除该位置记录**（而不是存 0）。
     * 若该物品还有其他位置记录，物品档案保留 —— 这正是「同类物品散在多个抽屉完全无所谓」。
     */
    suspend fun take(
        itemName: String,
        locationCode: String,
        quantity: Double = 1.0,
        asrText: String? = null,
        rawText: String? = null,
        batchId: String? = null,
        notifyStock: Boolean = true,
    ): OperationEntity? {
        val now = System.currentTimeMillis()
        val item = itemDao.findLiveByName(itemName) ?: return null
        val existing = locationDao.findLive(item.id, locationCode) ?: return null

        val remaining = existing.quantity - quantity
        if (remaining <= 0.0) {
            // ⚠️ 软删时**不要改写 quantity**。
            // 这条被删记录里存的是「取出之前」的数量，撤销 TAKE 时要靠它还原
            // （见 [rollback]）。若在这里顺手把它改成 0，撤销后会少算；
            // 遇到「超量取出」（库存 2 却取 3）还会凭空多出库存。
            locationDao.softDelete(existing.id, now)
        } else {
            locationDao.upsert(existing.copy(quantity = remaining, updatedAt = now))
        }

        val op = appendOperation(
            type = OperationType.TAKE,
            itemId = item.id,
            locationCode = locationCode,
            quantity = quantity,
            asrText = asrText,
            rawText = rawText,
            batchId = batchId,
        )

        // last_used_at 完全由 TAKE 操作派生 —— 冷门提醒的唯一来源
        itemDao.recomputeLastUsedAt(item.id)
        if (notifyStock) stockStateRefresh()
        return op
    }

    /** 详情页取出：在同一事务内核对物品和最新库存，并保留正常取出历史。 */
    suspend fun takeFromDetail(itemId: String, locationCode: String, quantity: Double): Boolean {
        if (!quantity.isFinite() || quantity <= 0.0 || quantity > MAX_QUANTITY) return false
        var written = false
        transactionRunner.runInTransaction {
            val item = itemDao.findLiveById(itemId) ?: return@runInTransaction
            val location = locationDao.findLive(itemId, locationCode) ?: return@runInTransaction
            if (quantity > location.quantity) return@runInTransaction
            written = take(item.name, locationCode, quantity, notifyStock = false) != null
        }
        if (written) {
            try {
                stockStateRefresh()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("StorageRepository", "取出后刷新库存提醒失败", error)
            }
        }
        return written
    }

    // ---------- 写：批量放入 ----------

    /**
     * 批量放入：一句话拆出的多行，**整批一个 [batchId]**。
     *
     * ## 为什么整批必须原子
     *
     * 写一半的危害比全不写大得多：用户说了 8 样，核对屏却只有 5 行，
     * 而条数对不上这件事他自己未必记得准 —— 半批记录会长期留在库里无人发现。
     * 所以任一行非法就**一条都不写**。
     *
     * @return 写库结果（顺序与 [rows] 一致，seq 升序）；
     *         任一行非法或 [rows] 为空时返回 **null**，调用方据此把整批转红提示。
     */
    suspend fun putBatch(
        rows: List<BatchRow>,
        asrText: String? = null,
        rawText: String? = null,
    ): List<OperationEntity>? {
        if (rows.isEmpty()) return null
        // 先全量预检，再进事务：非法批次根本不该碰数据库
        if (rows.any { !it.isValid }) return null

        val batchId = UUID.randomUUID().toString()
        val written = mutableListOf<OperationEntity>()
        transactionRunner.runInTransaction {
            rows.forEach { row ->
                written += put(
                    itemName = row.itemName,
                    locationCode = row.locationCode,
                    quantity = row.quantity,
                    aliases = row.aliases,
                    category = row.category,
                    expiryDate = row.expiryDate,
                    asrText = asrText,
                    rawText = rawText,
                    batchId = batchId,
                    notifyStock = false,
                )
            }
        }
        stockStateRefresh()
        return written
    }

    // ---------- 纠错：撤销 + 重放（开发计划 5.3）----------

    /**
     * 撤销上一条未撤销操作。语义细节见 [revokeOperation]。
     *
     * 保留这个入口是为了「说一句『删掉上一条』」这类**语音纠错**（M4）——
     * 那时用户没指名哪一条，撤最新一条就是他的意思。
     * 而 UI 上的「撤销刚才这条」必须走 [revokeOperation] 指名撤销，
     * 否则撤销的未必是屏幕上显示的那条。
     */
    suspend fun revokeLatest(): OperationEntity? {
        val latest = operationDao.findLatestActive() ?: return null
        return revokeOperation(latest.seq, RevokeScope.LatestOnly)
    }

    /** 历史页专用：在同一事务里检查该物品、位置的后续依赖并撤销。 */
    suspend fun revokeFromHistory(seq: Long): HistoryRevokeResult {
        var result: HistoryRevokeResult = HistoryRevokeResult.Rejected("撤销未执行")
        transactionRunner.runInTransaction {
            val op = operationDao.findBySeq(seq)
            result = when {
                op == null -> HistoryRevokeResult.Rejected("记录不存在")
                op.revokedAt != null -> HistoryRevokeResult.Rejected("这条记录已经撤销")
                op.type !in setOf(OperationType.PUT, OperationType.TAKE) ->
                    HistoryRevokeResult.Rejected("这条是审计记录，不能作为库存操作撤销")
                else -> {
                    if (!allowsRevoke(op, RevokeScope.BatchSibling)) {
                        HistoryRevokeResult.Rejected("这件物品在该位置有后续关联操作，请先处理后续记录")
                    } else {
                        val revoked = revokeOperation(seq, RevokeScope.BatchSibling, notifyStock = false)
                        if (revoked == null) HistoryRevokeResult.Rejected("记录状态已变化，请刷新后重试")
                        else HistoryRevokeResult.Revoked(revoked)
                    }
                }
            }
        }
        if (result is HistoryRevokeResult.Revoked) stockStateRefresh()
        return result
    }

    /**
     * 撤销**指定**的那条操作。**没有时间窗口**（Owen 2026-10-17 定）：
     * 只要核对屏还显示着这条，用户就该能撤；以后在历史页里撤销也不设过期。
     *
     * ## 为什么去掉了 5 分钟窗口（推翻决策 #7）
     *
     * 那个窗口的本意是「防止撤到已被后续操作依赖的旧记录」，
     * 但代价是**用户刚发现错了却已经撤不了**。
     * 而纠错窗口存在的意义恰恰是给用户留出发现错误的时间 ——
     * 到点就把它关掉，等于把最容易出错的那一批（事后才发现）直接排除在外。
     * 「页面还在就能撤」把判断权交回给用户，比替他计时更合理。
     *
     * @param scope 允许的撤销范围，见 [RevokeScope]。默认 [RevokeScope.LatestOnly]。
     *
     * 实现要点：**不删行**，只打 [OperationEntity.revokedAt] 标记。
     * 这样「可回溯原状态」与「回收站防误删」同时满足。
     *
     * @return 被撤销的操作；不可撤（不存在 / 已撤销 / 不符合 [scope]）时返回 null。
     */
    suspend fun revokeOperation(
        seq: Long,
        scope: RevokeScope = RevokeScope.LatestOnly,
        notifyStock: Boolean = true,
    ): OperationEntity? {
        val now = System.currentTimeMillis()
        val op = operationDao.findBySeq(seq) ?: return null
        if (op.revokedAt != null) return null

        if (!allowsRevoke(op, scope)) return null

        rollback(op, now)
        // 必须在 markRevoked **之前**判定：一旦打了标记，这条就不再计入活跃操作数，
        // 「它是不是物品存在的唯一原因」也就无从判断了
        if (op.type == OperationType.PUT) dropOrphanItem(op.itemId, now)

        operationDao.markRevoked(op.seq, now)

        // 撤销 TAKE 会改变 last_used_at，必须重算
        op.itemId?.let { itemDao.recomputeLastUsedAt(it) }
        if (notifyStock) stockStateRefresh()
        return op
    }

    /**
     * 该操作在给定 [scope] 下能不能撤。
     *
     * 单独抽出来是因为 [revokeBatch] 要在**动手之前**对全批做一次同样的预检 ——
     * 两条路径必须共用同一套判定，否则迟早出现「预检过了但实际撤不掉」的偏差。
     */
    private suspend fun allowsRevoke(op: OperationEntity, scope: RevokeScope): Boolean = when (scope) {
        RevokeScope.LatestOnly -> {
            val latest = operationDao.findLatestActive() ?: return false
            latest.seq == op.seq
        }

        RevokeScope.BatchSibling -> {
            val itemId = op.itemId
            val locationCode = op.locationCode
            if (itemId == null || locationCode == null) {
                // 没有 item/位置 的操作（如 DELETE）无从判定位置冲突 —— 退回最严口径
                val latest = operationDao.findLatestActive() ?: return false
                latest.seq == op.seq
            } else {
                operationDao.countConflictingLaterOps(
                    seq = op.seq,
                    itemId = itemId,
                    locationCode = locationCode,
                    batchId = op.batchId,
                    type = op.type,
                ) == 0
            }
        }

    }

    /** Read-only guard for restored home cards; mirrors the edit/delete safety gate. */
    suspend fun canRevokeForHome(seq: Long): Boolean {
        val op = operationDao.findBySeq(seq) ?: return false
        if (op.revokedAt != null || op.type !in setOf("PUT", "TAKE")) return false
        return allowsRevoke(op, RevokeScope.BatchSibling)
    }

    /**
     * 撤销一整批。**从最新往回逐条撤**，整体在同一个事务里。
     *
     * ## 为什么能整批撤而单条撤要挑范围
     *
     * 从 seq 高到低撤时，每条在被撤的那一刻都是该位置的「最新活跃操作」——
     * 自洽性天然成立，不需要额外条件。唯一要挡的是**批外的后续操作**
     * （典型的后续 TAKE 已经消耗了库存），那由 [RevokeScope.BatchSibling] 拦下。
     *
     * ## 为什么先全量预检再动手
     *
     * 半批撤销是全 App 最难解释的状态：界面上一部分灰一部分绿，
     * 用户不知道到底撤了没撤。宁可一条都不撤，也要给一个确定的结果。
     *
     * @return 被撤销的操作（seq 降序）；批不存在、为空、或任一条撤不掉时返回 null。
     */
    suspend fun revokeBatch(batchId: String): List<OperationEntity>? {
        val pending = operationDao.findActiveByBatch(batchId)
        if (pending.isEmpty()) return null

        // 预检：任一条不符合 BatchSibling 就整批不动
        for (op in pending) {
            if (!allowsRevoke(op, RevokeScope.BatchSibling)) return null
        }

        val revoked = mutableListOf<OperationEntity>()
        transactionRunner.runInTransaction {
            for (op in pending) {
                val done = revokeOperation(op.seq, RevokeScope.BatchSibling, notifyStock = false)
                    // 预检已过，这里不该返回 null。真返回了说明预检与实际判定不一致，
                    // 抛出去让事务回滚，胜过静默地撤掉一半。
                    ?: error("预检通过却撤销失败：seq=${op.seq}")
                revoked += done
            }
        }
        stockStateRefresh()
        return revoked
    }

    /**
     * 改一行：撤销 [seq] 那条，用新值重写一条。**新行继承原批次号。**
     *
     * 这是批量核对屏「快速修改」的统一实现，沿用开发计划 5.3 的「撤销 + 重放」：
     * 不 UPDATE 任何历史行，旧行只打 `revoked_at`。append-only、
     * `last_used_at` 派生、可回溯三条原则全部保住，历史页也能看出这一行改过。
     *
     * **改物品名也走这里**：撤销后旧物品若只因这条操作才存在，
     * 会被 [dropOrphanItem] 自动回收，不需要额外的「删除物品」逻辑。
     *
     * 继承 `batch_id` 是必须的 —— 否则新行会从批次里掉出去，
     * 后续再撤批内其它行时会被安全闸门误判成「外来操作」而拒绝。
     *
     * @return 新写入的操作；原操作不存在、入参非法、安全闸门拒绝或新 TAKE 无目标时返回 null。
     *         新 TAKE 失败发生在旧撤销之后，必须靠 Room 事务回滚，不能提交半次改写。
     */
    suspend fun restateRow(
        seq: Long,
        itemName: String,
        locationCode: String,
        quantity: Double,
    ): OperationEntity? {
        val original = operationDao.findBySeq(seq) ?: return null
        if (original.revokedAt != null) return null
        if (original.type != OperationType.PUT && original.type != OperationType.TAKE) return null

        // 校验一律在做撤销之前 —— 否则「改到一个非法值」会把原记录也一起弄丢
        if (itemName.isBlank()) return null
        if (!LocationCodeNormalizer.looksLikeLocationCode(locationCode)) return null
        if (quantity <= 0.0 || quantity > MAX_QUANTITY) return null
        if (!allowsRevoke(original, RevokeScope.BatchSibling)) return null

        return try {
            var written: OperationEntity? = null
            transactionRunner.runInTransaction {
                revokeOperation(seq, RevokeScope.BatchSibling, notifyStock = false) ?: throw RestateRejected()
                written = when (original.type) {
                    OperationType.PUT -> put(
                        itemName = itemName,
                        locationCode = locationCode,
                        quantity = quantity,
                        asrText = original.asrText,
                        rawText = original.rawText,
                        batchId = original.batchId,
                        notifyStock = false,
                    )

                    OperationType.TAKE -> take(
                        itemName = itemName,
                        locationCode = locationCode,
                        quantity = quantity,
                        asrText = original.asrText,
                        rawText = original.rawText,
                        batchId = original.batchId,
                        notifyStock = false,
                    )

                    else -> null // 已在事务前拦截；保留穷举防止后续类型扩展误写
                }
                // 事务内的普通 return 会提交此前的撤销；必须抛出才能整体回滚。
                if (written == null) throw RestateRejected()
            }
            stockStateRefresh()
            written
        } catch (_: RestateRejected) {
            null
        }
    }

    /**
     * 撤销某条操作的数据影响。**逆向**处理：
     * - PUT → 回退该位置的数量（减去当时的 quantity）
     * - TAKE → 把数量加回去（**含已取光被软删的那条**）
     */
    private suspend fun rollback(op: OperationEntity, now: Long) {
        val itemId = op.itemId ?: return
        val locationCode = op.locationCode ?: return
        val qty = op.quantity ?: return

        // ⚠️ 用 findAnyAtLocation 而不是 findLive：取出取光时记录已被软删，
        // findLive 查不到会让回滚静默失效（数量没变，撤销标记却打上了）。
        val record = locationDao.findAnyAtLocation(itemId, locationCode) ?: return
        when (op.type) {
            OperationType.PUT -> {
                val restored = record.quantity - qty
                if (restored <= 0.0) {
                    locationDao.softDelete(record.id, now)
                } else {
                    locationDao.upsert(record.copy(quantity = restored, updatedAt = now))
                }
            }
            OperationType.TAKE -> {
                if (record.deletedAt == null) {
                    // 记录还在架 → 它存的是「取出之后」的数量，加回去即可
                    locationDao.upsert(
                        record.copy(quantity = record.quantity + qty, updatedAt = now)
                    )
                } else {
                    // 记录已被取光软删 → 它存的是「取出之前」的数量（take 软删时不改写），
                    // 所以只恢复在架状态，**不能再加一次**，否则数量翻倍。
                    // 这一分支正是回滚「取光」能成立的原因。
                    locationDao.upsert(record.copy(deletedAt = null, updatedAt = now))
                }
            }
        }
    }

    /**
     * 撤销一条放入后，若该物品**只因为这条操作才存在**，把它收回回收站。
     *
     * 两个条件必须同时满足才算「孤儿」：
     * 1. 除这条之外没有任何别的未撤销操作 —— 说明它是被这次录入凭空建出来的
     * 2. 撤销后在架位置数为 0 —— 说明它现在哪儿都不在
     *
     * 只满足 2 就删是**错的**：「取光后保留物品档案」是既有语义
     * （物品的历史与有效期仍有价值，见 `take` 的注释），
     * 那种情况下物品虽然没有在架位置，但有多条历史操作，必须留着。
     *
     * 进的是回收站（软删）而不是物理删除 —— 撤销本身也可能是误操作。
     */
    private suspend fun dropOrphanItem(itemId: String?, now: Long) {
        val id = itemId ?: return
        val item = itemDao.findLiveById(id) ?: return
        if (operationDao.countActiveForItem(id) != 1) return
        if (locationDao.countLiveForItem(id) > 0) return
        itemDao.update(item.copy(deletedAt = now, updatedAt = now))
    }

    /**
     * 软删除物品（进回收站）。
     * 位置记录一并软删，但**操作历史保留** —— 否则 last_used_at 会丢，冷门提醒失效。
     */
    suspend fun softDeleteItem(itemId: String) {
        transactionRunner.runInTransaction {
            val item = itemDao.findLiveById(itemId) ?: return@runInTransaction
            val now = System.currentTimeMillis()
            val deletedAt = maxOf(now, (locationDao.maxDeletedAtForItem(itemId) ?: 0L) + 1L)
            itemDao.update(item.copy(deletedAt = deletedAt, updatedAt = now))
            locationDao.softDeleteByItem(itemId, deletedAt)
        }
        stockStateRefresh()
    }

    /** 从回收站恢复。 */
    suspend fun restoreItem(itemId: String): RestoreResult {
        var result: RestoreResult = RestoreResult.Rejected("物品不存在")
        transactionRunner.runInTransaction {
            val item = itemDao.findById(itemId) ?: return@runInTransaction
            val deletedAt = item.deletedAt ?: run {
                result = RestoreResult.Rejected("物品已经恢复")
                return@runInTransaction
            }
            val conflict = itemDao.findLiveByName(item.name)
            if (conflict != null && conflict.id != itemId) {
                result = RestoreResult.Rejected("已有同名物品「${item.name}」，请先改名再恢复")
                return@runInTransaction
            }
            val now = System.currentTimeMillis()
            itemDao.update(item.copy(deletedAt = null, updatedAt = now))
            locationDao.restoreByItem(itemId, deletedAt, now)
            result = RestoreResult.Restored
        }
        if (result is RestoreResult.Restored) stockStateRefresh()
        return result
    }

    // ---------- 写：属性修改（M3，详情页唯一写入口）----------

    /**
     * 修改物品属性：名称 / 别名 / 类别 / 有效期。
     *
     * ## 改名去重（M3 门禁）
     *
     * 选**拒绝并说明**（方案 a），理由见报告 §4：
     * 合并要动位置记录、软删档案、还要处理「合并后旧操作指向谁」，
     * 单人应用里误合并的代价（两件东西的位置被并到一起）远高于「改个名字被拒」。
     *
     * 冲突判定走 [findExistingItem]，也就是和语音录入**同一套匹配**：
     * 新名字命中另一个活档案的**名称或别名**都算冲突 ——
     * 否则改完之后用户说新名字，`resolveItem` 会解析到另一个档案，
     * 详情页显示的和语音写入的不是同一个东西。
     *
     * ## 旧名不丢
     *
     * 改名成功时把**旧名称**追加进 `aliases`。否则用户以后说旧名字，
     * 库里再也找不到这个物品 —— 改名从「换个称呼」变成了「换个东西」。
     *
     * ## 不碰的字段
     *
     * - `last_used_at`：完全由 operations 派生，任何手工赋值都是数据污染；
     * - 位置记录：详情页**不允许**改位置（计划 7.2），这里连查询都不做。
     *
     * ## 可追溯
     *
     * 追加一条 [OperationType.EDIT]，`quantity` / `location_code` 为 null，
     * 变更明细写在 `raw_text`。它不参与任何库存计算（见 OperationDao 的过滤条件）。
     *
     * @return [EditResult.Rejected] 时**没有任何副作用**（校验全部在写库之前）
     */
    suspend fun editItem(
        itemId: String,
        name: String,
        aliases: List<String>,
        category: String?,
        expiryDate: String?,
    ): EditResult {
        val now = System.currentTimeMillis()
        val current = itemDao.findLiveById(itemId)
            ?: return EditResult.Rejected("物品不存在或已进回收站")

        val newName = name.trim()
        if (newName.isEmpty()) return EditResult.Rejected("名称不能为空")

        // 有效期在这一层**再验一次**：UI 校验是体验，数据层校验才是约束
        val expiry = when (val parsed = ItemAttributes.parseExpiry(expiryDate)) {
            is ItemAttributes.Expiry.Valid -> parsed.value
            ItemAttributes.Expiry.Invalid ->
                return EditResult.Rejected("有效期要写成 ${ItemAttributes.EXPIRY_PATTERN}，例如 2026-10-18")
        }

        val conflict = findExistingItem(newName)
        if (conflict != null && conflict.id != itemId) {
            return EditResult.Rejected("已有同名物品「${conflict.name}」，请先去重")
        }

        val renamedFrom = current.name.takeIf { it != newName }
        val cleanedAliases = ItemAttributes.normalizeAliases(aliases, newName)
        // 旧名进别名：保证「改名之后说旧名」仍然找得到
        val mergedAliases = (cleanedAliases + listOfNotNull(renamedFrom)).distinct()
        for (alias in mergedAliases) {
            val aliasConflict = findExistingItem(alias)
            if (aliasConflict != null && aliasConflict.id != itemId) {
                return EditResult.Rejected("别名「$alias」已被「${aliasConflict.name}」使用")
            }
        }
        val newCategory = category?.trim()?.takeIf { it.isNotEmpty() }

        val updated = current.copy(
            name = newName,
            aliases = ItemAttributes.encodeAliases(mergedAliases),
            category = newCategory,
            expiryDate = expiry,
            updatedAt = now,
            // lastUsedAt 原样带过来（copy 不写它），这是刻意的
        )

        val description = describeEdit(current, updated)
            ?: return EditResult.Unchanged(current)

        var operation: OperationEntity? = null
        transactionRunner.runInTransaction {
            itemDao.update(updated)
            operation = appendOperation(
                type = OperationType.EDIT,
                itemId = current.id,
                locationCode = null,
                quantity = null,
                asrText = null,
                rawText = description,
            )
        }

        return EditResult.Saved(
            item = updated,
            operation = operation ?: error("EDIT 操作没有写入"),
            renamedFrom = renamedFrom,
        )
    }

    /** 独立保存物品级补货阈值；零表示只在用完时提醒，null 表示关闭。 */
    suspend fun setReplenishmentThreshold(itemId: String, threshold: Double?): EditResult {
        if (threshold != null && (!threshold.isFinite() || threshold < 0.0)) {
            return EditResult.Rejected("提醒数量必须是零或正数")
        }
        val current = itemDao.findLiveById(itemId)
            ?: return EditResult.Rejected("物品不存在或已进回收站")
        if (current.restockThreshold == threshold) return EditResult.Unchanged(current)
        val updated = current.copy(restockThreshold = threshold, updatedAt = System.currentTimeMillis())
        val description = describeEdit(current, updated) ?: error("补货提醒变更缺少审计记录")
        var operation: OperationEntity? = null
        transactionRunner.runInTransaction {
            itemDao.update(updated)
            operation = appendOperation(
                type = OperationType.EDIT,
                itemId = itemId,
                locationCode = null,
                quantity = null,
                asrText = null,
                rawText = description,
            )
        }
        stockStateRefresh()
        return EditResult.Saved(updated, operation ?: error("EDIT 操作没有写入"), null)
    }

    // ---------- 内部 ----------

    /**
     * 找到或创建物品档案（M1-B 的 B3）。
     *
     * 匹配顺序：**精确名 → 数字形态变体 → 别名 → 新建**。不阻断（符合零确认），
     * 查不到就直接建，而不是提示用户「这个物品不存在」。
     *
     * ## 为什么要有「数字形态变体」
     *
     * M0.6 实测：配了物品名热词之后，阿里会把「7号电池」写成「七号电池」；
     * 而不带热词时它写回 ASCII。同一件东西在两次录入里名字不一样，
     * 精确匹配就会建出两条档案 —— 之后「东西在哪」只能答一半，冷门提醒也会重算错。
     *
     * 所以查库时先做一次「七 ↔ 7」的双向尝试。**只影响查，不影响写**：
     * 写进去的仍是用户这次说的那个形态，档案名不会被悄悄改掉。
     *
     * ## 模糊匹配仍然不做
     *
     * 「膨胀阀」找不到「膨胀栓」这件事**故意不由这里解决** —— 那是 M2 的 LLM 判断，
     * 本地做字符模糊会在「螺丝 / 螺丝刀」这种词上直接判错。
     */
    private suspend fun resolveItem(
        itemName: String,
        aliases: List<String>,
        category: String?,
        expiryDate: String?,
        now: Long,
    ): ItemEntity {
        val found = findExistingItem(itemName)
        if (found != null) {
            // 已有档案：只在传了新值时补齐，不覆盖已有数据
            val merged = found.copy(
                category = found.category ?: category,
                expiryDate = found.expiryDate ?: expiryDate,
                updatedAt = now,
            )
            if (merged != found) itemDao.update(merged)
            return merged
        }

        val newItem = ItemEntity(
            id = UUID.randomUUID().toString(),
            name = itemName,
            aliases = ItemAttributes.encodeAliases(aliases),
            category = category,
            expiryDate = expiryDate,
            lastUsedAt = null,
            deletedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        itemDao.upsert(newItem)
        return newItem
    }

    /** 精确名 → 数字形态变体 → 别名。命中即返回，全不中返回 null。 */
    private suspend fun findExistingItem(itemName: String): ItemEntity? {
        for (variant in ItemAttributes.variants(itemName)) {
            itemDao.findLiveByName(variant)?.let { return it }
        }
        for (variant in ItemAttributes.variants(itemName)) {
            itemDao.findLiveByAlias(ItemAttributes.encodedAlias(variant))?.let { return it }
        }
        return null
    }

    /** 档案 + 位置记录 → 反查快照。合计由 [ItemSnapshot] 派生，不落库。 */
    private fun ItemEntity.toSnapshot(records: List<LocationRecordEntity>): ItemSnapshot =
        ItemSnapshot(
            id = id,
            name = name,
            category = category,
            locations = records.map { LocationQty(it.locationCode, it.quantity) },
        )

    /**
     * 把一次属性修改写成一句人话（进 `operations.raw_text`）。
     *
     * 返回 null 表示什么都没变 —— 那种情况下不写 EDIT：
     * 历史里出现一条「改了什么也没改」的记录，比不记录更糟。
     */
    private fun describeEdit(before: ItemEntity, after: ItemEntity): String? {
        val parts = mutableListOf<String>()
        if (before.name != after.name) {
            parts += "名称：${before.name} → ${after.name}"
        }
        if (before.aliases != after.aliases) {
            parts += "别名：" +
                ItemAttributes.decodeAliases(before.aliases).joinToString("、").ifBlank { "无" } +
                " → " +
                ItemAttributes.decodeAliases(after.aliases).joinToString("、").ifBlank { "无" }
        }
        if (before.category != after.category) {
            parts += "类别：${before.category ?: "未设置"} → ${after.category ?: "未设置"}"
        }
        if (before.expiryDate != after.expiryDate) {
            parts += "有效期：${before.expiryDate ?: "未设置"} → ${after.expiryDate ?: "未设置"}"
        }
        if (before.restockThreshold != after.restockThreshold) {
            fun label(value: Double?): String = when (value) {
                null -> "关闭"
                0.0 -> "只在用完时提醒"
                else -> "剩余 $value 时提醒"
            }
            parts += "补货提醒：${label(before.restockThreshold)} → ${label(after.restockThreshold)}"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private suspend fun appendOperation(
        type: String,
        itemId: String?,
        locationCode: String?,
        quantity: Double?,
        asrText: String?,
        rawText: String?,
        batchId: String? = null,
        targetSeq: Long? = null,
    ): OperationEntity {
        val op = OperationEntity(
            id = UUID.randomUUID().toString(),
            seq = operationDao.maxSeq() + 1,
            type = type,
            itemId = itemId,
            locationCode = locationCode,
            quantity = quantity,
            rawText = rawText,
            asrText = asrText,
            targetSeq = targetSeq,
            batchId = batchId,
            revokedAt = null,
            createdAt = System.currentTimeMillis(),
        )
        operationDao.append(op)
        return op
    }

    // 注：这里原本有一个 5 分钟纠错窗口常量（决策 #7）。
    // 2026-10-17 撤销改为「无时间限制」后一并删除 —— 留着一个没人用的窗口常量，
    // 迟早会被某处重新捡起来用，把没期限的撤销又变回有期限。
}

sealed interface HistoryRevokeResult {
    data class Revoked(val operation: OperationEntity) : HistoryRevokeResult
    data class Rejected(val reason: String) : HistoryRevokeResult
}

sealed interface RestoreResult {
    data object Restored : RestoreResult
    data class Rejected(val reason: String) : RestoreResult
}

/**
 * 属性修改的结果。
 *
 * 刻意不用 `ItemEntity?`：null 无法区分「名字冲突」「日期非法」「物品不存在」，
 * 而详情页必须把**具体原因**说出来 —— 用户点一次保存只得到「没成功」，
 * 就只能在四个输入框里挨个猜。
 */
sealed interface EditResult {
    /** 已写入：物品新状态 + 审计操作（`renamedFrom` 非空表示这次改了名）。 */
    data class Saved(
        val item: ItemEntity,
        val operation: OperationEntity,
        val renamedFrom: String?,
    ) : EditResult

    /** 字段与原值完全一致，没有写库、也没有写 EDIT。 */
    data class Unchanged(val item: ItemEntity) : EditResult

    /** 被拒绝，[reason] 是可以直接展示给用户的一句话。 */
    data class Rejected(val reason: String) : EditResult
}

/** 反查「没这个物品」时最多给几条近似结果。 */
private const val SUGGESTION_LIMIT = 3
