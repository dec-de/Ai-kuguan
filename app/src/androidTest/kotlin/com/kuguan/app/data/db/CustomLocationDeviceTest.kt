package com.kuguan.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.domain.voice.IntentJson
import com.kuguan.app.domain.voice.IntentRowPolicy
import com.kuguan.app.domain.voice.PlannedRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an in-memory database, so it never changes the phone's inventory. */
@RunWith(AndroidJUnit4::class)
class CustomLocationDeviceTest {
    @Test fun a113IsParsedAcceptedAndStoredUnchanged() = runBlocking {
        val code = IntentJson.canonicalCode("A113")!!
        assertEquals("A113", code.value)
        assertTrue(IntentRowPolicy.isWritable(PlannedRow("电池", 2.0, code.value, code.suspect)))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            db.itemDao().upsert(ItemEntity("custom-location-item", "电池", "[]", createdAt = 1, updatedAt = 1))
            db.locationDao().upsert(LocationRecordEntity(
                "custom-location-record", "custom-location-item", code.value, 2.0,
                createdAt = 1, updatedAt = 1,
            ))
            assertEquals("A113", db.locationDao().findLive("custom-location-item", "A113")?.locationCode)
        } finally {
            db.close()
        }
    }
}
