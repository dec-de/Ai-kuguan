package com.kuguan.app.domain.confirm

import com.kuguan.app.domain.normalize.LocationCodeNormalizer

/**
 * 写入前的确认闸门 —— 整个 App **唯一**决定「能不能直接写库」的地方。
 *
 * ## 为什么要有这一层
 *
 * App 的核心原则是「零确认」：说完直接写库，不弹框。这大幅降低了记录成本，
 * 但也意味着**系统可能把用户没说过的话写进数据库**。
 *
 * 典型事故场景（Owen 2026-10-17 指出）：
 * 用户念「A 洞零拐」，ASR 听到三个音节 `007`。应完整记录为 `A007`，
 * 若此时自作聪明折叠成 `A-07`，就等于替用户猜了一个他没说的话。
 * 而用户**全程不知情** —— 他只会看到核对屏闪一下 `A-07`，
 * 等发现时数据早已污染了操作历史与冷门提醒的派生结果。
 *
 * 所以原则是：**能确定的直接写，不能确定的绝不猜，一律问用户。**
 * 这一层把「能确定」的判定集中到一处，避免散落在各个调用点被绕过。
 */
object ConfirmationGate {

    /** 闸门放行结果。 */
    sealed interface Decision {
        /** 无歧义，可立即写库（零确认路径）。 */
        data class Proceed(val codes: List<String>) : Decision

        /** 有歧义，**禁止写库**，必须等用户点选候选。 */
        data class AskUser(val candidates: List<String>, val heardDigits: String) : Decision

        /** 完全没听出编号，交给上层重新说或走 LLM 层。 */
        data object NoCodeFound : Decision
    }

    /**
     * 判断一次语音解析结果能否直接写库。
     *
     * @param parseResult [LocationCodeNormalizer.parse] 的输出
     */
    fun evaluate(parseResult: LocationCodeNormalizer.LocationParseResult): Decision {
        if (parseResult.isEmpty) return Decision.NoCodeFound

        // 有一处歧义就不能写：整句话的语义可能是多编号的，
        // 只写一半会产生比全错更糟的「半条记录」。
        if (parseResult.hasAmbiguity) {
            val ambiguity = parseResult.ambiguities.first()
            return Decision.AskUser(
                candidates = ambiguity.candidates,
                heardDigits = ambiguity.digits,
            )
        }

        return Decision.Proceed(parseResult.confirmed)
    }

    /**
     * 用户在候选里点选之后的结果 —— 此时才算真正可以写库。
     *
     * @param picked 用户选中的编号
     * @return 校验通过返回该编号；非法则返回 null（说明 UI 传了脏数据）
     */
    fun resolveUserChoice(picked: String): String? =
        picked.takeIf { LocationCodeNormalizer.looksLikeLocationCode(it) }
}
