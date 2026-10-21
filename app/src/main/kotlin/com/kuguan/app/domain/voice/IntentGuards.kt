package com.kuguan.app.domain.voice

import com.kuguan.app.domain.normalize.LocationCodeNormalizer

/**
 * 一条历史操作的最小快照 —— 只带校验需要的字段。
 *
 * 用快照而不是 `OperationEntity`：Domain 层不该认识 Room 实体，
 * 而且这里要能在单测里手搓（不用起数据库）。
 */
data class OperationSnapshot(
    val seq: Long,
    val type: String,
    val itemName: String?,
    val locationCode: String?,
    val quantity: Double?,
    val revoked: Boolean,
)

/**
 * 纠错 / 删除的**二次校验**（开发计划 6.4：解析结果校验）。
 *
 * ## 为什么必须再校验一次
 *
 * `targetSeq` 是模型给的。模型会自信地指向一条不存在的记录、已撤销的记录，
 * 或者「听起来相关其实不是同一件东西」的记录。直接拿去 `revokeOperation`
 * 的后果是**用户说了一句话，库里少了一条他没打算删的数据**。
 *
 * 所以所有校验都返回「给用户看的中文原因」，校验不过就**拒绝执行**。
 * 拒绝必须带原因 —— 「点了却什么都没发生」是这类交互最伤人的失败方式。
 */
object IntentGuards {

    /** 与 `com.kuguan.app.data.repo.MAX_QUANTITY` 同值（Domain 不 import Data，单测锁住一致性）。 */
    const val MAX_QUANTITY = 999.0

    /**
     * @return null = 通过；非 null = 给用户看的拒绝原因。
     */
    fun checkCorrect(plan: IntentPlan, target: OperationSnapshot?, rawText: String): String? {
        val seq = plan.targetSeq ?: return "不知道要改哪一条（没听到具体记录）"
        val op = target ?: return "找不到第 $seq 条记录"
        if (op.revoked) return "第 $seq 条已经撤销了，改不了"
        if (op.type != "PUT" && op.type != "TAKE") return "第 $seq 条不是放入/取出记录，改不了"

        val row = plan.rows.firstOrNull() ?: return "没听清要改成什么"
        val name = row.itemName?.takeIf { it.isNotBlank() } ?: return "没听清要改成什么物品"
        if (name.length > 30) return "物品名太长（最多 30 字）"

        val code = row.locationCode?.takeIf { it.isNotBlank() } ?: return "没听清要改到哪个编号"
        if (!LocationCodeNormalizer.looksLikeLocationCode(code)) {
            return "抽屉编号不能为空"
        }

        val quantity = row.quantity ?: return "没听清数量"
        if (quantity <= 0.0 || quantity > MAX_QUANTITY) return "数量要在 1~999 之间"

        // 「属于该 item」的判定：改名是允许的，但**用户原话里必须真的提到了旧名字**。
        // 否则就是模型指错了记录（「登山杖」被改到「螺丝刀」那条上）。
        if (!sameItemName(name, op.itemName) && !mentions(rawText, op.itemName)) {
            return "第 $seq 条是「${op.itemName ?: "未知物品"}」，和「$name」对不上"
        }
        return null
    }

    /**
     * @return null = 通过；非 null = 给用户看的拒绝原因。
     */
    fun checkDelete(plan: IntentPlan, target: OperationSnapshot?): String? {
        val seq = plan.targetSeq ?: return "不知道要删哪一条（没听到具体记录）"
        val op = target ?: return "找不到第 $seq 条记录"
        if (op.revoked) return "第 $seq 条已经撤销了，不用再删"
        if (op.type != "PUT" && op.type != "TAKE") return "第 $seq 条不是放入/取出记录，删不了"

        val claimed = plan.rows.firstOrNull()?.itemName
        if (!claimed.isNullOrBlank() && !sameItemName(claimed, op.itemName)) {
            return "第 $seq 条是「${op.itemName ?: "未知物品"}」，和「$claimed」对不上"
        }
        return null
    }

    /** 物品名等价：忽略空白与「七/7」这类数字形态差异。 */
    fun sameItemName(a: String?, b: String?): Boolean {
        val x = normalizeName(a) ?: return false
        val y = normalizeName(b) ?: return false
        return x == y
    }

    /**
     * 用户原话里是否提到了这个物品名（同样容忍数字形态差异）。
     *
     * 「不是五号电池，是七号电池」→ 提到「五号电池」→ 允许把该记录改名。
     */
    fun mentions(rawText: String, itemName: String?): Boolean {
        val name = normalizeName(itemName) ?: return false
        if (name.isEmpty()) return false
        return normalizeName(rawText)?.contains(name) == true
    }

    /** 数量显示：整数不带小数点（2.0 → "2"）。 */
    fun formatQuantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    /**
     * 「删掉上一条」里的「上一条」是**唯一指代**，不是猜 ——
     * LLM 没给 targetSeq 时才用它兜底取最近一条活跃操作。
     */
    fun refersToLatest(rawText: String): Boolean =
        LATEST_WORDS.any { rawText.contains(it) }

    private val LATEST_WORDS = listOf("上一条", "最后一条", "最后那个", "刚才", "刚刚", "这条", "这一条", "上一个")

    private fun normalizeName(raw: String?): String? =
        raw?.trim()?.replace(Regex("\\s+"), "")?.map { CN_DIGIT_TO_ASCII[it] ?: it }?.joinToString("")

    private val CN_DIGIT_TO_ASCII: Map<Char, Char> = mapOf(
        '零' to '0', '〇' to '0', '一' to '1', '二' to '2', '两' to '2', '三' to '3',
        '四' to '4', '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9',
    )
}
