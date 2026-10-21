package com.kuguan.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表 2：位置记录 —— 「它在哪」
 *
 * 关键语义（决策 #3，位置级数量）：[quantity] 是**该位置上**的数量，不是物品总量。
 * 「A-07 里有 2 节电池，B-05 里还有 1 节」是正常状态，物品总量 = 各位置求和，是派生值。
 *
 * [locationCode] 是自由文本，不预录、不校验、不做「该编号是否存在」的业务判断
 * （功能汇总表核心原则 3：有位置就放）。
 */
@Entity(
    tableName = "location_records",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // 同一物品在同一位置只有一条记录，upsert 依赖这个唯一约束
        Index(value = ["item_id", "location_code"], unique = true),
        Index(value = ["location_code"]),
        Index(value = ["item_id"]),
    ],
)
data class LocationRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    /** 如 "A-07"。自由文本，不校验。 */
    @ColumnInfo(name = "location_code")
    val locationCode: String,

    /** 该位置的数量。0 视为「该位置已空」，由上层直接删记录而非存 0。 */
    @ColumnInfo(name = "quantity", defaultValue = "1.0")
    val quantity: Double = 1.0,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)