package com.kuguan.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestockThresholdInputTest {
    @Test fun acceptsPositiveDecimalQuantity() {
        assertEquals(2.5, parsePositiveRestockThreshold(" 2.5 ")!!, 0.0)
        assertEquals(0.25, parsePositiveRestockThreshold("0.25")!!, 0.0)
    }

    @Test fun rejectsInvalidOrNonPositiveQuantity() {
        listOf("", " ", "0", "0.0", "-0.0", "-1", "1,5", "abc", "NaN", "Infinity",
            "-Infinity", "1e309")
            .forEach { assertNull(it, parsePositiveRestockThreshold(it)) }
    }
}
