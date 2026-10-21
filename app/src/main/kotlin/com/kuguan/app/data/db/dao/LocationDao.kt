package com.kuguan.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kuguan.app.data.db.LocationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Query("SELECT COUNT(*) FROM location_records")
    suspend fun backupCount(): Int

    @Query("SELECT * FROM location_records WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun backupPage(afterId: String, limit: Int): List<LocationRecordEntity>

    /**
     * 唯一索引 (item_id, location_code) 上做 REPLACE，
     * 语义即「同一物品在同一位置只有一条记录，数量覆盖」。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: LocationRecordEntity)

    @Query(
        """
        SELECT * FROM location_records
        WHERE item_id = :itemId AND deleted_at IS NULL
        ORDER BY location_code ASC
        """
    )
    fun observeByItem(itemId: String): Flow<List<LocationRecordEntity>>

    /**
     * 某物品当前**在架**的全部位置（一次性查询）。
     *
     * query 意图（「登山杖在哪」）要的是一句立刻能回显的话，不是持续订阅；
     * 用 Flow 再 `first()` 会把简单读取变成协程流操作，没必要。
     */
    @Query(
        """
        SELECT * FROM location_records
        WHERE item_id = :itemId AND deleted_at IS NULL
        ORDER BY location_code ASC
        """
    )
    suspend fun findLiveByItem(itemId: String): List<LocationRecordEntity>

    @Query(
        """
        SELECT * FROM location_records
        WHERE item_id = :itemId AND location_code = :locationCode AND deleted_at IS NULL
        LIMIT 1
        """
    )
    suspend fun findLive(itemId: String, locationCode: String): LocationRecordEntity?

    /**
     * 查该物品在该位置的记录，**包含已被软删的那条**。
     *
     * 撤销回滚必须用它而不是 [findLive]：取出把某位置取光时，那条位置记录已被软删，
     * 用 `findLive` 会查不到 → 回滚直接 return → 数量没加回去，
     * 但撤销标记照样打上了。结果是**数据没回滚而界面显示已撤销**，
     * 属于最难发现的静默损坏。
     *
     * (item_id, location_code) 上有唯一索引，所以最多只有一条，不需要排序去重。
     */
    @Query(
        """
        SELECT * FROM location_records
        WHERE item_id = :itemId AND location_code = :locationCode
        LIMIT 1
        """
    )
    suspend fun findAnyAtLocation(itemId: String, locationCode: String): LocationRecordEntity?

    /** 该物品还有几条**在架**的位置记录。用于判断撤销后物品是否成了空档案。 */
    @Query(
        """
        SELECT COUNT(*) FROM location_records
        WHERE item_id = :itemId AND deleted_at IS NULL
        """
    )
    suspend fun countLiveForItem(itemId: String): Int

    /** 某个位置上的全部物品（位置 → 物品的反查）。 */
    @Query(
        """
        SELECT * FROM location_records
        WHERE location_code = :locationCode AND deleted_at IS NULL
        ORDER BY updated_at DESC
        """
    )
    fun observeByLocation(locationCode: String): Flow<List<LocationRecordEntity>>

    /** 软删除该物品在某位置的记录（数量归零 / TAKE 完 / 纠错回滚）。 */
    @Query("UPDATE location_records SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** 软删除某物品的全部位置记录。 */
    @Query(
        "UPDATE location_records SET deleted_at = :now, updated_at = :now WHERE item_id = :itemId AND deleted_at IS NULL"
    )
    suspend fun softDeleteByItem(itemId: String, now: Long)

    /** 取此物品已有软删时间的最大值，以便为本次整件删除生成唯一标记。 */
    @Query("SELECT MAX(deleted_at) FROM location_records WHERE item_id = :itemId")
    suspend fun maxDeletedAtForItem(itemId: String): Long?

    /** 只恢复与本次物品删除同时软删的位置；取光的旧位置不能复活。 */
    @Query(
        "UPDATE location_records SET deleted_at = NULL, updated_at = :now WHERE item_id = :itemId AND deleted_at = :deletedAt"
    )
    suspend fun restoreByItem(itemId: String, deletedAt: Long, now: Long)

    @Query("SELECT * FROM location_records ORDER BY created_at ASC")
    suspend fun exportAll(): List<LocationRecordEntity>

    @Query("DELETE FROM location_records")
    suspend fun deleteAll()
}
