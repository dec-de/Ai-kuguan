package com.kuguan.app.data.repo

import com.kuguan.app.data.db.OperationType
import com.kuguan.app.domain.item.ItemAttributes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页属性修改（M3 核心）。
 *
 * 门禁要求「改名不产生重复档案」，所以最关键的一条是
 * [改成已存在的活物品名时拒绝] —— 它直接断言活档案里同名只有一个。
 * 其余锁死：旧名进别名、别名数字形态、有效期校验、updated_at 更新、
 * last_used_at 不动、位置记录不动、EDIT 只做审计。
 */
class StorageRepositoryEditTest {

    @Test
    fun `改成已存在的活物品名时拒绝且不产生重复档案`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "手电筒", locationCode = "A-07", quantity = 1.0)
        val flashlight = repo.findLiveItem("手电筒")!!

        val result = repo.editItem(
            itemId = flashlight.id,
            name = "七号电池",
            aliases = emptyList(),
            category = null,
            expiryDate = null,
        )

        assertTrue("应被拒绝，实际 $result", result is EditResult.Rejected)
        assertTrue((result as EditResult.Rejected).reason.contains("已有同名物品"))
        assertEquals(
            "活档案里同名只能有一个",
            1,
            db.itemDao.rows.values.count { it.deletedAt == null && it.name == "七号电池" },
        )
        assertEquals("手电筒", db.itemDao.findLiveById(flashlight.id)!!.name)
    }

    @Test
    fun `改成另一个物品的别名也拒绝`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(
            itemName = "膨胀栓",
            locationCode = "A-07",
            quantity = 2.0,
            aliases = listOf("墙塞"),
        )
        repo.put(itemName = "手电筒", locationCode = "A-07", quantity = 1.0)
        val flashlight = repo.findLiveItem("手电筒")!!

        val result = repo.editItem(
            itemId = flashlight.id,
            name = "墙塞",
            aliases = emptyList(),
            category = null,
            expiryDate = null,
        )

        // 否则改完之后用户说「墙塞」，resolveItem 会解析到膨胀栓 —— 详情与语音指向两个东西
        assertTrue("应被拒绝，实际 $result", result is EditResult.Rejected)
    }

    @Test
    fun `新增别名撞上另一物品名称或别名时拒绝且不写审计`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put("膨胀栓", "A-07", 2.0, aliases = listOf("墙塞"))
        repo.put("手电筒", "B-05", 1.0)
        val item = repo.findLiveItem("手电筒")!!
        val before = db.operationDao.rows.size

        for (alias in listOf("膨胀栓", "墙塞")) {
            val result = repo.editItem(item.id, "手电筒", listOf(alias), null, null)
            assertTrue("$alias 应被拒绝，实际 $result", result is EditResult.Rejected)
            assertEquals(before, db.operationDao.rows.size)
            assertEquals("[]", db.itemDao.findLiveById(item.id)!!.aliases)
        }
    }

    @Test
    fun `改名成功把旧名写进别名且旧名仍能查到`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        val item = repo.findLiveItem("七号电池")!!

        val result = repo.editItem(
            itemId = item.id,
            name = "七号碱性电池",
            aliases = emptyList(),
            category = "电池",
            expiryDate = "2028-01-31",
        )
        assertTrue("应保存成功，实际 $result", result is EditResult.Saved)
        val saved = (result as EditResult.Saved)
        assertEquals("七号电池", saved.renamedFrom)

        val stored = db.itemDao.findLiveById(item.id)!!
        assertEquals("七号碱性电池", stored.name)
        assertEquals(2.0, db.locationDao.findLiveByItem(item.id).single().quantity, 0.001)

        val aliases = ItemAttributes.decodeAliases(stored.aliases)
        assertTrue("旧名必须进别名：$aliases", "七号电池" in aliases)
        assertEquals("说旧名仍要能找到同一条档案", item.id, repo.findLiveItem("七号电池")!!.id)
    }

    @Test
    fun `别名接受七号与7号两种数字形态`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        val item = repo.findLiveItem("七号电池")!!

        repo.editItem(
            itemId = item.id,
            name = "七号电池",
            aliases = listOf("5号电池"),
            category = null,
            expiryDate = null,
        )

        // 存的是「5号电池」，说「五号电池」也要命中 —— 与 resolveItem 的变体规则一致
        assertEquals(item.id, repo.findLiveItem("五号电池")!!.id)
        assertEquals(item.id, repo.findLiveItem("5号电池")!!.id)
    }

    @Test
    fun `非法有效期拒绝落库`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "牛奶", locationCode = "A-07", quantity = 1.0)
        val item = repo.findLiveItem("牛奶")!!

        val result = repo.editItem(
            itemId = item.id,
            name = "牛奶",
            aliases = emptyList(),
            category = null,
            expiryDate = "2026-02-30",
        )

        assertTrue("应被拒绝，实际 $result", result is EditResult.Rejected)
        assertNull(db.itemDao.findLiveById(item.id)!!.expiryDate)
    }

    @Test
    fun `清空有效期是合法修改`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "牛奶", locationCode = "A-07", quantity = 1.0)
        val item = repo.findLiveItem("牛奶")!!
        repo.editItem(item.id, "牛奶", emptyList(), null, "2026-12-01")

        val result = repo.editItem(item.id, "牛奶", emptyList(), null, "")
        assertTrue(result is EditResult.Saved)
        assertNull(db.itemDao.findLiveById(item.id)!!.expiryDate)
    }

    @Test
    fun `改属性更新updatedAt但不碰lastUsedAt`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "登山杖", locationCode = "C-03", quantity = 1.0)
        repo.take(itemName = "登山杖", locationCode = "C-03", quantity = 1.0)
        val item = repo.findLiveItem("登山杖")!!
        val lastUsed = item.lastUsedAt
        assertNotNull("TAKE 之后必须有 last_used_at", lastUsed)

        // 把 updatedAt 压到一个确定的小值，才能证明这次修改真的写了时间
        db.itemDao.rows[item.id] = item.copy(updatedAt = 1L)

        val result = repo.editItem(item.id, "登山杖（折叠）", emptyList(), "户外", null)
        assertTrue(result is EditResult.Saved)
        val stored = db.itemDao.findLiveById(item.id)!!
        assertNotEquals(1L, stored.updatedAt)
        assertEquals("last_used_at 只能由 operations 派生", lastUsed, stored.lastUsedAt)
    }

    @Test
    fun `改属性不影响位置记录`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "七号电池", locationCode = "B-05", quantity = 1.0)
        val item = repo.findLiveItem("七号电池")!!

        repo.editItem(item.id, "七号电池", listOf("AA电池"), "电池", "2027-06-30")

        val locations = db.locationDao.findLiveByItem(item.id)
        assertEquals(2, locations.size)
        assertEquals(2.0, locations.first { it.locationCode == "A-07" }.quantity, 0.001)
        assertEquals(1.0, locations.first { it.locationCode == "B-05" }.quantity, 0.001)
        assertEquals(
            "位置数量不受属性修改影响",
            3.0,
            locations.sumOf { it.quantity },
            0.001,
        )
    }

    @Test
    fun `属性修改写入EDIT审计行且不占用库存字段`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        val item = repo.findLiveItem("七号电池")!!

        repo.editItem(item.id, "七号碱性电池", emptyList(), "电池", "2027-06-30")

        val edit = db.operationDao.rows.values.single { it.type == OperationType.EDIT }
        assertEquals(item.id, edit.itemId)
        assertNull("EDIT 不能有位置", edit.locationCode)
        assertNull("EDIT 不能有数量", edit.quantity)
        val text = edit.rawText.orEmpty()
        assertTrue(text, text.contains("名称：七号电池 → 七号碱性电池"))
        assertTrue(text, text.contains("类别：未设置 → 电池"))
        assertTrue(text, text.contains("有效期：未设置 → 2027-06-30"))
    }

    @Test
    fun `没有任何改动时不写EDIT`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        val item = repo.findLiveItem("七号电池")!!
        val before = db.operationDao.rows.size

        val result = repo.editItem(item.id, "七号电池", emptyList(), null, null)

        assertTrue("应为 Unchanged，实际 $result", result is EditResult.Unchanged)
        assertEquals(before, db.operationDao.rows.size)
    }

    @Test
    fun `清空类别会写进审计文案`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "扳手", locationCode = "A-07", quantity = 1.0, category = "工具")
        val item = repo.findLiveItem("扳手")!!

        repo.editItem(item.id, "扳手", emptyList(), "", null)

        assertNull(db.itemDao.findLiveById(item.id)!!.category)
        val edit = db.operationDao.rows.values.single { it.type == OperationType.EDIT }
        assertTrue(edit.rawText.orEmpty().contains("类别：工具 → 未设置"))
    }

    @Test
    fun `撤销唯一一次放入时EDIT不算物品存在的理由`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "万能胶", locationCode = "A-07", quantity = 1.0)
        val item = repo.findLiveItem("万能胶")!!
        repo.editItem(item.id, "万能胶（大）", emptyList(), null, null)

        val put = db.operationDao.rows.values.single { it.type == OperationType.PUT }
        // EDIT 不参与库存，所以「上一条库存操作」仍然是那次放入
        assertEquals(put.seq, repo.latestActiveOperation()!!.seq)
        assertNotNull(repo.revokeOperation(put.seq, RevokeScope.LatestOnly))

        assertNotNull(
            "放入撤销后档案应收进回收站（EDIT 不该把它算成有来头）",
            db.itemDao.findById(item.id)!!.deletedAt,
        )
    }

    @Test
    fun `补货阈值保存三种状态并审计非法值零写入`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        repo.put(itemName = "七号电池", locationCode = "抽屉甲", quantity = 2.0)
        val item = repo.findLiveItem("七号电池")!!
        val initialOperations = db.operationDao.rows.size

        assertTrue(repo.setReplenishmentThreshold(item.id, Double.NaN) is EditResult.Rejected)
        assertTrue(repo.setReplenishmentThreshold(item.id, -0.1) is EditResult.Rejected)
        assertEquals(initialOperations, db.operationDao.rows.size)
        assertNull(db.itemDao.findLiveById(item.id)!!.restockThreshold)

        assertTrue(repo.setReplenishmentThreshold(item.id, 1.5) is EditResult.Saved)
        assertEquals(1.5, db.itemDao.findLiveById(item.id)!!.restockThreshold!!, 0.0)
        assertTrue(repo.setReplenishmentThreshold(item.id, 0.0) is EditResult.Saved)
        assertEquals(0.0, db.itemDao.findLiveById(item.id)!!.restockThreshold!!, 0.0)
        assertTrue(repo.setReplenishmentThreshold(item.id, null) is EditResult.Saved)
        assertNull(db.itemDao.findLiveById(item.id)!!.restockThreshold)
        assertTrue(repo.setReplenishmentThreshold(item.id, null) is EditResult.Unchanged)
        assertEquals(initialOperations + 3, db.operationDao.rows.size)
        assertTrue(db.operationDao.rows.values.filter { it.type == OperationType.EDIT }
            .all { it.rawText.orEmpty().contains("补货提醒") })
    }
}
