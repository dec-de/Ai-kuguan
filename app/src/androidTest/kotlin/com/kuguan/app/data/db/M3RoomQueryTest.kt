package com.kuguan.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.domain.item.ItemAttributes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 真 SQLite 验证 JOIN、多位置、别名 token 与字面搜索。 */
@RunWith(AndroidJUnit4::class)
class M3RoomQueryTest {
    @Test
    fun searchAndAliasUseActualRoomQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            db.itemDao().upsert(ItemEntity(
                id = "one", name = "7号电池",
                aliases = ItemAttributes.encodeAliases(listOf("备用,电池", "盒%_")),
                createdAt = now, updatedAt = now,
            ))
            db.itemDao().upsert(ItemEntity(
                id = "two", name = "七号电池盒", createdAt = now, updatedAt = now,
            ))
            db.locationDao().upsert(LocationRecordEntity(
                id = "loc1", itemId = "one", locationCode = "A-07", quantity = 2.0,
                createdAt = now, updatedAt = now,
            ))
            db.locationDao().upsert(LocationRecordEntity(
                id = "loc2", itemId = "one", locationCode = "B-05", quantity = 1.0,
                createdAt = now, updatedAt = now,
            ))

            val rows = db.itemDao().searchWithLocations("电池", "电池", "电池").first()
            assertEquals(3, rows.size)
            assertEquals(listOf("A-07", "B-05"), rows.filter { it.id == "one" }.map { it.locationCode })
            assertEquals(listOf(2.0, 1.0), rows.filter { it.id == "one" }.map { it.quantity })
            assertNull(rows.single { it.id == "two" }.locationCode)
            assertEquals("one", db.itemDao().findLiveByAlias(ItemAttributes.encodedAlias("备用,电池"))?.id)
            assertNull(db.itemDao().findLiveByAlias(ItemAttributes.encodedAlias("备用")))
            assertEquals(listOf("one"), db.itemDao().searchWithLocations("%", "%", "%").first().map { it.id }.distinct())
            assertEquals(listOf("one"), db.itemDao().searchWithLocations("_", "_", "_").first().map { it.id }.distinct())
        } finally {
            db.close()
        }
    }
}
