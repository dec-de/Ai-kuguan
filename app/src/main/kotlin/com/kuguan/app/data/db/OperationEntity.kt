package com.kuguan.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 操作类型。枚举值会直接写进数据库，改名等于数据不兼容。 */
object OperationType {
    const val PUT = "PUT"
    const val TAKE = "TAKE"
    const val CORRECTION = "CORRECTION"
    const val DELETE = "DELETE"

    /**
     * 属性修改（名称 / 别名 / 类别 / 有效期，M3）。
     *
     * **只做审计，不参与库存计算**：`quantity` 与 `location_code` 恒为 null，
     * 变更内容写在 `raw_text` 里（如「名称：七号电池 → 七号电池（新）」）。
     *
     * 为什么不复用一个 `PUT`：那会让「改个名字」被库存回滚逻辑当成立了一次放入，
     * 撤销时凭空加数量。加类型而不是加「假 PUT」是唯一不污染库存的做法。
     *
     * 所有按 type 聚合的查询都必须把它排除在库存语义之外 ——
     * 详见 `OperationDao.findLatestActive` / `findRecentActive` / `countActiveForItem`
     * 以及报告里的核查点清单。
     */
    const val EDIT = "EDIT"
}

/**
 * 表 3：操作历史 —— 「发生过什么」
 *
 * ⚠️ **append-only，永不物理删除。**
 * 这是冷门提醒正确性的唯一来源（[ItemEntity.lastUsedAt] 完全由本表派生），
 * 也是「可回溯原状态」和「回收站防误删」两项功能的基础。
 * 撤销的实现是打 [revokedAt] 标记，不是 DELETE 行。
 * 代码层面禁止对本表执行任何 DELETE 语句。
 */
@Entity(
    tableName = "operations",
    indices = [
        Index(value = ["item_id"]),
        Index(value = ["created_at"]),
        // seq 是纠错回滚的顺序依据，必须全局唯一且可排序
        Index(value = ["seq"], unique = true),
        // 批量录入：同一句话拆出的多条操作共用一个 batch_id
        Index(value = ["batch_id"]),
    ],
)
data class OperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 单调递增序号。纠错/撤销按它定位「上一条」。 */
    @ColumnInfo(name = "seq")
    val seq: Long,

    /** [OperationType] 之一。 */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "item_id")
    val itemId: String? = null,

    @ColumnInfo(name = "location_code")
    val locationCode: String? = null,

    @ColumnInfo(name = "quantity")
    val quantity: Double? = null,

    /** LLM 返回的结构化 JSON 原文。审计用，便于判断是 ASR 听错还是 LLM 理解错。 */
    @ColumnInfo(name = "raw_text")
    val rawText: String? = null,

    /** ASR 原始返回文本。与 [rawText] 分开存，才能定位是哪一环出错。 */
    @ColumnInfo(name = "asr_text")
    val asrText: String? = null,

    /** CORRECTION/DELETE 指向被改动的 [seq]。 */
    @ColumnInfo(name = "target_seq")
    val targetSeq: Long? = null,

    /**
     * 同一句话拆出的多条操作共用一个批次号。**可空** = 单条录入或历史数据。
     *
     * 存续它的唯一理由：批次归属是**过去时**的信息。等积累了散录之后再补这个字段，
     * 谁也判断不出哪几条是同一次说的，历史页就永远无法按批分组、无法「撤销整批」。
     * 所以它必须在第一次写库时就落盘，哪怕现在还没有任何逻辑读它。
     *
     * 不建第四张表（严守功能表「三张表」的决策）：批次只是一句语音的副产品，
     * 不是实体，没有自己的属性。行内顺序靠已有的 [seq] 升序即可，不需要 batch_index。
     *
     * 「改一行」产生的新操作**继承原批次号** —— 否则它会从批次里掉出去，
     * 后续再撤批内其它行时就会被安全闸门误判成「外来操作」。
     */
    @ColumnInfo(name = "batch_id")
    val batchId: String? = null,

    /** 被撤销/删除的标记。非空 = 已作废，但行还在。 */
    @ColumnInfo(name = "revoked_at")
    val revokedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)