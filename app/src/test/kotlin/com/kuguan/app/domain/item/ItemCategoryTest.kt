package com.kuguan.app.domain.item

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemCategoryTest {
    @Test fun savedCategoryWinsAndIsTrimmed() {
        assertEquals("露营装备", ItemCategory.of("电池", " 露营装备 "))
    }

    @Test fun existingNamesGetStableFallbacks() {
        assertEquals("电子数码", ItemCategory.of("电池", null))
        assertEquals("工具五金", ItemCategory.of("膨胀螺丝", ""))
        assertEquals("其他", ItemCategory.of("纪念品", null))
    }

    @Test fun onlyCategoriesWithItemsAppear() {
        assertEquals(mapOf("电子数码" to 2, "其他" to 1),
            ItemCategory.counts(listOf("电池" to null, "充电器" to null, "纪念品" to null)))
    }
}
