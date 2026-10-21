package com.kuguan.app.domain.voice

import com.kuguan.app.domain.voice.IntentRowPolicy.MissingField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪一行能写库」的判定。
 *
 * 这是零确认路径上最后一道闸门：判错不会报错、不会崩溃，只会在库里留下一条
 * 用户没说过数量的记录。所以每个缺失字段都要单独锁一条。
 */
class IntentRowPolicyTest {

    private fun row(
        name: String? = "七号电池",
        qty: Double? = 2.0,
        code: String? = "A-07",
        suspect: Boolean = false,
    ) = PlannedRow(name, qty, code, suspect)

    @Test
    fun `三要素齐全才可写`() {
        assertTrue(IntentRowPolicy.isWritable(row()))
    }

    @Test
    fun `缺数量不写`() {
        assertFalse(IntentRowPolicy.isWritable(row(qty = null)))
    }

    @Test
    fun `数量为 0 不写`() {
        assertFalse(IntentRowPolicy.isWritable(row(qty = 0.0)))
    }

    @Test
    fun `缺物品名不写`() {
        assertFalse(IntentRowPolicy.isWritable(row(name = null)))
        assertFalse(IntentRowPolicy.isWritable(row(name = "   ")))
    }

    @Test
    fun `缺编号不写`() {
        assertFalse(IntentRowPolicy.isWritable(row(code = null)))
        assertFalse(IntentRowPolicy.isWritable(row(code = "")))
    }

    @Test
    fun `用户自定义编号不受位数与格式限制`() {
        for (code in listOf("A113", "A-113", "A-00", "左边工具柜")) {
            assertTrue(IntentRowPolicy.isWritable(row(code = code)))
            assertEquals(emptySet<MissingField>(), IntentRowPolicy.missingFields(row(code = code)))
        }
        assertFalse(IntentRowPolicy.isWritable(row(code = "   ")))
    }

    @Test
    fun `标红字段按缺口给出`() {
        assertEquals(emptySet<MissingField>(), IntentRowPolicy.missingFields(row()))
        assertEquals(setOf(MissingField.QUANTITY), IntentRowPolicy.missingFields(row(qty = null)))
        assertEquals(setOf(MissingField.ITEM), IntentRowPolicy.missingFields(row(name = null)))
        assertEquals(setOf(MissingField.LOCATION), IntentRowPolicy.missingFields(row(code = null)))
    }

    @Test
    fun `存疑编号即使形态合法也不写`() {
        assertFalse(IntentRowPolicy.isWritable(row(suspect = true)))
    }

    @Test
    fun `存疑编号即使形态合法也标红`() {
        assertEquals(
            setOf(MissingField.LOCATION),
            IntentRowPolicy.missingFields(row(suspect = true)),
        )
    }


    @Test
    fun `字段全缺时三个都标红`() {
        assertEquals(
            setOf(MissingField.ITEM, MissingField.LOCATION, MissingField.QUANTITY),
            IntentRowPolicy.missingFields(row(name = null, qty = null, code = null)),
        )
    }
}
