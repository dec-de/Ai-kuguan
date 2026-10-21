package com.kuguan.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.domain.reminder.ReminderKind
import com.kuguan.app.domain.reminder.ReminderRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class M5ReminderRoomTest {
    @Test fun liveItemsIncludeZeroStockAndAggregateAcrossLocations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            fun item(id: String, deleted: Long? = null, expiry: String? = null) = ItemEntity(
                id = id, name = id, expiryDate = expiry, deletedAt = deleted, createdAt = now, updatedAt = now,
            )
            fun location(id: String, item: String, code: String, deleted: Long? = null) = LocationRecordEntity(
                id = id, itemId = item, locationCode = code, quantity = 1.0,
                deletedAt = deleted, createdAt = now, updatedAt = now,
            )
            listOf(item("live", expiry = "2026-10-17"), item("deleted", deleted = now), item("empty"))
                .forEach { db.itemDao().upsert(it) }
            listOf(location("l1", "live", "A-01"), location("l2", "live", "B-02"),
                location("l3", "deleted", "C-03"), location("l4", "empty", "D-04", deleted = now))
                .forEach { db.locationDao().upsert(it) }
            val rows = db.itemDao().reminderSources()
            assertEquals(2, rows.size)
            assertEquals("A-01、B-02", rows.single { it.id == "live" }.location)
            assertEquals(2.0, rows.single { it.id == "live" }.quantity, 0.0)
            assertEquals("", rows.single { it.id == "empty" }.location)
            assertEquals(0.0, rows.single { it.id == "empty" }.quantity, 0.0)
            val reminders = ReminderRules.evaluate(rows, LocalDate.of(2026, 10, 18), ZoneId.of("Asia/Shanghai"))
            assertEquals(1, reminders.size)
            assertEquals(ReminderKind.EXPIRED, reminders.single().kind)
            assertFalse(reminders.any { it.id != "live" })
        } finally { db.close() }
    }
}
