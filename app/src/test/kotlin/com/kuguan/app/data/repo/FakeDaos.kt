package com.kuguan.app.data.repo

import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.LocationRecordEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.dao.ItemDao
import com.kuguan.app.data.db.dao.ItemLocationRow
import com.kuguan.app.data.db.dao.HistoryOperationRow
import com.kuguan.app.data.db.dao.LocationDao
import com.kuguan.app.data.db.dao.OperationDao
import com.kuguan.app.domain.reminder.ReminderSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 内存版 DAO，供 [StorageRepositoryTest] 使用。
 *
 * 为什么不用 Room in-memory：被测的三条规则（位置级数量、last_used_at 派生、
 * append-only 撤销）都是纯 Kotlin 逻辑，与 SQLite 无关。用 fake 可以让这批测试
 * 在毫秒级跑完，且不需要 Android 环境 —— 这对「改了逻辑就立刻验证」很重要。
 *
 * 三个 fake 通过 [FakeDb] 互相持有引用，因为 [ItemDao.recomputeLastUsedAt]
 * 需要读 operations 表 ——真实实现里这是 SQL 子查询，fake 里得显式连线。
 */
class FakeDb {
    val operationDao = FakeOperationDao()
    val locationDao = FakeLocationDao()
    val itemDao = FakeItemDao(operationDao).also { dao ->
        // 真实 SQL 里搜索是一次 JOIN；fake 里显式连线，保证 repository 的分组逻辑被测到
        dao.locationSource = { locationDao.rows.values }
    }

    init { operationDao.itemSource = { itemDao.rows.values } }

    fun repository() = StorageRepository(itemDao, locationDao, operationDao)
}

class FakeItemDao(private val operations: FakeOperationDao) : ItemDao {
    val rows = linkedMapOf<String, ItemEntity>()

    override suspend fun backupCount() = rows.size
    override suspend fun backupPage(afterId: String, limit: Int) =
        rows.values.filter { it.id > afterId }.sortedBy { it.id }.take(limit)

    /** 搜索投影需要位置记录；[FakeDb] 构造时接上 [FakeLocationDao.rows]。 */
    var locationSource: () -> Collection<LocationRecordEntity> = { emptyList() }

    override suspend fun upsert(item: ItemEntity) { rows[item.id] = item }

    override suspend fun update(item: ItemEntity) { rows[item.id] = item }

    override suspend fun findById(id: String) = rows[id]

    override suspend fun findLiveById(id: String) = rows[id]?.takeIf { it.deletedAt == null }

    override suspend fun countLive() = rows.values.count { it.deletedAt == null }

    override fun observeLiveCount(): Flow<Int> = flowOf(countLiveBlocking())

    private fun countLiveBlocking() = rows.values.count { it.deletedAt == null }

    override suspend fun findLiveByName(name: String) =
        rows.values.firstOrNull { it.name == name && it.deletedAt == null }

    override suspend fun findLiveByAlias(encodedAlias: String) =
        rows.values
            .filter { it.deletedAt == null && it.aliases.contains(encodedAlias) }
            .maxByOrNull { it.updatedAt }

    override fun searchWithLocations(
        keyword: String,
        variantA: String,
        variantB: String,
    ): Flow<List<ItemLocationRow>> =
        flowOf(
            rows.values
                .filter {
                    it.deletedAt == null &&
                        listOf(keyword, variantA, variantB).any { term ->
                            it.name.contains(term) || it.aliases.contains(term)
                        }
                }
                .sortedByDescending { it.updatedAt }
                .flatMap { item ->
                    val live = locationSource()
                        .filter { it.itemId == item.id && it.deletedAt == null }
                        .sortedBy { it.locationCode }
                    // LEFT JOIN 语义：没位置也要出一行（位置列 null）
                    if (live.isEmpty()) {
                        listOf(item.toLocationRow(null, null))
                    } else {
                        live.map { item.toLocationRow(it.locationCode, it.quantity) }
                    }
                }
        )

    private fun ItemEntity.toLocationRow(code: String?, qty: Double?) = ItemLocationRow(
        id = id,
        name = name,
        aliases = aliases,
        category = category,
        expiryDate = expiryDate,
        lastUsedAt = lastUsedAt,
        locationCode = code,
        quantity = qty,
    )

    override suspend fun findSimilarLive(keyword: String, limit: Int) =
        rows.values
            .filter { it.deletedAt == null && it.name.contains(keyword) }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override fun observeAllLive(): Flow<List<ItemEntity>> =
        flowOf(rows.values.filter { it.deletedAt == null })

    /** 与真实 SQL 一致：取最近一次未撤销 TAKE 的时间重算。 */
    override suspend fun recomputeLastUsedAt(itemId: String) {
        val item = rows[itemId] ?: return
        val latest = operations.takes(itemId).maxOfOrNull { it.createdAt }
        rows[itemId] = item.copy(lastUsedAt = latest)
    }

    override suspend fun findColdItems(threshold: Long) = emptyList<Nothing>()

    override suspend fun reminderSources(): List<ReminderSource> = rows.values
        .filter { it.deletedAt == null }
        .flatMap { item ->
            locationSource().filter { it.itemId == item.id && it.deletedAt == null && it.quantity > 0 }
                .sortedBy { it.locationCode }
                .map { location -> ReminderSource(item.id, item.name, location.locationCode,
                    item.createdAt, item.lastUsedAt, item.expiryDate) }
        }

    override fun observeDeleted(): Flow<List<ItemEntity>> =
        flowOf(rows.values.filter { it.deletedAt != null })

    override suspend fun restore(id: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(deletedAt = null, updatedAt = now) }
    }

    override suspend fun exportAll() = rows.values.toList()

    override suspend fun deleteAll() = rows.clear()
}

class FakeLocationDao : LocationDao {
    val rows = linkedMapOf<String, LocationRecordEntity>()

    override suspend fun backupCount() = rows.size
    override suspend fun backupPage(afterId: String, limit: Int) =
        rows.values.filter { it.id > afterId }.sortedBy { it.id }.take(limit)

    override suspend fun upsert(record: LocationRecordEntity) { rows[record.id] = record }

    override fun observeByItem(itemId: String): Flow<List<LocationRecordEntity>> =
        flowOf(rows.values.filter { it.itemId == itemId && it.deletedAt == null })

    override suspend fun findLiveByItem(itemId: String): List<LocationRecordEntity> =
        rows.values
            .filter { it.itemId == itemId && it.deletedAt == null }
            .sortedBy { it.locationCode }

    override suspend fun findLive(itemId: String, locationCode: String) =
        rows.values.firstOrNull {
            it.itemId == itemId && it.locationCode == locationCode && it.deletedAt == null
        }

    // 与真实 SQL 一致：唯一索引 (item_id, location_code) 保证最多一条，
    // 且**包含已软删的那条** —— 撤销「取光」的操作必须能找到它。
    override suspend fun findAnyAtLocation(itemId: String, locationCode: String) =
        rows.values.firstOrNull { it.itemId == itemId && it.locationCode == locationCode }

    override suspend fun countLiveForItem(itemId: String) =
        rows.values.count { it.itemId == itemId && it.deletedAt == null }

    override fun observeByLocation(locationCode: String): Flow<List<LocationRecordEntity>> =
        flowOf(rows.values.filter { it.locationCode == locationCode && it.deletedAt == null })

    override suspend fun softDelete(id: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(deletedAt = now, updatedAt = now) }
    }

    override suspend fun softDeleteByItem(itemId: String, now: Long) {
        rows.values.filter { it.itemId == itemId && it.deletedAt == null }.forEach {
            rows[it.id] = it.copy(deletedAt = now, updatedAt = now)
        }
    }

    override suspend fun maxDeletedAtForItem(itemId: String): Long? =
        rows.values.filter { it.itemId == itemId }.mapNotNull { it.deletedAt }.maxOrNull()

    override suspend fun restoreByItem(itemId: String, deletedAt: Long, now: Long) {
        rows.values.filter { it.itemId == itemId && it.deletedAt == deletedAt }.forEach {
            rows[it.id] = it.copy(deletedAt = null, updatedAt = now)
        }
    }

    override suspend fun exportAll() = rows.values.toList()

    override suspend fun deleteAll() = rows.clear()
}

class FakeOperationDao : OperationDao {
    val rows = linkedMapOf<String, OperationEntity>()

    override suspend fun latestActivePutTimes() = rows.values
        .filter { it.type == com.kuguan.app.data.db.OperationType.PUT && it.revokedAt == null &&
            it.itemId != null && it.locationCode != null }
        .groupBy { it.itemId!! to it.locationCode!! }
        .map { (key, operations) ->
            com.kuguan.app.data.db.dao.LatestPutTimeRow(
                key.first, key.second, operations.maxOf { it.createdAt },
            )
        }

    override suspend fun backupPage(afterSeq: Long, limit: Int) =
        rows.values.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit)
    var itemSource: () -> Collection<ItemEntity> = { emptyList() }

    /** 供 [FakeItemDao] 重算 last_used_at 时查询未撤销的 TAKE。 */
    fun takes(itemId: String) = rows.values.filter {
        it.itemId == itemId &&
            it.type == com.kuguan.app.data.db.OperationType.TAKE &&
            it.revokedAt == null
    }

    override suspend fun append(operation: OperationEntity) { rows[operation.id] = operation }

    override suspend fun appendAll(operations: List<OperationEntity>) {
        operations.forEach { rows[it.id] = it }
    }

    override suspend fun maxSeq() = rows.values.maxOfOrNull { it.seq } ?: 0L

    override suspend fun findById(id: String) = rows[id]

    override suspend fun findBySeq(seq: Long) = rows.values.firstOrNull { it.seq == seq }

    override suspend fun findLatestActive() =
        rows.values
            .filter { it.revokedAt == null && it.type in INVENTORY_TYPES }
            .maxByOrNull { it.seq }

    override suspend fun findRecentActive(limit: Int) =
        rows.values
            .filter { it.revokedAt == null && it.type in INVENTORY_TYPES }
            .sortedByDescending { it.seq }
            .take(limit)

    override fun observeRecent(limit: Int): Flow<List<OperationEntity>> =
        flowOf(rows.values.sortedByDescending { it.seq }.take(limit))

    override fun observeRecentWithNames(limit: Int): Flow<List<HistoryOperationRow>> =
        flowOf(rows.values.sortedByDescending { it.seq }.take(limit).map { op ->
            HistoryOperationRow(op, itemSource().firstOrNull { it.id == op.itemId }?.name)
        })

    override fun observeByItem(itemId: String, limit: Int): Flow<List<OperationEntity>> =
        flowOf(rows.values.filter { it.itemId == itemId }.sortedByDescending { it.seq }.take(limit))

    override suspend fun count() = rows.size

    override suspend fun countActiveForItem(itemId: String) =
        rows.values.count {
            it.itemId == itemId && it.revokedAt == null && it.type in INVENTORY_TYPES
        }

    /**
     * 与真实 SQL 逐条对齐，包括 `batch_id IS NULL` 的 OR 分支 ——
     * SQL 里 `x <> NULL` 求值为 NULL（非真），少这一句会让单条录入的后续操作被漏判成同批。
     */
    override suspend fun countConflictingLaterOps(
        seq: Long,
        itemId: String,
        locationCode: String,
        batchId: String?,
        type: String,
    ): Int = rows.values.count {
        it.revokedAt == null &&
            it.seq > seq &&
            it.itemId == itemId &&
            it.locationCode == locationCode &&
            (it.batchId == null || it.batchId != batchId || it.type != type)
    }

    override suspend fun findActiveByBatch(batchId: String): List<OperationEntity> =
        rows.values
            .filter { it.batchId == batchId && it.revokedAt == null }
            .sortedByDescending { it.seq }

    override suspend fun markRevoked(seq: Long, now: Long) {
        rows.values.filter { it.seq == seq && it.revokedAt == null }.forEach {
            rows[it.id] = it.copy(revokedAt = now)
        }
    }

    override suspend fun exportAll() = rows.values.toList()

    /** 测试辅助：把最新一条操作的时间往前推，模拟「超出纠错窗口」。 */
    fun forceAgeLatestOperation(millis: Long) {
        val latest = rows.values.maxByOrNull { it.seq } ?: return
        rows[latest.id] = latest.copy(createdAt = latest.createdAt - millis)
    }

    /** 测试辅助：把**指定**一条操作的时间往前推（不是最新的那条时用它）。 */
    fun forceAgeOperation(seq: Long, millis: Long) {
        val op = rows.values.firstOrNull { it.seq == seq } ?: return
        rows[op.id] = op.copy(createdAt = op.createdAt - millis)
    }

    private companion object {
        /** 与 OperationDao 的 SQL 过滤保持一致：EDIT 是审计行，不算库存操作。 */
        val INVENTORY_TYPES = setOf(
            com.kuguan.app.data.db.OperationType.PUT,
            com.kuguan.app.data.db.OperationType.TAKE,
        )
    }
}
