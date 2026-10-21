package com.kuguan.app.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import com.kuguan.app.data.db.OperationEntity
import kotlinx.coroutines.flow.Flow

data class HistoryOperationRow(
    @Embedded val operation: OperationEntity,
    val itemName: String?,
)

data class LatestPutTimeRow(
    val itemId: String,
    val locationCode: String,
    val lastPutAt: Long,
)

/**
 * ⚠️ **本 DAO 刻意不提供任何 DELETE 方法。**
 *
 * operations 是 append-only 的审计日志，`last_used_at` 完全由它派生，
 * 冷门提醒的正确性直接依赖它不被破坏。撤销/纠错只打 [OperationEntity.revokedAt] 标记，
 * 不删行。把 DELETE 从这个接口里彻底移除，比写注释更可靠 —— 想删行的人在编译期就找不到方法可调。
 *
 * M6 备份导入需要清库重建，那是唯一例外，放在 [BackupRestoreDao] 里，
 * 与日常写入路径物理隔离。
 */
@Dao
interface OperationDao {

    /** 当前库存提示词只需每个物品/位置最近一次未撤销的放入时间。 */
    @Query("""
        SELECT item_id AS itemId, location_code AS locationCode,
               MAX(created_at) AS lastPutAt
        FROM operations
        WHERE type = 'PUT' AND revoked_at IS NULL
          AND item_id IS NOT NULL AND location_code IS NOT NULL
        GROUP BY item_id, location_code
    """)
    suspend fun latestActivePutTimes(): List<LatestPutTimeRow>

    @Query("SELECT * FROM operations WHERE seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun backupPage(afterSeq: Long, limit: Int): List<OperationEntity>

    @Insert
    suspend fun append(operation: OperationEntity)

    /** 导入时批量追加。 */
    @Insert
    suspend fun appendAll(operations: List<OperationEntity>)

    /** 当前最大 seq。新增操作用它 +1，保证单调递增且不重复。 */
    @Query("SELECT IFNULL(MAX(seq), 0) FROM operations")
    suspend fun maxSeq(): Long

    @Query("SELECT * FROM operations WHERE id = :id")
    suspend fun findById(id: String): OperationEntity?

    @Query("SELECT * FROM operations WHERE seq = :seq LIMIT 1")
    suspend fun findBySeq(seq: Long): OperationEntity?

    /**
     * 最近一条**未撤销的库存操作**（PUT/TAKE）。纠错/撤销的「上一条」就是它。
     *
     * 必须排除 `EDIT`：属性修改也是 operations 里的一行，但它不产生库存事实。
     * 若把它当成「上一条」，「删掉上一条」会去撤一条本来就没写库存的记录，
     * 而用户真正想删的那次放入还在库里 —— 静默地什么也没做，却回报「已删掉」。
     */
    @Query(
        """
        SELECT * FROM operations
        WHERE revoked_at IS NULL AND type IN ('PUT', 'TAKE')
        ORDER BY seq DESC
        LIMIT 1
        """
    )
    suspend fun findLatestActive(): OperationEntity?

    /**
     * 最近 N 条未撤销的**库存操作**摘要，注入 LLM prompt 作纠错上下文。
     *
     * 同样排除 `EDIT`：prompt 里的「最近操作」是给纠正/删除指代用的，
     * 塞一条属性修改进去只会让模型把 targetSeq 指向一个不能改库存的行。
     */
    @Query(
        """
        SELECT * FROM operations
        WHERE revoked_at IS NULL AND type IN ('PUT', 'TAKE')
        ORDER BY seq DESC
        LIMIT :limit
        """
    )
    suspend fun findRecentActive(limit: Int): List<OperationEntity>

    @Query("SELECT * FROM operations ORDER BY seq DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OperationEntity>>

    /** 包括回收站物品的名字；一条 JOIN 避免历史页逐行查询。 */
    @Query(
        """
        SELECT o.*, i.name AS itemName
        FROM operations o LEFT JOIN items i ON i.id = o.item_id
        ORDER BY o.seq DESC LIMIT :limit
        """
    )
    fun observeRecentWithNames(limit: Int): Flow<List<HistoryOperationRow>>

    @Query("SELECT * FROM operations WHERE item_id = :itemId ORDER BY seq DESC LIMIT :limit")
    fun observeByItem(itemId: String, limit: Int): Flow<List<OperationEntity>>

    @Query("SELECT COUNT(*) FROM operations")
    suspend fun count(): Int

    /**
     * 该物品还有多少条**未撤销**的操作。
     *
     * 撤销回滚时用它判断「这个物品是不是只因为这一条操作才存在」：
     * 计数为 1 说明眼前这条是它存在的唯一原因，撤掉后物品本身也不该留下
     * （否则撤销一条听错的「登山仗」，库里会多一个谁也没放过的空物品，
     *  首页计数还不回退 —— 用户会觉得撤销没生效）。
     *
     * 只数未撤销的：已被撤销的历史不该让一个物品显得「有来头」。
     *
     * 也只数 PUT/TAKE：`EDIT` 是审计行，不代表「这个物品因它而存在」。
     * 否则「放进一个东西 → 改个类别 → 撤销那次放入」会因为 EDIT 多占一个计数，
     * 孤儿档案收不掉，首页物品数也不回退 —— 用户会觉得撤销没生效。
     */
    @Query(
        """
        SELECT COUNT(*) FROM operations
        WHERE item_id = :itemId AND revoked_at IS NULL AND type IN ('PUT', 'TAKE')
        """
    )
    suspend fun countActiveForItem(itemId: String): Int

    /**
     * 该行之后，同一 (物品, 位置) 上还有几条「不可交换」的活跃操作。
     * **返回 0 才允许单独撤销这一行。**
     *
     * ## 为什么同批同类型可以放行
     *
     * 同批的 PUT 是**纯累加**：放 5 又放 3 得 8，撤掉「放 5」剩 3 —— 与操作顺序无关，
     * 撤销可交换，先撤哪一条结果都一样。所以它们是兄弟，不是前提关系。
     *
     * ## 为什么要拦后续 TAKE
     *
     * 放 5 → 取 2（库存 3）→ 撤掉「放 5」：3 − 5 = −2，会被 clamp 成 0，
     * 凭空丢 2 个。那条 TAKE 已经消耗了库存，撤掉它的来源会让账目失真。
     * 这正是「撤中间一条会让后面的记录失去前提」的具象形态。
     *
     * `batch_id IS NULL` 放在 OR 里是必需的：SQL 里 `x <> NULL` 求值为 NULL（非真），
     * 单条录入（batch_id 为空）的后续操作会因此漏判成「同批」而被放行。
     */
    @Query(
        """
        SELECT COUNT(*) FROM operations
        WHERE revoked_at IS NULL
          AND seq > :seq
          AND item_id = :itemId
          AND location_code = :locationCode
          AND (batch_id IS NULL OR batch_id <> :batchId OR type <> :type)
        """
    )
    suspend fun countConflictingLaterOps(
        seq: Long,
        itemId: String,
        locationCode: String,
        batchId: String?,
        type: String,
    ): Int

    /**
     * 该批次里全部**未撤销**的操作，按 seq **降序**。
     *
     * 降序不是排版偏好：整批撤销必须从最新往回逐条撤，
     * 这样每条在被撤的那一刻都是该位置的最新活跃操作。
     */
    @Query(
        """
        SELECT * FROM operations
        WHERE batch_id = :batchId AND revoked_at IS NULL
        ORDER BY seq DESC
        """
    )
    suspend fun findActiveByBatch(batchId: String): List<OperationEntity>

    /** 打撤销标记，不删行。 */
    @Query("UPDATE operations SET revoked_at = :now WHERE seq = :seq AND revoked_at IS NULL")
    suspend fun markRevoked(seq: Long, now: Long)

    /** 全量导出（M6 备份，必须包含完整历史）。 */
    @Query("SELECT * FROM operations ORDER BY seq ASC")
    suspend fun exportAll(): List<OperationEntity>
}
