package com.kuguan.app.data.repo

import com.kuguan.app.domain.query.LookupResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反查与搜索投影的回归测试（M3 门禁）。
 *
 * 三条断言对应三条业务事实：
 * 1. 一个物品 N 个位置 → 一次报全 N 个位置与各自数量（不是只报第一个）；
 * 2. 位置全空 → 说「取走了」，而不是「库里没有这个物品」；
 * 3. 搜索投影按物品分组，位置随物品一起回来（不是 N+1）。
 */
class StorageRepositoryLookupTest {

    @Test
    fun `泛称返回所有相关档案但具体名称不反向扩展`() = runTest {
        val repo = FakeDb().repository()
        repo.put(itemName = "电池", locationCode = "A189", quantity = 1.0)
        repo.put(itemName = "7号电池", locationCode = "A-07", quantity = 4.0)
        repo.put(itemName = "7号电池", locationCode = "A-10", quantity = 2.0)

        val broad = repo.lookupCandidates(listOf("电池", "7号电池", "不存在的电池"))
            as LookupResult.Matches
        assertEquals(listOf("电池", "7号电池"), broad.items.map { it.name })
        assertEquals(listOf("A-07", "A-10"), broad.items[1].locations.map { it.locationCode })
        assertEquals(6.0, broad.items[1].total, 0.001)

        val specific = repo.lookupCandidates(listOf("七号电池")) as LookupResult.Found
        assertEquals("7号电池", specific.item.name)
    }

    @Test
    fun `完整库存上下文不截断且含位置数量与放入时间`() = runTest {
        val repo = FakeDb().repository()
        repeat(35) { index ->
            repo.put(itemName = "物品$index", locationCode = "A$index", quantity = 1.0)
        }
        val inventory = repo.inventoryForPrompt()
        assertEquals(35, inventory.size)
        assertTrue(inventory.any { it.name == "物品34" })
        val location = inventory.first { it.name == "物品34" }.locations.single()
        assertEquals("A34", location.code)
        assertEquals(1.0, location.quantity, 0.001)
        assertTrue(location.lastPutAtMs != null && location.lastPutAtMs > 0)
    }

    @Test
    fun `库存上下文保留取空的档案并排除回收站物品`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "取空电池", locationCode = "A-01", quantity = 1.0)
        repo.take(itemName = "取空电池", locationCode = "A-01", quantity = 1.0)
        repo.put(itemName = "回收站电池", locationCode = "B-02", quantity = 1.0)
        val deleted = repo.findLiveItem("回收站电池")!!
        repo.softDeleteItem(deleted.id)

        val inventory = repo.inventoryForPrompt()
        assertEquals(listOf("取空电池"), inventory.map { it.name })
        assertTrue(inventory.single().locations.isEmpty())
    }

    @Test
    fun `多位置物品一次报全全部位置与数量`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "七号电池", locationCode = "B-05", quantity = 1.0)

        val result = repo.lookupItem("七号电池")
        assertTrue("应为 Found，实际 $result", result is LookupResult.Found)
        val snapshot = (result as LookupResult.Found).item
        assertEquals(listOf("A-07", "B-05"), snapshot.locations.map { it.locationCode })
        assertEquals(listOf(2.0, 1.0), snapshot.locations.map { it.quantity })
        assertEquals(3.0, snapshot.total, 0.001)
    }

    @Test
    fun `数字形态不同的名字查到同一份完整位置`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "七号电池", locationCode = "B-05", quantity = 1.0)

        val result = repo.lookupItem("7号电池") as LookupResult.Found
        assertEquals(2, result.item.locations.size)
        assertEquals(3.0, result.item.total, 0.001)
    }

    @Test
    fun `取光后回答有档案但不在任何位置`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "登山杖", locationCode = "C-03", quantity = 1.0)
        repo.take(itemName = "登山杖", locationCode = "C-03", quantity = 1.0)

        val result = repo.lookupItem("登山杖")
        assertTrue("应为 NoLocations，实际 $result", result is LookupResult.NoLocations)
    }

    @Test
    fun `库里没有时给部分匹配的近似结果`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "五号电池", locationCode = "A-08", quantity = 2.0)
        repo.put(itemName = "登山杖", locationCode = "C-03", quantity = 1.0)

        val result = repo.lookupItem("电池") as LookupResult.NotFound
        // 单字/两字以上都走 LIKE；这里「电池」是名字的一部分
        assertEquals(2, result.suggestions.size)
        assertTrue(result.suggestions.all { it.name.contains("电池") })
    }

    @Test
    fun `近似结果最多三条`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        listOf("电池一", "电池二", "电池三", "电池四", "电池五").forEachIndexed { i, name ->
            repo.put(itemName = name, locationCode = "A-0${i + 1}", quantity = 1.0)
        }
        val result = repo.lookupItem("电池") as LookupResult.NotFound
        assertEquals(3, result.suggestions.size)
    }

    @Test
    fun `单字查询不给近似结果`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "螺丝刀", locationCode = "A-07", quantity = 1.0)

        val result = repo.lookupItem("螺") as LookupResult.NotFound
        assertEquals(emptyList<String>(), result.suggestions.map { it.name })
    }

    @Test
    fun `没给名字时返回NoName`() = runTest {
        val repo = FakeDb().repository()
        assertEquals(LookupResult.NoName, repo.lookupItem("   "))
    }

    @Test
    fun `搜索投影多位置全部返回且按物品分组`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "七号电池", locationCode = "B-05", quantity = 1.0)
        repo.put(itemName = "五号电池", locationCode = "A-08", quantity = 2.0)

        val results = repo.searchWithLocations("电池").first()
        assertEquals(2, results.size)

        val seven = results.first { it.name == "七号电池" }
        assertEquals(listOf("A-07", "B-05"), seven.locations.map { it.locationCode })
        assertEquals(listOf(2.0, 1.0), seven.locations.map { it.quantity })

        val five = results.first { it.name == "五号电池" }
        assertEquals(listOf("A-08"), five.locations.map { it.locationCode })
    }

    @Test
    fun `搜索能命中别名且位置完整`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(
            itemName = "膨胀栓",
            locationCode = "A-07",
            quantity = 4.0,
            aliases = listOf("墙塞"),
        )
        repo.put(itemName = "膨胀栓", locationCode = "B-02", quantity = 2.0)

        val results = repo.searchWithLocations("墙塞").first()
        assertEquals(1, results.size)
        assertEquals(listOf("A-07", "B-02"), results.single().locations.map { it.locationCode })
    }

    @Test
    fun `搜索数字与中文读法互通`() = runTest {
        val repo = FakeDb().repository()
        repo.put(itemName = "7号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "备用盒", locationCode = "B-05", quantity = 1.0, aliases = listOf("五号电池"))

        assertEquals(listOf("7号电池"), repo.searchWithLocations("七号").first().map { it.name })
        assertEquals(listOf("备用盒"), repo.searchWithLocations("5号").first().map { it.name })
    }

    @Test
    fun `空关键词不返回全库`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)

        assertEquals(emptyList<Any>(), repo.searchWithLocations("  ").first())
    }

    @Test
    fun `搜索包含没有位置的物品`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "万能胶", locationCode = "A-07", quantity = 1.0)
        repo.take(itemName = "万能胶", locationCode = "A-07", quantity = 1.0)

        val results = repo.searchWithLocations("万能胶").first()
        assertEquals(1, results.size)
        assertEquals(emptyList<Any>(), results.single().locations)
    }

    @Test
    fun `搜索词百分号与下划线按字面匹配`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put("100%酒精", "A-07", 1.0)
        repo.put("1000酒精", "B-05", 1.0)
        repo.put("型号_A", "C-03", 1.0)
        repo.put("型号XA", "D-04", 1.0)

        assertEquals(listOf("100%酒精"), repo.searchWithLocations("%").first().map { it.name })
        assertEquals(listOf("型号_A"), repo.searchWithLocations("_").first().map { it.name })
    }
}
