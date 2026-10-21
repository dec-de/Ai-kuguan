package com.kuguan.app.domain.voice

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Prompt 组装 —— **纯字符串替换**，不读文件、不碰 Android。
 *
 * 真正的模板在 `app/src/main/assets/prompt/intent-v1.md`（单一真源）：
 * App 从这里读、`tools/m2-intent-bench.mjs` 也从这里读。
 * 代码里**不再抄第二份** —— 一旦有两份，回归脚本测的就不是线上那份了。
 *
 * 占位符：
 * - `{{TODAY}}`：今天（`yyyy-MM-dd`），给「一年保质期」这类相对时间用；
 * - `{{KNOWN_ITEMS}}`：已存在的物品名（≤30），让模型做名字归一而不是造新叫法；
 * - `{{RECENT_OPS}}`：最近 3 条未撤销操作，correct/delete 的 `targetSeq` 只能取自这里；
 * - `{{LAST_SEQ}}`：其中最新一条的 seq（示例里用，避免模型照抄一个不存在的号）。
 * - `{{INVENTORY}}`：全部在用物品及当前位置、数量、最近有效放入时间。
 */
object IntentPrompt {

    const val MAX_KNOWN_ITEMS = 30
    const val MAX_RECENT_OPS = 3

    /** 一条历史操作的 prompt 摘要。 */
    data class RecentOp(
        val seq: Long,
        val type: String,
        val itemName: String?,
        val locationCode: String?,
        val quantity: Double?,
    )

    data class InventoryLocation(
        val code: String,
        val quantity: Double,
        val lastPutAtMs: Long?,
    )

    data class InventoryItem(
        val name: String,
        val aliases: List<String>,
        val category: String?,
        val locations: List<InventoryLocation>,
    )

    data class PromptContext(
        val today: String,
        val knownItems: List<String>,
        val recentOps: List<RecentOp>,
        val inventory: List<InventoryItem> = emptyList(),
    )

    /**
     * @return 渲染好的 system prompt。模板为空时返回空串，
     *   调用方（`IntentGateway`）据此判定「prompt 没打进包」并直接降级。
     */
    fun render(template: String, context: PromptContext): String {
        if (template.isBlank()) return ""

        val items = context.knownItems
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KNOWN_ITEMS)
            .joinToString("、")
            .ifEmpty { "（空）" }

        val ops = context.recentOps
            .takeLast(MAX_RECENT_OPS)
            .joinToString("\n") { formatOp(it) }
            .ifEmpty { "（没有最近操作）" }

        val lastSeq = context.recentOps.takeLast(MAX_RECENT_OPS).lastOrNull()?.seq?.toString() ?: "null"
        val inventory = context.inventory.joinToString("\n") { item ->
            buildJsonObject {
                put("name", item.name)
                put("aliases", buildJsonArray { item.aliases.forEach { add(JsonPrimitive(it)) } })
                put("category", item.category)
                put("locations", buildJsonArray {
                    item.locations.forEach { location ->
                        add(buildJsonObject {
                            put("code", location.code)
                            put("quantity", location.quantity)
                            put("lastPutAtUtc", location.lastPutAtMs?.let {
                                Instant.ofEpochMilli(it).toString()
                            })
                        })
                    }
                })
            }.toString()
        }.ifEmpty { "（没有在用物品）" }

        return template
            .replace("{{TODAY}}", context.today)
            .replace("{{KNOWN_ITEMS}}", items)
            .replace("{{RECENT_OPS}}", ops)
            .replace("{{LAST_SEQ}}", lastSeq)
            .replace("{{INVENTORY}}", inventory)
    }

    /** `seq=103 PUT 物品=五号电池 位置=A-07 数量=2` */
    fun formatOp(op: RecentOp): String = buildString {
        append("seq=").append(op.seq)
        append(' ').append(op.type)
        append(" 物品=").append(op.itemName ?: "-")
        append(" 位置=").append(op.locationCode ?: "-")
        append(" 数量=").append(op.quantity?.let { IntentGuards.formatQuantity(it) } ?: "-")
    }
}
