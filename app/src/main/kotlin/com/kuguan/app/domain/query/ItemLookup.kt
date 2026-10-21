package com.kuguan.app.domain.query

/**
 * 反查（「X 在哪」）的**纯领域模型与文案**（M3）。
 *
 * 为什么查询也要有领域模型，而不是在 UI 里拼字符串：
 * 「查不到」有三种完全不同的含义（没这个物品 / 有档案但位置全空 / 名字只部分匹配），
 * 把它们混成一句「没找到」会让用户做出错误判断 —— 以为东西丢了，其实只是被取光。
 * 判定与文案放在这里，JVM 单测可以逐条锁死；UI 只负责把链接挂上去。
 *
 * 合计（[ItemSnapshot.total]）是**派生值**，不落库：位置级数量才是事实。
 */

/** 某个位置上的数量。 */
data class LocationQty(
    val locationCode: String,
    val quantity: Double,
)

/** 反查结果里的物品快照（位置已按编号排序，由数据层保证）。 */
data class ItemSnapshot(
    val id: String,
    val name: String,
    val category: String?,
    val locations: List<LocationQty>,
) {
    /** 各位置数量之和。派生值，任何地方都不该把它写回数据库。 */
    val total: Double get() = locations.sumOf { it.quantity }
}

/** 近似结果（只给用户点，不替他选）。 */
data class ItemBrief(
    val id: String,
    val name: String,
)

sealed interface LookupResult {
    /** 有档案、也有在架位置。 */
    data class Found(val item: ItemSnapshot) : LookupResult

    /** 一句话匹配多件独立档案；每件的位置仍由本地数据库提供。 */
    data class Matches(val items: List<ItemSnapshot>) : LookupResult

    /** 有档案，但当前没有任何在架位置（全被取光 / 位置记录被撤）。 */
    data class NoLocations(val item: ItemSnapshot) : LookupResult

    /** 库里没有这个物品；[suggestions] 是名字部分匹配的 0~3 条。 */
    data class NotFound(
        val query: String,
        val suggestions: List<ItemBrief>,
    ) : LookupResult

    /** 用户说了查询意图，但没给出物品名。 */
    data object NoName : LookupResult
}

/** 文案的唯一出口。UI 不再自己拼「在哪、几个」。 */
object LookupText {

    /** 数量显示成整数，除非确实带小数（避免「2.0 个」这种别扭写法）。 */
    fun quantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    fun render(result: LookupResult): String = when (result) {
        is LookupResult.Matches -> result.items.joinToString("；") { item ->
            if (item.locations.isEmpty()) {
                "「${item.name}」现在不在任何位置"
            } else {
                "「${item.name}」在 " + item.locations.joinToString("、") {
                    "${it.locationCode} · ${quantity(it.quantity)} 个"
                }
            }
        }
        is LookupResult.Found -> {
            val parts = result.item.locations.joinToString("；") {
                "${it.locationCode} · ${quantity(it.quantity)} 个"
            }
            // 合计只对多位置有意义：一个位置时「共 2 个」是废话
            val total = if (result.item.locations.size > 1) {
                "（合计 ${quantity(result.item.total)} 个）"
            } else {
                ""
            }
            "「${result.item.name}」在 $parts$total"
        }

        is LookupResult.NoLocations ->
            "「${result.item.name}」现在不在任何位置（可能已经取走了）"

        is LookupResult.NotFound ->
            if (result.suggestions.isEmpty()) {
                "库里没有「${result.query}」"
            } else {
                "库里没有「${result.query}」；最接近的有：" +
                    result.suggestions.joinToString("、") { it.name }
            }

        LookupResult.NoName -> "要查什么？再说一次物品名"
    }
}
