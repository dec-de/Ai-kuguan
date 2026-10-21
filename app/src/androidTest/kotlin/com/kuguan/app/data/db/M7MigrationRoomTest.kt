package com.kuguan.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Builds an actual v1 file from the exported schema, then lets Room validate its v2 migration. */
@RunWith(AndroidJUnit4::class)
class M7MigrationRoomTest {
    private fun createSchema(name: String, version: Int, populated: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val schema = JSONObject(testContext.assets.open("com.kuguan.app.data.db.KuguanDatabase/$version.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        try {
            for (n in 0 until entities.length()) {
                val entity = entities.getJSONObject(n)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: JSONArray()
                for (i in 0 until indexes.length()) {
                    raw.execSQL(indexes.getJSONObject(i).getString("createSql")
                        .replace("${'$'}{TABLE_NAME}", table))
                }
            }
            if (populated) {
                raw.execSQL("INSERT INTO items(id,name,aliases,category,created_at,updated_at) VALUES('i1','电池','[\"七号电池\"]','日用',100,100)")
                raw.execSQL("INSERT INTO location_records(id,item_id,location_code,quantity,created_at,updated_at) VALUES('l1','i1','A-07',2,100,100)")
                raw.execSQL("INSERT INTO operations(id,seq,type,item_id,location_code,quantity,raw_text,asr_text,created_at) VALUES('o1',1,'PUT','i1','A-07',2,'原始JSON','录音原文',100)")
            }
            raw.version = version
        } finally { raw.close() }
    }

    @Test fun populatedV1MigratesWithoutLosingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "m7-populated-migration.db"
        createSchema(name, 1, true)
        val db = Room.databaseBuilder(context, KuguanDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            val items = db.itemDao().exportAll()
            val locations = db.locationDao().exportAll()
            val history = db.operationDao().exportAll()
            assertEquals(1, items.size)
            assertEquals("[\"七号电池\"]", items.single().aliases)
            assertEquals(null, items.single().restockThreshold)
            assertEquals("A-07", locations.single().locationCode)
            assertEquals(2.0, locations.single().quantity, 0.0)
            assertEquals("录音原文", history.single().asrText)
            assertEquals(null, history.single().batchId)
            val cursor = db.openHelper.readableDatabase.query("PRAGMA index_list('operations')")
            cursor.use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == "index_operations_batch_id") found = true
                assertTrue(found)
            }
            db.operationDao().append(OperationEntity("o2", 2, OperationType.PUT, "i1", "A-07", 1.0,
                batchId = "new-batch", createdAt = 101))
            assertEquals("new-batch", db.operationDao().exportAll().last().batchId)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun emptyV1MigratesAndAcceptsNewRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "m7-empty-migration.db"
        createSchema(name, 1, false)
        val db = Room.databaseBuilder(context, KuguanDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            assertTrue(db.itemDao().exportAll().isEmpty())
            db.itemDao().upsert(ItemEntity("fresh", "新物品", createdAt = 1, updatedAt = 1))
            assertNotNull(db.itemDao().exportAll().singleOrNull())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v2MigratesReminderSingletonWithoutChangingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "backup-v2-reminder-migration.db"
        createSchema(name, 2, true)
        val db = Room.databaseBuilder(context, KuguanDatabase::class.java, name)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            assertEquals(1, db.itemDao().exportAll().size)
            assertEquals(1, db.operationDao().exportAll().size)
            assertEquals(null, db.reminderSettingsDao().enabledOrNull())
            db.reminderSettingsDao().insertIfAbsent(ReminderSettingsEntity(enabled = false))
            db.reminderSettingsDao().insertIfAbsent(ReminderSettingsEntity(enabled = true))
            assertEquals(false, db.reminderSettingsDao().enabledOrNull())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v3MigratesRestockThresholdWithoutLosingInventoryOrHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "restock-v3-migration.db"
        createSchema(name, 3, true)
        val db = Room.databaseBuilder(context, KuguanDatabase::class.java, name)
            .addMigrations(MIGRATION_3_4).build()
        try {
            assertEquals(null, db.itemDao().exportAll().single().restockThreshold)
            assertEquals(2.0, db.locationDao().exportAll().single().quantity, 0.0)
            assertEquals("录音原文", db.operationDao().exportAll().single().asrText)
            val item = db.itemDao().exportAll().single()
            db.itemDao().update(item.copy(restockThreshold = 2.5))
            assertEquals(2.5, db.itemDao().findById(item.id)!!.restockThreshold!!, 0.0)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun reminderSourcesSumLiveLocationsAndRetainZeroStockItems() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            db.itemDao().upsert(ItemEntity("live", "电池", createdAt = 1, updatedAt = 1, restockThreshold = 2.5))
            db.itemDao().upsert(ItemEntity("trash", "回收站", deletedAt = 2, createdAt = 1, updatedAt = 2, restockThreshold = 0.0))
            db.locationDao().upsert(LocationRecordEntity("a", "live", "A-07", 1.0, createdAt = 1, updatedAt = 1))
            db.locationDao().upsert(LocationRecordEntity("b", "live", "B-05", 1.5, createdAt = 1, updatedAt = 1))
            db.locationDao().upsert(LocationRecordEntity("old", "live", "旧位置", 9.0, deletedAt = 2, createdAt = 1, updatedAt = 2))
            db.locationDao().upsert(LocationRecordEntity("trash-location", "trash", "C", 1.0, createdAt = 1, updatedAt = 1))

            val current = db.itemDao().reminderSources()
            assertEquals(1, current.size)
            assertEquals(2.5, current.single().quantity, 0.0)
            assertEquals(2.5, current.single().restockThreshold!!, 0.0)
            assertEquals("A-07、B-05", current.single().location)

            db.locationDao().upsert(LocationRecordEntity("a", "live", "A-07", 1.0, deletedAt = 3, createdAt = 1, updatedAt = 3))
            db.locationDao().upsert(LocationRecordEntity("b", "live", "B-05", 1.5, deletedAt = 3, createdAt = 1, updatedAt = 3))
            val empty = db.itemDao().reminderSources()
            assertEquals(1, empty.size)
            assertEquals(0.0, empty.single().quantity, 0.0)
            assertEquals("", empty.single().location)
        } finally { db.close() }
    }
}
