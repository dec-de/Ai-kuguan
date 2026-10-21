package com.kuguan.app.domain.item

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.serialization.json.Json

/**
 * 物品属性的纯领域规则（M3）。
 *
 * 这一层不 import `android.*`，全部是可在 JVM 单测里毫秒级验证的字符串/日期逻辑。
 * Repository 与详情页共用同一套规则 —— 界面上「看起来通过」和数据库里「真的能存」
 * 必须是同一个判断，否则非法值总会在其中一条路径上漏进去。
 */
object ItemAttributes {

    /** 有效期在库里的唯一格式（ISO，见 [com.kuguan.app.data.db.ItemEntity.expiryDate]）。 */
    const val EXPIRY_PATTERN = "yyyy-MM-dd"

    /**
     * 有效期解析结果。
     *
     * 空串是**合法**的（= 清空有效期），非法格式与不存在的日期才是 [Invalid]。
     * 把「没填」和「填错了」混成一个 null，会让用户以为清空生效了、其实是被静默拒绝。
     */
    sealed interface Expiry {
        data class Valid(val value: String?) : Expiry
        data object Invalid : Expiry
    }

    private val EXPIRY_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /**
     * 严格解析 `yyyy-MM-dd`。
     *
     * 必须带 [ResolverStyle.STRICT] + `uuuu`：默认的 SMART 会把 `2026-02-30`
     * 静默改成 `2026-02-28` —— 那是替用户改数据，和「1.5 不能变 15」同一条铁律冲突。
     */
    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    fun parseExpiry(raw: String?): Expiry {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return Expiry.Valid(null)
        if (!EXPIRY_REGEX.matches(value)) return Expiry.Invalid
        return try {
            val date = LocalDate.parse(value, DATE_FORMATTER)
            // 再比一次字符串：万一将来有人把 formatter 改成宽容模式，这里仍然拦得住
            if (date.format(DATE_FORMATTER) == value) Expiry.Valid(value) else Expiry.Invalid
        } catch (_: DateTimeParseException) {
            Expiry.Invalid
        }
    }

    /** 界面上一个输入框写多个别名时用的分隔符：中英文逗号、顿号、分号、空白都算。 */
    private val ALIAS_SEPARATORS = Regex("""[,，、;；\s]+""")

    /** 把别名输入框拆成列表（只拆分与去空白，不判断是否合法）。 */
    fun parseAliases(raw: String): List<String> =
        raw.split(ALIAS_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 归一别名列表：去空、去重、**去掉与当前名称相同的那一项**。
     *
     * 数字形态不在这里改写 —— 「七号电池」和「7号电池」都原样保存，
     * 匹配时由 [variants] 双向覆盖。理由与 `resolveItem` 一致：
     * 写进去的必须是用户这次说的形态，不能悄悄改掉他输入的字。
     */
    fun normalizeAliases(aliases: List<String>, name: String): List<String> {
        val trimmedName = name.trim()
        return aliases
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != trimmedName }
            .distinct()
    }

    /**
     * 名字的数字形态变体：`七号电池` ↔ `7号电池`。
     *
     * 只转「一位中文数字」，`十二号` 这种不转 —— 口语里几乎不说，转了反而可能撞车。
     * 查询（语音、搜索、改名去重）与别名匹配共用这一份，避免三处各写一套逐渐漂移。
     */
    fun variants(name: String): List<String> {
        val toAscii = buildString {
            name.forEach { c -> append(CN_DIGIT_TO_ASCII[c] ?: c) }
        }
        val toChinese = buildString {
            name.forEach { c -> append(ASCII_TO_CN_DIGIT[c] ?: c) }
        }
        return listOf(name, toAscii, toChinese).distinct()
    }

    /** 别名序列化为 JSON 数组字符串（严守「三张表」：不单独建别名表）。 */
    fun encodeAliases(aliases: List<String>): String = Json.encodeToString(aliases)

    /** SQL 精确查找别名时使用完整 JSON token，避免 `%`、`_` 被当通配符。 */
    fun encodedAlias(alias: String): String = Json.encodeToString(alias)

    /** 解析 `items.aliases` 的 JSON 数组（与 [encodeAliases] 对称）。 */
    fun decodeAliases(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())
    }
}

private val CN_DIGIT_TO_ASCII: Map<Char, Char> = mapOf(
    '零' to '0', '一' to '1', '二' to '2', '两' to '2', '三' to '3', '四' to '4',
    '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9',
)

private val ASCII_TO_CN_DIGIT: Map<Char, Char> = mapOf(
    '0' to '零', '1' to '一', '2' to '二', '3' to '三', '4' to '四',
    '5' to '五', '6' to '六', '7' to '七', '8' to '八', '9' to '九',
)
