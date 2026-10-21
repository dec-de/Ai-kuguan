package com.kuguan.app.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeResultRepositorySafetyTest {
    @Test fun restoredRowGateRejectsConflictingLaterStockOperation() = runTest {
        val repo = FakeDb().repository()
        val first = repo.put("电池", "自由编号", 5.0)
        assertTrue(repo.canRevokeForHome(first.seq))
        repo.take("电池", "自由编号", 2.0)
        assertFalse(repo.canRevokeForHome(first.seq))
        assertFalse(repo.canRevokeForHome(-1L))
    }

    @Test fun revokedRowCanNeverExposeHomeActionAgain() = runTest {
        val repo = FakeDb().repository()
        val op = repo.put("电池", "自由编号", 1.0)
        assertTrue(repo.canRevokeForHome(op.seq))
        repo.revokeOperation(op.seq, RevokeScope.BatchSibling)
        assertFalse(repo.canRevokeForHome(op.seq))
    }
}
