package com.kuguan.app.domain.voice

/**
 * LLM 意图抽取的**纯领域模型**（M2）。
 *
 * 这一层刻意不 import `okhttp` / `android.*`：
 * - LLM 返回的 JSON 可能千奇百怪（多字段、数字写成字符串、编号写成 `A07`），
 *   归一化与校验是最容易出 bug 的部分，必须在 JVM 单测里秒级覆盖；
 * - 网络层（[com.kuguan.app.data.llm.DeepSeekLlmClient]）只负责把字节搬回来，
 *   它不解释内容，也就没有机会悄悄改坏一条记录。
 *
 * 模型无法给出有效计划时，只返回 UNKNOWN，不从原话猜测写入行。
 */

/** 六个意图。字符串值与 prompt 里的枚举逐字一致（小写）。 */
enum class IntentKind {
    PUT,
    TAKE,
    QUERY,
    CORRECT,
    DELETE,
    UNKNOWN;

    companion object {
        fun fromWire(raw: String?): IntentKind = when (raw?.trim()?.lowercase()) {
            "put", "place", "add" -> PUT
            "take", "remove" -> TAKE
            "query", "find", "search" -> QUERY
            "correct", "fix", "update" -> CORRECT
            "delete", "revoke", "undo" -> DELETE
            else -> UNKNOWN
        }
    }
}

/** 这一份解析结果的状态。只用于诊断，不参与业务规则。 */
enum class IntentSource {
    /** LLM 结果被采用 */
    LLM,

    /** 尚未获得有效模型结果，不包含待写入行。 */
    UNRESOLVED,
}

/**
 * 计划写入（或展示）的一行。
 *
 * @param locationCode 已归一化的编号（`A-07`）。**归一化失败时保留用户说的原值**，
 *   同时把 [locationSuspect] 置 true —— 让用户看见「听到了 D-00」并自己改，
 *   比悄悄置空更好：置空后用户不知道 App 到底听到了什么。
 * @param quantity 没听到就是 null。**绝不补 1**。
 */
data class PlannedRow(
    val itemName: String?,
    val quantity: Double?,
    val locationCode: String?,
    val locationSuspect: Boolean = false,
)

/**
 * 一句话解析出来的完整计划。
 *
 * @param targetSeq 只对 CORRECT / DELETE 有意义，必须是「最近操作」里真实存在过的 seq；
 *   二次校验在 [IntentGuards] 里做，这里只是 LLM 的说法。
 */
data class IntentPlan(
    val kind: IntentKind,
    val rows: List<PlannedRow> = emptyList(),
    val targetSeq: Long? = null,
    val note: String? = null,
    val source: IntentSource,
) {
    companion object {
        fun unknown(note: String?, source: IntentSource = IntentSource.UNRESOLVED) =
            IntentPlan(IntentKind.UNKNOWN, emptyList(), null, note, source)
    }
}
