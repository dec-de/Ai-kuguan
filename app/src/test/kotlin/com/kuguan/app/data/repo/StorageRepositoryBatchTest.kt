package com.kuguan.app.data.repo

import com.kuguan.app.data.db.OperationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量录入的回归测试。
 *
 * 锁死的是四条一旦错了会**静默**出错的规则：
 * 1. 同批各行共用 batch_id（否则以后永远无法按批撤销）
 * 2. 整批原子（半批记录用户发现不了）
 * 3. 批内兄弟可撤、批外后续操作拦下（撤销安全闸门）
 * 4. 改一行 = 撤销旧行 + 追加新行，新行继承批次号（append-only 不被破坏）
 */
class StorageRepositoryBatchTest {

    private fun batch(
        vararg specs: Triple<String, String, Double>,
    ) = specs.map { (name, code, qty) -> BatchRow(itemName = name, locationCode = code, quantity = qty) }

    // ---------- 批次归属 ----------

    @Test
    fun `批量放入的各行共用同一个batchId且seq递增`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(
            batch(
                Triple("七号电池", "A-07", 2.0),
                Triple("五号电池", "A-07", 3.0),
                Triple("手电筒", "A-07", 1.0),
            )
        )
        assertNotNull(ops)
        assertEquals(3, ops!!.size)
        assertEquals(listOf(1L, 2L, 3L), ops.map { it.seq })

        val ids = ops.map { it.batchId }
        assertNotNull("每一行都必须有 batchId", ids[0])
        assertEquals("同批各行必须共用批次号", 1, ids.distinct().size)

        // 数量按位置累加：三样东西都进了 A-07
        assertEquals(3, db.locationDao.exportAll().count { it.locationCode == "A-07" })
    }

    @Test
    fun `单条录入没有batchId且行为不变`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val op = repo.put(itemName = "电池", locationCode = "A-07", quantity = 2.0)
        assertNull("单条录入不应有批次号", op.batchId)

        // 批内撤销的口径用在单条上时，等价于「必须是最新一条」
        assertNotNull(repo.revokeOperation(op.seq, RevokeScope.BatchSibling))
    }

    @Test
    fun `自定义抽屉编号按原值入库`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val first = repo.put(itemName = "电池", locationCode = "A113", quantity = 2.0)
        val second = repo.put(itemName = "胶带", locationCode = "左边工具柜", quantity = 1.0)
        assertEquals("A113", first.locationCode)
        assertEquals("左边工具柜", second.locationCode)
        assertEquals(setOf("A113", "左边工具柜"), db.locationDao.exportAll().map { it.locationCode }.toSet())
    }

    @Test
    fun `空批次和含非法行的批次一条都不写`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        assertNull("空批次应返回 null", repo.putBatch(emptyList()))

        assertNull(
            "物品名为空应整批拒绝",
            repo.putBatch(batch(Triple("电池", "A-07", 1.0), Triple("", "A-07", 1.0))),
        )
        assertNull(
            "数量为零应整批拒绝",
            repo.putBatch(batch(Triple("电池", "A-07", 1.0), Triple("胶带", "A-07", 0.0))),
        )
        assertNull(
            "数量超上限应整批拒绝",
            repo.putBatch(batch(Triple("电池", "A-07", 1.0), Triple("螺丝", "A-07", 1000.0))),
        )
        assertNull(
            "编号为空应整批拒绝",
            repo.putBatch(batch(Triple("电池", "A-07", 1.0), Triple("胶带", "   ", 1.0))),
        )

        assertEquals("非法批次绝不能留下任何痕迹", 0, db.operationDao.exportAll().size)
        assertEquals(0, db.itemDao.countLive())
    }

    // ---------- 批内撤销：兄弟可撤 ----------

    @Test
    fun `批量写入后可以撤批内任意一行而不必是最新那条`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(
            batch(
                Triple("七号电池", "A-07", 2.0),
                Triple("五号电池", "A-07", 3.0),
                Triple("手电筒", "A-07", 1.0),
            )
        )!!

        // 这是批量场景最核心的一条：requireLatest=true 时第 1 条根本撤不掉，
        // 而界面照样显示「已撤销」—— 界面与数据不一致且无人报错。
        assertNotNull(
            "批内非最新的一条也必须能撤",
            repo.revokeOperation(ops[0].seq, RevokeScope.BatchSibling),
        )
        assertNotNull(repo.revokeOperation(ops[2].seq, RevokeScope.BatchSibling))

        // 只有被撤的两条打了标记，中间那条没被牵连
        assertNotNull(db.operationDao.findBySeq(ops[0].seq)!!.revokedAt)
        assertNull("未被指名的行不得受影响", db.operationDao.findBySeq(ops[1].seq)!!.revokedAt)
        assertNotNull(db.operationDao.findBySeq(ops[2].seq)!!.revokedAt)

        val live = db.locationDao.exportAll().filter { it.deletedAt == null }
        assertEquals("只剩五号电池还在架", 1, live.size)
        assertEquals(3.0, live.single().quantity, 0.001)
    }

    @Test
    fun `同一物品同位置的两条批内记录撤销顺序不影响结果`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        // LLM 没合并「两节」和「三节」，拆成了两行同物品同位置 —— 纯累加，可交换
        val ops = repo.putBatch(
            batch(Triple("电池", "A-07", 2.0), Triple("电池", "A-07", 3.0))
        )!!
        assertEquals(5.0, db.locationDao.exportAll().single().quantity, 0.001)

        assertNotNull(repo.revokeOperation(ops[0].seq, RevokeScope.BatchSibling))
        assertEquals("先撤「放 2」应剩 3", 3.0, db.locationDao.exportAll().single().quantity, 0.001)
    }

    // ---------- 安全闸门：批外后续操作必须拦下 ----------

    @Test
    fun `批后有取出时不得再撤批内对应的放入`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(
            batch(Triple("电池", "A-07", 5.0), Triple("胶带", "A-07", 2.0))
        )!!
        repo.take(itemName = "电池", locationCode = "A-07", quantity = 2.0)

        assertNull(
            "那条取出已经消耗了库存，撤掉来源会让账目失真",
            repo.revokeOperation(ops[0].seq, RevokeScope.BatchSibling),
        )
        assertNull("被拒绝时不得打任何标记", db.operationDao.findBySeq(ops[0].seq)!!.revokedAt)

        // 但没被取出过的那一行仍然可撤 —— 闸门是按 (物品, 位置) 判的，不是整批一刀切
        assertNotNull(repo.revokeOperation(ops[1].seq, RevokeScope.BatchSibling))
    }

    @Test
    fun `整批撤销时若有批外后续操作则一条都不撤`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(
            batch(Triple("电池", "A-07", 5.0), Triple("胶带", "A-07", 2.0))
        )!!
        repo.take(itemName = "电池", locationCode = "A-07", quantity = 2.0)

        assertNull("半批撤销是最难解释的状态，宁可一条都不撤", repo.revokeBatch(ops[0].batchId!!))
        assertTrue(
            "整批被拒后任何一条都不得打标记",
            db.operationDao.exportAll().none { it.revokedAt != null },
        )
    }

    // ---------- 整批撤销 ----------

    @Test
    fun `整批撤销把全部行回滚且不留孤儿物品`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(
            batch(
                Triple("七号电池", "A-07", 2.0),
                Triple("五号电池", "A-07", 3.0),
                Triple("手电筒", "A-07", 1.0),
            )
        )!!
        assertEquals(3, db.itemDao.countLive())

        val revoked = repo.revokeBatch(ops[0].batchId!!)
        assertNotNull(revoked)
        assertEquals(3, revoked!!.size)
        assertEquals(
            "从最新往回撤，返回的顺序应是 seq 降序",
            listOf(3L, 2L, 1L),
            revoked.map { it.seq },
        )

        assertTrue("整批撤销后不该有任何在架位置", db.locationDao.exportAll().none { it.deletedAt == null })
        assertEquals("凭空建出的物品必须一并回收", 0, db.itemDao.countLive())
        // append-only 的底线：历史行一条都不许少
        assertEquals(3, db.operationDao.exportAll().size)
        assertTrue(db.operationDao.exportAll().all { it.revokedAt != null })
    }

    @Test
    fun `撤销不存在的批次返回null`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        assertNull(repo.revokeBatch("不存在的批次"))
    }

    // ---------- 改一行：撤销 + 重写 ----------

    @Test
    fun `改数量是撤销旧行加写新行且新行继承批次号`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(
            batch(Triple("电池", "A-07", 2.0), Triple("胶带", "A-07", 3.0))
        )!!
        val target = ops[0]

        val fixed = repo.restateRow(target.seq, itemName = "电池", locationCode = "A-07", quantity = 5.0)
        assertNotNull(fixed)

        assertNotNull("旧行必须打撤销标记而不是被改写", db.operationDao.findBySeq(target.seq)!!.revokedAt)
        assertNotEquals("新行必须是新的一条", target.seq, fixed!!.seq)
        assertEquals("新行必须留在原批次里", target.batchId, fixed.batchId)
        assertEquals(OperationType.PUT, fixed.type)

        // 数量：2 被撤回，写入 5
        assertEquals(5.0, db.locationDao.exportAll().single { it.itemId == fixed.itemId }.quantity, 0.001)
        // 另一行没被牵连
        assertEquals(3.0, db.locationDao.exportAll().single { it.locationCode == "A-07" && it.itemId == ops[1].itemId }.quantity, 0.001)
    }

    @Test
    fun `改物品名会把听错的那个物品收走`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        // ASR 把「登山杖」听成「登山仗」—— 批量里只错这一行，不该牵动其它行
        val ops = repo.putBatch(
            batch(Triple("登山仗", "A-07", 1.0), Triple("电池", "A-07", 2.0))
        )!!

        val fixed = repo.restateRow(ops[0].seq, itemName = "登山杖", locationCode = "A-07", quantity = 1.0)
        assertNotNull(fixed)

        assertNull("听错的物品名不该留在库里", db.itemDao.findLiveByName("登山仗"))
        assertNotNull(db.itemDao.findLiveByName("登山杖"))
        assertNotNull("没改的那行必须原样在", db.itemDao.findLiveByName("电池"))
    }

    @Test
    fun `改一行遇到非法入参时原记录纹丝不动`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(batch(Triple("电池", "A-07", 2.0)))!!
        val target = ops[0]

        assertNull(repo.restateRow(target.seq, itemName = "", locationCode = "A-07", quantity = 2.0))
        assertNull(repo.restateRow(target.seq, itemName = "电池", locationCode = "   ", quantity = 2.0))
        assertNull(repo.restateRow(target.seq, itemName = "电池", locationCode = "A-07", quantity = 0.0))
        assertNull(repo.restateRow(target.seq, itemName = "电池", locationCode = "A-07", quantity = 1000.0))

        assertNull("非法入参绝不能把原记录一起弄丢", db.operationDao.findBySeq(target.seq)!!.revokedAt)
        assertEquals(2.0, db.locationDao.exportAll().single().quantity, 0.001)
    }

    @Test
    fun `改一行也要过安全闸门`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(batch(Triple("电池", "A-07", 5.0)))!!
        repo.take(itemName = "电池", locationCode = "A-07", quantity = 2.0)

        assertNull(
            "后续已有取出，改这一行会让账目失真",
            repo.restateRow(ops[0].seq, itemName = "电池", locationCode = "A-07", quantity = 6.0),
        )
        assertNull(db.operationDao.findBySeq(ops[0].seq)!!.revokedAt)
    }

    @Test
    fun `同一行不能被改两次`() = runTest {
        val db = FakeDb()
        val repo = db.repository()

        val ops = repo.putBatch(batch(Triple("电池", "A-07", 2.0)))!!
        val first = repo.restateRow(ops[0].seq, itemName = "电池", locationCode = "A-07", quantity = 5.0)
        assertNotNull(first)
        assertNull("已撤销的行再改一次必须拒绝", repo.restateRow(ops[0].seq, itemName = "电池", locationCode = "A-07", quantity = 6.0))
    }
}
