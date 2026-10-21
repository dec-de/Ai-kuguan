package com.kuguan.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.backup.BackupCodec
import com.kuguan.app.data.backup.BackupService
import com.kuguan.app.data.backup.BackupSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M6BackupRoomTest {
    @Test fun exportClearRestoreRepeatAndRollback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val service = BackupService(db)
            db.reminderSettingsDao().set(ReminderSettingsEntity(enabled = false))
            (1..10).forEach { n ->
                val id = "i$n"
                db.itemDao().upsert(ItemEntity(id, "物品$n", "[\"别名$n\"]", "工具", null,
                    if (n == 1) 100L else null, if (n == 10) 500L else null, n.toLong(), n.toLong(),
                    when (n) { 1 -> 0.0; 2 -> 2.5; else -> null }))
                db.locationDao().upsert(LocationRecordEntity("l$n", id, "A-${n.toString().padStart(2, '0')}",
                    n.toDouble(), if (n == 10) 500L else null, n.toLong(), n.toLong()))
                db.operationDao().append(OperationEntity("o$n", n.toLong(), OperationType.PUT, id,
                    "A-${n.toString().padStart(2, '0')}", n.toDouble(), "历史$n", "语音$n", null,
                    "batch", if (n == 10) 500L else null, n.toLong()))
            }
            db.operationDao().append(OperationEntity("take", 11, OperationType.TAKE, "i1", "A-01", 1.0,
                createdAt = 100))
            val before = service.snapshot()
            fun assertRows(actual: BackupSnapshot) {
                assertEquals(before.items.associateBy { it.id }, actual.items.associateBy { it.id })
                assertEquals(before.locationRecords.associateBy { it.id }, actual.locationRecords.associateBy { it.id })
                assertEquals(before.operations.associateBy { it.id }, actual.operations.associateBy { it.id })
            }
            val json = service.exportJson()
            val decoded = BackupCodec.decode(json)
            assertEquals(false, decoded.reminderSettings?.enabled)
            assertEquals(4, decoded.schemaVersion)
            assertEquals(0.0, decoded.items.first { it.id == "i1" }.restockThreshold!!, 0.0)
            assertEquals(2.5, decoded.items.first { it.id == "i2" }.restockThreshold!!, 0.0)
            assertRows(decoded)
            db.backupRestoreDao().clearOperations()
            db.locationDao().deleteAll()
            db.itemDao().deleteAll()
            service.restore(decoded)
            assertRows(service.snapshot())
            assertEquals(false, db.reminderSettingsDao().enabledOrNull())
            service.restore(decoded)
            assertRows(service.snapshot())
            assertEquals(false, db.reminderSettingsDao().enabledOrNull())
            assertEquals(10, service.currentCounts().items)
            assertEquals(10, service.currentCounts().locations)
            assertEquals(11, service.currentCounts().operations)

            // SQLite 触发器在清库后的重新插入阶段制造失败，验证整个事务回滚。
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_restore BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT, 'test failure'); END"
            )
            assertThrows(IllegalArgumentException::class.java) {
                BackupCodec.validate(decoded.copy(operations = decoded.operations + decoded.operations.first()))
            }
            assertThrows(Exception::class.java) { runBlocking { service.restore(decoded) } }
            assertRows(service.snapshot())
            assertEquals(false, db.reminderSettingsDao().enabledOrNull())
            val staged = service.stageImport(json.byteInputStream(), context.cacheDir)
            try {
                assertEquals(10, staged.preview.items)
                assertThrows(Exception::class.java) { runBlocking { service.restoreStaged(staged) } }
                assertRows(service.snapshot())
                assertEquals(false, db.reminderSettingsDao().enabledOrNull())
            } finally { staged.discard() }
        } finally { db.close() }
    }

    @Test fun v2RestorePreservesCurrentReminderSwitch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            db.reminderSettingsDao().set(ReminderSettingsEntity(enabled = false))
            val old = BackupCodec.decode("""{"schemaVersion":2,"items":[],"locationRecords":[],"operations":[]}""")
            BackupService(db).restore(old)
            assertEquals(false, db.reminderSettingsDao().enabledOrNull())
        } finally { db.close() }
    }

    @Test fun stagedPreviewCancelAndCommitDoNotRetainSnapshotLists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val service = BackupService(db)
            db.reminderSettingsDao().set(ReminderSettingsEntity(enabled = true))
            val old = """{"operations":[],"locationRecords":[],"items":[],"schemaVersion":2}"""
            val cancelled = service.stageImport(old.byteInputStream(), context.cacheDir)
            assertEquals(0, cancelled.preview.items)
            cancelled.discard()
            assertEquals(false, cancelled.file.exists())
            assertEquals(true, db.reminderSettingsDao().enabledOrNull())
            val staged = service.stageImport(old.byteInputStream(), context.cacheDir)
            service.restoreStaged(staged)
            assertEquals(false, staged.file.exists())
            assertEquals(true, db.reminderSettingsDao().enabledOrNull())
        } finally { db.close() }
    }
}
