package com.kuguan.app.domain.voice

/** 将已校验的模型结果映射为计划；不从 ASR 原文提取或补全字段。 */
object IntentResolver {
    fun resolve(llm: IntentJson.LlmIntent?): IntentPlan {
        if (llm == null || llm.kind == IntentKind.UNKNOWN) return IntentPlan.unknown(llm?.note)

        // 模型没有拆出行时，不产生待写入记录。
        if ((llm.kind == IntentKind.PUT || llm.kind == IntentKind.TAKE) && llm.rows.isEmpty()) {
            return IntentPlan.unknown("模型未给出物品，请再说一次")
        }

        return IntentPlan(
            kind = llm.kind,
            rows = llm.rows,
            targetSeq = llm.targetSeq,
            note = llm.note,
            source = IntentSource.LLM,
        )
    }
}
