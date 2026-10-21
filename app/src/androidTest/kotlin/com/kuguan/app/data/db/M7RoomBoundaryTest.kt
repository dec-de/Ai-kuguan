package com.kuguan.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite boundaries that a fake DAO cannot validate. */
@RunWith(AndroidJUnit4::class)
class M7RoomBoundaryTest {
    private suspend fun <T> withDb(block: suspend (KuguanDatabase) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        return try { block(db) } finally { db.close() }
    }

    private suspend fun seed(db: KuguanDatabase) {
        db.itemDao().upsert(ItemEntity("i", "七号电池", "[\"电池\"]", createdAt = 1, updatedAt = 1))
        db.locationDao().upsert(LocationRecordEntity("l", "i", "A-07", 2.0, createdAt = 1, updatedAt = 1))
        db.operationDao().append(OperationEntity("o", 1, OperationType.PUT, "i", "A-07", 2.0,
            createdAt = 1))
    }

    @Test fun sameLocationUpsertKeepsOneCurrentRow() = runBlocking { withDb { db ->
        seed(db)
        db.locationDao().upsert(LocationRecordEntity("l2", "i", "A-07", 3.0, createdAt = 2, updatedAt = 2))
        assertEquals(1, db.locationDao().findLiveByItem("i").size)
        assertEquals(3.0, db.locationDao().findLive("i", "A-07")!!.quantity, 0.0)
    } }

    @Test fun seqUniqueIndexRejectsDuplicateAcrossIds() = runBlocking { withDb { db ->
        seed(db)
        assertThrows(Exception::class.java) { runBlocking {
            db.operationDao().append(OperationEntity("other", 1, OperationType.TAKE, "i", "A-07", 1.0,
                createdAt = 2))
        } }
        assertEquals(1, db.operationDao().count())
    } }

    @Test fun locationForeignKeyRejectsMissingItem() = runBlocking { withDb { db ->
        assertThrows(Exception::class.java) { runBlocking {
            db.locationDao().upsert(LocationRecordEntity("bad", "missing", "A-07", createdAt = 1, updatedAt = 1))
        } }
        assertTrue(db.locationDao().exportAll().isEmpty())
    } }

    @Test fun softDeletedLocationIsHiddenButRetained() = runBlocking { withDb { db ->
        seed(db)
        db.locationDao().softDelete("l", 9)
        assertTrue(db.locationDao().findLiveByItem("i").isEmpty())
        assertNotNull(db.locationDao().findAnyAtLocation("i", "A-07"))
        assertEquals(9L, db.locationDao().exportAll().single().deletedAt)
    } }

    @Test fun deletedItemDisappearsFromLiveSearchAndReminder() = runBlocking { withDb { db ->
        seed(db)
        db.itemDao().upsert(db.itemDao().findById("i")!!.copy(deletedAt = 9))
        assertNull(db.itemDao().findLiveByName("七号电池"))
        assertTrue(db.itemDao().reminderSources().isEmpty())
        assertTrue(db.itemDao().searchWithLocations("电池", "电池", "电池").first().isEmpty())
    } }

    @Test fun exactAliasDoesNotMatchSubstring() = runBlocking { withDb { db ->
        seed(db)
        assertNotNull(db.itemDao().findLiveByAlias("\"电池\""))
        assertNull(db.itemDao().findLiveByAlias("\"池\""))
    } }

    @Test fun revokedTakeNoLongerDefinesLastUse() = runBlocking { withDb { db ->
        seed(db)
        db.operationDao().append(OperationEntity("take", 2, OperationType.TAKE, "i", "A-07", 1.0,
            createdAt = 100))
        db.itemDao().recomputeLastUsedAt("i")
        assertEquals(100L, db.itemDao().findById("i")!!.lastUsedAt)
        db.operationDao().markRevoked(2, 200)
        db.itemDao().recomputeLastUsedAt("i")
        assertNull(db.itemDao().findById("i")!!.lastUsedAt)
    } }

    @Test fun latestActiveIgnoresEditAndRevokedInventory() = runBlocking { withDb { db ->
        seed(db)
        db.operationDao().append(OperationEntity("edit", 2, OperationType.EDIT, "i", createdAt = 2))
        db.operationDao().append(OperationEntity("take", 3, OperationType.TAKE, "i", "A-07", 1.0,
            createdAt = 3))
        db.operationDao().markRevoked(3, 4)
        assertEquals(1L, db.operationDao().findLatestActive()!!.seq)
        assertEquals(1, db.operationDao().countActiveForItem("i"))
    } }

    @Test fun historyRetainsRevokedRowsInSequenceOrder() = runBlocking { withDb { db ->
        seed(db)
        db.operationDao().append(OperationEntity("take", 2, OperationType.TAKE, "i", "A-07", 1.0,
            createdAt = 2))
        db.operationDao().markRevoked(1, 3)
        val rows = db.operationDao().exportAll()
        assertEquals(listOf(1L, 2L), rows.map { it.seq })
        assertEquals(3L, rows.first().revokedAt)
        assertFalse(rows.last().revokedAt != null)
    } }
}
