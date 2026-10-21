package com.kuguan.app.domain.voice

import com.kuguan.app.domain.normalize.LocationCodeNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LLM 输出的解析与归一（L2 之后的**本地再校验**）。
 *
 * ## 为什么不能直接信任模型
 *
 * 这段代码存在的唯一理由是「模型会一本正经地编」：
 * - 把「D号」擅自补成 `D-00`；
 * - 把 `A4纸` 当成编号 `A-04`；
 * - 把「1.5米」写成 `quantity=15`；
 * - 把 `A07` 写成 `A7`、`A 07`、甚至 `A洞拐`。
 *
 * 数量仍要做合法性检查；位置只要求非空白，不能用格式规则拒绝用户自定义编号。
 *
 * ## 为什么用手工读 JsonElement 而不是 `@Serializable` DTO
 *
 * 这个工程没有 apply `kotlin.plugin.serialization`（只引了 runtime，不引编译器插件是刻意的：
 * 加插件会牵动 AGP 9 的 Kotlin 配置，而 M2 不该为了一个 DTO 动构建）。
 * 手工读反而更宽容：`quantity` 可能是数字、字符串、甚至 `true`，DTO 会直接抛异常，
 * 而这里能逐字段决定「这个值能不能用」。
 */
object IntentJson {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 模型返回、已归一但**尚未做业务校验**的结果。 */
    data class LlmIntent(
        val kind: IntentKind,
        val rows: List<PlannedRow>,
        val targetSeq: Long?,
        val note: String?,
    )

    /**
     * 解析模型输出。
     *
     * @return null 表示模型没有返回约定的 JSON；调用方应返回无写入行的失败结果。
     */
    fun parse(content: String?): LlmIntent? {
        val root = parseObject(content) ?: return null

        val rows = (root["items"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { item ->
                val code = canonicalCode(item.string("locationCode"))
                PlannedRow(
                    itemName = item.string("name")?.trim()?.takeIf { it.isNotEmpty() },
                    quantity = parseQuantity(item["quantity"]),
                    locationCode = code?.value,
                    locationSuspect = code?.suspect ?: false,
                )
            }
            // 三个字段全空的占位行不是记录，丢掉（否则核对屏会多一行空卡片）
            .filter { it.itemName != null || it.locationCode != null || it.quantity != null }

        return LlmIntent(
            kind = IntentKind.fromWire(root.string("intent")),
            rows = rows,
            targetSeq = root.long("targetSeq"),
            note = root.string("note")?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
        )
    }

    /** 从模型结果中取得位置。 */
    data class CanonicalCode(val value: String, val suspect: Boolean)

    /**
     * 保留模型给出的位置；旧一至两位编号继续兼容原有归一规则。
     *
     * - 合法形态（含 `A07`、`a-7`、`A 07`）→ 补零成两位；
     * - 三位及以上编号与自由文本 → 原值返回，可写库；
     * - 中文/军事读法（`A洞拐`）→ 走一遍 L1，能唯一确认就采用；
     * - 空 → null（没听到就是没听到）。
     */
    fun canonicalCode(raw: String?): CanonicalCode? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val compact = text.uppercase().replace(Regex("\\s+"), "").replace('－', '-').replace('—', '-')

        // 三位及以上是用户自定义编号，不套用旧两位编号的补零/加横杠规则。
        if (Regex("^[A-Z]{1,2}-?\\d{3,}$").matches(compact)) {
            return CanonicalCode(text, suspect = false)
        }

        val ascii = Regex("^([A-Z]{1,2})-?(\\d{1,2})$").find(compact)
        if (ascii != null) {
            val letter = ascii.groupValues[1]
            val digits = ascii.groupValues[2].padStart(2, '0')
            val candidate = "$letter-$digits"
            return CanonicalCode(candidate, suspect = false)
        }

        // 模型偶尔会把 ASR 的中文读法原样搬过来（A洞拐 / A零七）
        val parsed = LocationCodeNormalizer.parse(text)
        if (!parsed.hasAmbiguity && parsed.confirmed.size == 1) {
            return CanonicalCode(parsed.confirmed.single(), suspect = false)
        }

        // 用户自行定义抽屉名称；模型给出的非空原值不能因旧编号格式而被拒绝。
        return CanonicalCode(text, suspect = false)
    }

    /**
     * 数量：只接受「正整数」。
     *
     * `1.5` → null（**绝不移位成 15，也不四舍五入成 2**）；
     * `0` / 负数 → null（0 个不是一条记录）；
     * `"3"`（字符串）→ 3（中转站偶尔这么写）。
     */
    fun parseQuantity(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString && primitive.content.isBlank()) return null
        val number = primitive.content.trim().toDoubleOrNull() ?: return null
        if (!number.isFinite()) return null
        if (number <= 0.0) return null
        if (number % 1.0 != 0.0) return null
        return number
    }

    /** 从模型输出里抠出 JSON 对象。容忍 ```json 围栏与前后废话。 */
    fun extractObject(content: String?): String? {
        val text = content?.trim().orEmpty()
        if (text.isEmpty()) return null

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /**
     * 解析成 JsonObject，并剥掉中转站偶发的 `{"type":"json_object","content":{...}}` 外壳。
     */
    private fun parseObject(content: String?): JsonObject? {
        val body = extractObject(content) ?: return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null

        val nested = root["content"] as? JsonObject
        if (nested != null && root.containsKey("type") && !root.containsKey("intent")) {
            return nested
        }
        return root
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.trim().toLongOrNull()
    }
}
