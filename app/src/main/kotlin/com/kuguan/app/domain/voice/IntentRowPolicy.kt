package com.kuguan.app.domain.voice

import com.kuguan.app.domain.normalize.LocationCodeNormalizer

/**
 * 「这一行能不能写库」的唯一判定。
 *
 * 三个要素**全部有值**才写：
 * - 物品名非空；
 * - 用户给出了非空白位置；
 * - 数量非空且 > 0。
 *
 * 缺任何一项都**不写**，只摆在核对屏上等用户填 —— 这是「绝不替用户猜」在
 * 写库前最后一道闸门：LLM 说「数量不知道」时，补 1 是最常见也最难发现的错误。
 *
 * 抽成独立对象而不是留在 Composable 里，是为了能被单测覆盖：
 * 这段逻辑决定「哪一行真的进了库」，一旦写错，界面看起来完全正常。
 */
object IntentRowPolicy {

    fun isWritable(row: PlannedRow): Boolean =
        !row.itemName.isNullOrBlank() &&
            !row.locationCode.isNullOrBlank() &&
            // 真正标为存疑的解析结果暂不写库；位数和格式本身不能成为存疑理由。
            !row.locationSuspect &&
            LocationCodeNormalizer.looksLikeLocationCode(row.locationCode) &&
            row.quantity != null &&
            row.quantity > 0.0

    /** 缺哪个字段 —— 给 UI 决定哪一格标红。 */
    fun missingFields(row: PlannedRow): Set<MissingField> = buildSet {
        if (row.itemName.isNullOrBlank()) add(MissingField.ITEM)
        val code = row.locationCode
        if (code.isNullOrBlank() ||
            row.locationSuspect ||
            !LocationCodeNormalizer.looksLikeLocationCode(code)
        ) {
            add(MissingField.LOCATION)
        }
        if (row.quantity == null) add(MissingField.QUANTITY)
    }

    enum class MissingField { ITEM, LOCATION, QUANTITY }
}
