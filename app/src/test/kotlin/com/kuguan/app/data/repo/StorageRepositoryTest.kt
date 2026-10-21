package com.kuguan.app.data.repo

import com.kuguan.app.data.db.OperationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository 语义的回归测试。
 *
 * 重点覆盖三条最容易写错、且错了会**静默污染数据**的规则：
 * 1. 位置级数量（决策 #3）—— 同物品多位置并存
 * 2. last_used_at 完全由 TAKE 派生（冷门提醒的唯一来源）
 * 3. 撤销是打标记而非删行（append-only 审计日志）
 *
 * 用内存 fake DAO 而非 Room in-memory：这些规则都是纯 Kotlin 逻辑，
 * 不需要数据库也能测，跑得快且不依赖 Android 环境。
 */
class StorageRepositoryTest {

    // ---------- 位置级数量 ----------

    @Test
    fun `同物品放入两个位置互不影响`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "七号电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "七号电池", locationCode = "B-05", quantity = 1.0)

        val item = db.itemDao.findLiveByName("七号电池")
        assertNotNull(item)
        val records = db.locationDao.exportAll().filter { it.itemId == item!!.id }
        assertEquals(2, records.size)
        // 每个位置各自记着自己的数量，不合并成物品总量
        assertEquals(2.0, records.first { it.locationCode == "A-07" }.quantity, 0.001)
        assertEquals(1.0, records.first { it.locationCode == "B-05" }.quantity, 0.001)
    }

    @Test
    fun `同一位置重复放入是累加而不是覆盖`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "电池", locationCode = "A-07", quantity = 2.0)
        repo.put(itemName = "电池", locationCode = "A-07", quantity = 1.0)

        val record = db.locationDao.exportAll().single()
        assertEquals(3.0, record.quantity, 0.001)
        // 唯一约束保证同一个位置只有一条记录
        assertEquals(1, db.locationDao.exportAll().size)
    }

    // ---------- last_used_at 派生 ----------

    @Test
    fun `放入不更新lastUsedAt取出才更新`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "登山杖", locationCode = "C-03")
        val afterPut = db.itemDao.findLiveByName("登山杖")!!
        assertNull("放入不应产生 last_used_at", afterPut.lastUsedAt)

        repo.take(itemName = "登山杖", locationCode = "C-03")
        val afterTake = db.itemDao.findLiveByName("登山杖")!!
        assertNotNull("取出必须更新 last_used_at", afterTake.lastUsedAt)
    }

    // ---------- 取出语义 ----------

    @Test
    fun `取光后软删除位置记录但保留物品档案`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "万能胶", locationCode = "A-07", quantity = 2.0)
        repo.take(itemName = "万能胶", locationCode = "A-07", quantity = 2.0)

        val live = db.locationDao.exportAll().filter { it.deletedAt == null }
        assertTrue("取光后该位置不应再有活记录", live.isEmpty())
        // 物品档案保留 —— 它的历史与有效期仍有价值
        assertNotNull(db.itemDao.findLiveByName("万能胶"))
    }

    @Test
    fun `只取一部分则保留剩余数量`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "牛奶", locationCode = "D-01", quantity = 3.0)
        repo.take(itemName = "牛奶", locationCode = "D-01", quantity = 1.0)

        val record = db.locationDao.exportAll().single { it.deletedAt == null }
        assertEquals(2.0, record.quantity, 0.001)
    }

    @Test
    fun `从不存在的位置取出返回null不崩`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "螺丝刀", locationCode = "A-01")
        val result = repo.take(itemName = "螺丝刀", locationCode = "Z-99")
        assertNull("位置不存在时应返回 null 而不是抛异常", result)
    }

    // ---------- 撤销（append-only） ----------

    @Test
    fun `撤销把数量回退且不删除历史行`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "胶水", locationCode = "A-07", quantity = 2.0)
        val opsBefore = db.operationDao.exportAll().size

        repo.revokeLatest()

        // 历史行仍在，只是被打了 revoked_at 标记
        assertEquals("撤销不得物理删除操作历史", opsBefore, db.operationDao.exportAll().size)
        assertNotNull(db.operationDao.exportAll().single().revokedAt)

        // 数量回退到 0 → 位置记录软删
        val live = db.locationDao.exportAll().filter { it.deletedAt == null }
        assertTrue("撤销 PUT 后位置记录应被回退", live.isEmpty())
    }

    @Test
    fun `revokeLatest同样不受时间窗口限制`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "钉子", locationCode = "A-07")
        // 把唯一一条操作的时间推到 10 分钟前
        db.operationDao.forceAgeLatestOperation(10 * 60 * 1000L)

        // 决策 #7 的 5 分钟窗口已作废，这个入口原本会在这里返回 null
        assertNotNull(repo.revokeLatest())
        assertNotNull(db.operationDao.exportAll().single().revokedAt)
    }

    // ---------- 撤销指名道姓（UI 的「撤销刚才这条」）----------

    @Test
    fun `撤销取光的操作会把数量和位置记录都恢复`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "胶水", locationCode = "A-07", quantity = 2.0)
        repo.take(itemName = "胶水", locationCode = "A-07", quantity = 2.0)
        assertTrue(
            "取光后位置记录应已软删",
            db.locationDao.exportAll().none { it.deletedAt == null },
        )

        val take = db.operationDao.exportAll().maxBy { it.seq }
        assertNotNull("撤销取光的操作必须成功", repo.revokeOperation(take.seq))

        val record = db.locationDao.exportAll().single()
        assertNull("撤销取光后位置记录必须重新回到在架状态", record.deletedAt)
        assertEquals(2.0, record.quantity, 0.001)
        // 物品档案不能因为这次撤销被当成孤儿删掉 —— 它还有那条 PUT 的历史
        assertNotNull(db.itemDao.findLiveByName("胶水"))
    }

    @Test
    fun `超量取出后撤销应还原到取出前的数量`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "螺丝", locationCode = "A-07", quantity = 2.0)
        // 库存只有 2 却取了 3 —— take 不阻断，但撤销不能凭空变出库存
        repo.take(itemName = "螺丝", locationCode = "A-07", quantity = 3.0)

        val take = db.operationDao.exportAll().maxBy { it.seq }
        assertNotNull(repo.revokeOperation(take.seq))

        val record = db.locationDao.exportAll().single()
        assertNull(record.deletedAt)
        assertEquals("撤销后应是取出前的 2 个，不是 0+3=3", 2.0, record.quantity, 0.001)
    }

    @Test
    fun `撤销指定seq若已不是最新一条则拒绝`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val first = repo.put(itemName = "甲", locationCode = "A-01")
        repo.put(itemName = "乙", locationCode = "A-02")

        // 屏幕上显示的是「甲」，但期间又写了「乙」——
        // 此时撤「甲」会撤成「乙」，界面却显示甲已撤销
        assertNull("不是最新一条时不得撤销", repo.revokeOperation(first.seq))
        assertNull(db.operationDao.findBySeq(first.seq)!!.revokedAt)
        assertNull(db.operationDao.findBySeq(2L)!!.revokedAt)
    }

    @Test
    fun `同一条不能被撤销两次`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val op = repo.put(itemName = "丙", locationCode = "A-03")
        assertNotNull(repo.revokeOperation(op.seq))
        assertNull("已撤销的操作再撤一次必须返回 null", repo.revokeOperation(op.seq))
    }

    @Test
    fun `撤销一条凭空建出物品的放入会把物品一并收回`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        // ASR 把「登山杖」听成了「登山仗」—— 正是这套兜底机制要处理的场景
        val op = repo.put(itemName = "登山仗", locationCode = "A-07")
        assertNotNull(db.itemDao.findLiveByName("登山仗"))

        assertNotNull(repo.revokeOperation(op.seq))

        assertNull(
            "撤销后不该留一个谁也没放过的空物品（否则首页计数不回退）",
            db.itemDao.findLiveByName("登山仗"),
        )
        assertEquals(0, db.itemDao.countLive())
        // 但历史必须留着 —— 这是 append-only 的底线
        assertNotNull(db.operationDao.findBySeq(op.seq))
    }

    @Test
    fun `撤销放入不会误删有历史的物品档案`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "电池", locationCode = "A-07", quantity = 2.0)
        val second = repo.put(itemName = "电池", locationCode = "B-05", quantity = 1.0)

        assertNotNull(repo.revokeOperation(second.seq))

        // 物品有两条活跃操作，撤销其一后它还在 A-07 有 2 节 —— 不能当孤儿删掉
        assertNotNull(db.itemDao.findLiveByName("电池"))
        val live = db.locationDao.exportAll().filter { it.deletedAt == null }
        assertEquals(1, live.size)
        assertEquals("A-07", live.single().locationCode)
    }

    @Test
    fun `撤销时物品有过往操作即使零在架位置也保留`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "螺丝", locationCode = "A-07", quantity = 1.0)
        val take = repo.take(itemName = "螺丝", locationCode = "A-07", quantity = 1.0)
        assertNotNull("取出应成功", take)

        // 此刻物品零在架位置，但它有两条历史 —— 撤销 TAKE 不该把它当孤儿
        assertNotNull(repo.revokeOperation(take!!.seq))
        assertNotNull(
            "「取光后保留物品档案」是既有语义，撤销不得破坏它",
            db.itemDao.findLiveByName("螺丝"),
        )
    }

    @Test
    fun `撤销没有时间窗口十分钟前写的也能撤`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        // 决策 #7 的 5 分钟窗口已于 2026-10-17 作废：
        // 「页面还在就能撤」，发现错误的时间不该被一个计时器截断
        val op = repo.put(itemName = "钉子", locationCode = "A-07")
        db.operationDao.forceAgeOperation(op.seq, 60 * 60 * 1000L)

        assertNotNull("一小时前的记录也必须能撤", repo.revokeOperation(op.seq))
        assertNotNull(db.operationDao.findBySeq(op.seq)!!.revokedAt)
    }

    @Test
    fun `历史页可撤销中间一条而不影响同物品其他位置`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "电池", locationCode = "A-07", quantity = 2.0)
        val middle = repo.put(itemName = "电池", locationCode = "B-05", quantity = 1.0)
        repo.put(itemName = "电池", locationCode = "C-03", quantity = 1.0)

        val result = repo.revokeFromHistory(middle.seq)
        assertTrue(result is HistoryRevokeResult.Revoked)
        assertNotNull(db.operationDao.findBySeq(middle.seq)!!.revokedAt)
        assertNull(db.operationDao.findBySeq(3L)!!.revokedAt)
        assertEquals(2.0, db.locationDao.findLive(middle.itemId!!, "A-07")!!.quantity, 0.001)
        assertNull(db.locationDao.findLive(middle.itemId!!, "B-05"))
        assertEquals(1.0, db.locationDao.findLive(middle.itemId!!, "C-03")!!.quantity, 0.001)
    }

    @Test
    fun `操作序号单调递增`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val seqs = listOf(
            repo.put(itemName = "物品A", locationCode = "A-01").seq,
            repo.put(itemName = "物品B", locationCode = "A-02").seq,
            repo.put(itemName = "物品C", locationCode = "A-03").seq,
        )
        assertEquals(listOf(1L, 2L, 3L), seqs)
        assertEquals(3L, db.operationDao.maxSeq())
    }

    @Test
    fun `操作记录同时保存asr与llm原文便于定位错误环节`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(
            itemName = "七号电池",
            locationCode = "A-07",
            asrText = "把 A 洞零拐 里的 七号电池 两节 放进去",
            rawText = """{"intent":"put","location":"A-07"}""",
        )

        val op = db.operationDao.exportAll().single()
        assertEquals(OperationType.PUT, op.type)
        assertTrue(op.asrText!!.contains("洞零拐"))
        assertTrue(op.rawText!!.contains("\"intent\":\"put\""))
    }

    // ---------- 回收站 ----------

    @Test
    fun `软删除物品保留操作历史`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        repo.put(itemName = "旧物", locationCode = "A-01")
        val item = db.itemDao.findLiveByName("旧物")!!
        val opsBefore = db.operationDao.exportAll().size

        repo.softDeleteItem(item.id)

        assertNull(db.itemDao.findLiveById(item.id))
        // 历史必须留着：否则 last_used_at 丢失，冷门提醒失效（风险 R7）
        assertEquals(opsBefore, db.operationDao.exportAll().size)

        repo.restoreItem(item.id)
        assertNotNull("应能从回收站恢复", db.itemDao.findLiveById(item.id))
    }
}
