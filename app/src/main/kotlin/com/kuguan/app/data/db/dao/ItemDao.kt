package com.kuguan.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.domain.reminder.ReminderSource
import kotlinx.coroutines.flow.Flow

/** 冷门物品查询的返回投影。 */
data class ColdItemRow(
    val id: String,
    val name: String,
    val locationCode: String,
    val lastUsedAt: Long?,
)

/** 搜索结果投影：物品 + 全部位置，一次查完避免 N+1。 */
data class ItemWithLocations(
    val id: String,
    val name: String,
    val aliases: String,
    val category: String?,
    val expiryDate: String?,
    val lastUsedAt: Long?,
    val locations: List<LocationSummary>,
)

data class LocationSummary(
    val locationCode: String,
    val quantity: Double,
)

/**
 * 搜索 SQL 的**扁平行**：一个物品有几个在架位置就有几行（没有位置时位置列全 null）。
 *
 * 为什么不直接返回 [ItemWithLocations]：Room 的嵌套投影要靠 `@Relation`，
 * 而 `@Relation` 只能挂在 Entity 上；为了一个只读结果再造一个 @Entity 会把
 * 「三张表」的边界搞乱。扁平行 + 一次 `groupBy` 更直白，且仍然只有**一条 SQL**。
 */
data class ItemLocationRow(
    val id: String,
    val name: String,
    val aliases: String,
    val category: String?,
    val expiryDate: String?,
    val lastUsedAt: Long?,
    val locationCode: String?,
    val quantity: Double?,
)

@Dao
interface ItemDao {

    @Query("SELECT COUNT(*) FROM items")
    suspend fun backupCount(): Int

    @Query("SELECT * FROM items WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun backupPage(afterId: String, limit: Int): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun findById(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id AND deleted_at IS NULL")
    suspend fun findLiveById(id: String): ItemEntity?

    @Query("SELECT COUNT(*) FROM items WHERE deleted_at IS NULL")
    suspend fun countLive(): Int

    @Query("SELECT COUNT(*) FROM items WHERE deleted_at IS NULL")
    fun observeLiveCount(): Flow<Int>

    /**
     * 按名称精确匹配（活记录）。用于「别名归一」的第一步。
     * 单人物品规模下全表扫是 1ms 级，不需要额外索引优化。
     */
    @Query("SELECT * FROM items WHERE name = :name AND deleted_at IS NULL LIMIT 1")
    suspend fun findLiveByName(name: String): ItemEntity?

    /**
     * 按**别名**精确匹配（活记录）。
     *
     * 别名存在 JSON 数组字符串里（`["墙塞","膨胀管"]`），所以用完整 JSON token 做 instr：
     * 这样「螺丝」不会命中别名「螺丝刀」—— 引号把匹配限定在**完整元素**上。
     *
     * 为什么必须有这一步：M1 的 `resolveItem` 只认精确同名，用户先说「膨胀栓」、
     * 后说「膨胀螺丝」，就会在库里建出第二条档案；之后「东西在哪」永远只能回答一半。
     */
    @Query(
        """
        SELECT * FROM items
        WHERE deleted_at IS NULL AND instr(aliases, :encodedAlias) > 0
        ORDER BY updated_at DESC
        LIMIT 1
        """
    )
    suspend fun findLiveByAlias(encodedAlias: String): ItemEntity?

    /**
     * 关键词搜索：跨名称与别名，**一条 SQL 连位置一起查出来**。
     *
     * 为什么不用「先查物品、再逐个查位置」：那是典型的 N+1 ——
     * 搜到 30 个物品就发 31 次查询，而且每个 item 的位置订阅还会各自触发刷新。
     * 这里 LEFT JOIN 一次拿全，Repository 里按 id 分组即可。
     *
     * 位置用 LEFT JOIN：物品可能已全部取走（没有在架位置），
     * 它仍然应该出现在搜索结果里（用户要找的是「东西」，不是「位置」）。
     *
     * 搜索页还承担按类别浏览，空关键词要拿到全部活物品，不能截断分类。
     */
    @Query(
        """
        SELECT i.id AS id, i.name AS name, i.aliases AS aliases,
               i.category AS category, i.expiry_date AS expiryDate,
               i.last_used_at AS lastUsedAt,
               l.location_code AS locationCode, l.quantity AS quantity
        FROM items i
        LEFT JOIN location_records l
               ON l.item_id = i.id AND l.deleted_at IS NULL
        WHERE i.deleted_at IS NULL
          AND i.id IN (
              SELECT id FROM items
              WHERE deleted_at IS NULL
                AND (
                    instr(name, :keyword) > 0 OR instr(aliases, :keyword) > 0 OR
                    instr(name, :variantA) > 0 OR instr(aliases, :variantA) > 0 OR
                    instr(name, :variantB) > 0 OR instr(aliases, :variantB) > 0
                )
              ORDER BY updated_at DESC
          )
        ORDER BY i.updated_at DESC, l.location_code ASC
        """
    )
    fun searchWithLocations(
        keyword: String,
        variantA: String,
        variantB: String,
    ): Flow<List<ItemLocationRow>>

    /**
     * 名字部分匹配（「库里没有登山杖，最接近的有登山棍」）。
     *
     * 只做 `LIKE`，不做编辑距离：本地字符模糊在「螺丝 / 螺丝刀」上会直接判错，
     * 这是 M2 已经写进 prompt 的同一条取舍。单字关键词不查（太宽，等于给全库）。
     */
    @Query(
        """
        SELECT * FROM items
        WHERE deleted_at IS NULL AND instr(name, :keyword) > 0
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun findSimilarLive(keyword: String, limit: Int): List<ItemEntity>


    @Query("SELECT * FROM items WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAllLive(): Flow<List<ItemEntity>>

    /**
     * 派生 [ItemEntity.lastUsedAt]：取该物品最近一次**未撤销的 TAKE** 的时间。
     *
     * ⚠️ 这是冷门提醒正确性的**唯一来源**，所以 operations 表绝不做自动清理。
     * 在每次 TAKE 以及任何影响历史完整性的操作（撤销、纠错、导入）后都要调用。
     */
    @Query(
        """
        UPDATE items SET last_used_at = (
            SELECT MAX(created_at) FROM operations
            WHERE operations.item_id = items.id
              AND operations.type = 'TAKE'
              AND operations.revoked_at IS NULL
        )
        WHERE id = :itemId
        """
    )
    suspend fun recomputeLastUsedAt(itemId: String)

    /**
     * 冷门物品查询（>365 天未取用，或从未取用）。
     *
     * 两个要点：
     * 1. `last_used_at IS NULL` 也命中 —— 按决策 #9，从未取用过的物品进冷门提醒。
     * 2. `LIMIT 5` 防止一年没用过的东西一次冒出 50 条把通知撑爆。
     */
    @Query(
        """
        SELECT i.id AS id, i.name AS name, l.location_code AS locationCode, i.last_used_at AS lastUsedAt
        FROM items i
        JOIN location_records l ON l.item_id = i.id AND l.deleted_at IS NULL
        WHERE i.deleted_at IS NULL
          AND (i.last_used_at IS NULL OR i.last_used_at < :threshold)
          AND EXISTS (
              SELECT 1 FROM location_records WHERE item_id = i.id AND deleted_at IS NULL
          )
        LIMIT 5
        """
    )
    suspend fun findColdItems(threshold: Long): List<ColdItemRow>

    /** One snapshot row per live item, including those with no active location. */
    @Query(
        """
        SELECT i.id AS id, i.name AS name,
               COALESCE((SELECT GROUP_CONCAT(location_code, '、') FROM (
                   SELECT location_code FROM location_records
                   WHERE item_id = i.id AND deleted_at IS NULL AND quantity > 0
                   ORDER BY location_code
               )), '') AS location,
               i.created_at AS createdAt, i.last_used_at AS lastUsedAt,
               i.expiry_date AS expiryDate,
               COALESCE((SELECT SUM(quantity) FROM location_records
                   WHERE item_id = i.id AND deleted_at IS NULL AND quantity > 0), 0) AS quantity,
               i.restock_threshold AS restockThreshold
        FROM items i
        WHERE i.deleted_at IS NULL
        ORDER BY i.id
        """
    )
    suspend fun reminderSources(): List<ReminderSource>

    /** 回收站列表。 */
    @Query("SELECT * FROM items WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(): Flow<List<ItemEntity>>

    /** 从回收站恢复。 */
    @Query("UPDATE items SET deleted_at = NULL, updated_at = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /** 全量导出（M6 备份）。 */
    @Query("SELECT * FROM items ORDER BY created_at ASC")
    suspend fun exportAll(): List<ItemEntity>

    /** 全量覆盖式导入前清库（M6）。 */
    @Query("DELETE FROM items")
    suspend fun deleteAll()
}
