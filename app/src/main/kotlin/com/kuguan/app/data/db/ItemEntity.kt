package com.kuguan.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表 1：物品档案 —— 「这个东西是什么」
 *
 * 设计约束（来自功能汇总表「不做同类归拢」+「无位置登记」）：
 * - [category] 是自由文本，不是受控枚举，不建分类体系。
 * - [aliases] 是多值字段，为了严守「三张表」而序列化成 JSON 数组存本列，不单独建表。
 * - [expiryDate] 一律存 ISO `yyyy-MM-dd`；相对表述由 LLM 层按注入的 today 换算后再落库。
 */
@Entity(
    tableName = "items",
    indices = [
        Index(value = ["name"]),
        Index(value = ["deleted_at"]),
    ],
)
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** JSON 数组字符串，如 `["膨胀螺丝","墙塞"]`。无别名时为 `"[]"`。 */
    @ColumnInfo(name = "aliases", defaultValue = "[]")
    val aliases: String = "[]",

    @ColumnInfo(name = "category")
    val category: String? = null,

    /** ISO `yyyy-MM-dd`。null = 未知有效期，不是「永不过期」。 */
    @ColumnInfo(name = "expiry_date")
    val expiryDate: String? = null,

    /**
     * 上次取用时间，**完全由 operations 表派生**，任何地方都不允许手工赋值。
     * null = 从未取用过（按决策 #9，这类物品进冷门提醒）。
     */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,

    /** 软删除标记。非空 = 已进回收站。 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /** null = 关闭；0 = 只在用完时提醒；正数 = 安全库存阈值。 */
    @ColumnInfo(name = "restock_threshold")
    val restockThreshold: Double? = null,
)
