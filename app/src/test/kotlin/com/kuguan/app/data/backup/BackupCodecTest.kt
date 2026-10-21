package com.kuguan.app.data.backup

import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.LocationRecordEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class BackupCodecTest {
    private fun sample(): BackupSnapshot {
        val items = (1..10).map { n ->
            ItemEntity("i$n", "电池 $n", "[\"别名$n\"]", "工具", "2027-01-01",
                if (n == 1) 120L else null, if (n == 10) 300L else null, n.toLong(), 400L + n)
        }
        val locations = (1..10).map { n ->
            LocationRecordEntity("l$n", "i$n", "A-${n.toString().padStart(2, '0')}",
                n + 0.5, if (n == 10) 300L else null, n.toLong(), 500L + n)
        }
        val operations = (1..10).map { n ->
            OperationEntity("o$n", n.toLong(), OperationType.PUT, "i$n", "A-${n.toString().padStart(2, '0')}",
                n + 0.5, "{\"中文\":\"值$n\"}", "说话$n", null, "batch", if (n == 10) 300L else null, n.toLong())
        } + OperationEntity("take", 11, OperationType.TAKE, "i1", "A-01", 1.0, "取用", "取一节", null, null, null, 120)
        return BackupSnapshot(items, locations, operations)
    }

    @Test fun tenRowsAllFieldsRoundTrip() {
        val original = sample()
        val encoded = BackupCodec.encode(original)
        assertTrue(encoded.contains("\"schemaVersion\":4"))
        assertEquals(original, BackupCodec.decode(encoded))
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(BackupCodec.decode(encoded))))
    }

    @Test fun emptyAndInvalidFiles() {
        val empty = BackupSnapshot(emptyList(), emptyList(), emptyList())
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
        listOf("", "{", "[]", "{}", "{\"schemaVersion\":4,\"items\":[],\"locationRecords\":[],\"operations\":[]}")
            .forEach { assertThrows(Exception::class.java) { BackupCodec.decode(it) } }
        assertEquals(500_000_000, BackupCodec.MAX_BYTES)
    }

    @Test fun brokenReferencesDuplicatesAndDerivedValue() {
        val base = sample()
        val bad = listOf(
            base.copy(items = base.items + base.items.first()),
            base.copy(locationRecords = base.locationRecords + base.locationRecords.first().copy(id = "other")),
            base.copy(locationRecords = base.locationRecords.map { if (it.id == "l1") it.copy(itemId = "missing") else it }),
            base.copy(locationRecords = base.locationRecords.map { if (it.id == "l1") it.copy(quantity = Double.POSITIVE_INFINITY) else it }),
            base.copy(operations = base.operations + base.operations.first().copy(id = "other")),
            base.copy(operations = base.operations.map { if (it.id == "take") it.copy(itemId = "missing") else it }),
            base.copy(operations = base.operations.map { if (it.id == "take") it.copy(targetSeq = 99) else it }),
            base.copy(items = base.items.map { if (it.id == "i1") it.copy(lastUsedAt = 1) else it }),
            base.copy(items = base.items.map { if (it.id == "i1") it.copy(aliases = "bad") else it }),
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(it) } }
    }

    @Test fun missingAndWrongTypedFieldRejected() {
        val encoded = BackupCodec.encode(sample())
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replaceFirst("\"itemId\":\"i1\"", "\"itemId\":null")) }
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replace("\"schemaVersion\":4", "\"schemaVersion\":\"4\"")) }
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replace("\"lastUsedAt\":120", "\"lastUsedAt\":\"120\"")) }
    }

    @Test fun streamingV4RoundTripAndOldV2Fields() {
        val original = sample().copy(reminderSettings = ReminderBackupSettings(false))
        val out = ByteArrayOutputStream()
        BackupCodec.encodeTo(out, original)
        assertEquals(original, BackupCodec.decode(ByteArrayInputStream(out.toByteArray())))
        val v2 = """{"schemaVersion":2,"items":[],"locationRecords":[],"operations":[]}"""
        val old = BackupCodec.decode(v2)
        assertEquals(2, old.schemaVersion)
        assertEquals(null, old.reminderSettings)
        assertEquals(null, old.backupId)
    }

    @Test fun restockThresholdModesAndLegacyV3Import() {
        val base = sample()
        val states = listOf(null, 0.0, 2.5)
        val current = base.copy(items = base.items.mapIndexed { index, item ->
            item.copy(restockThreshold = states[index % states.size])
        })
        assertEquals(current, BackupCodec.decode(BackupCodec.encode(current)))

        val v3 = BackupCodec.encode(base)
            .replaceFirst("\"schemaVersion\":4", "\"schemaVersion\":3")
            .replace(Regex(",\"restockThreshold\":null"), "")
        val imported = BackupCodec.decode(v3)
        assertEquals(3, imported.schemaVersion)
        assertEquals(base.items.map { null }, imported.items.map { it.restockThreshold })
        assertEquals(base.reminderSettings, imported.reminderSettings)
        val reorderedV3 = v3.replaceFirst("{\"schemaVersion\":3,", "{")
            .dropLast(1) + ",\"schemaVersion\":3}"
        assertEquals(imported, BackupCodec.decode(reorderedV3))

        val v2 = v3.replaceFirst("\"schemaVersion\":3,", "\"schemaVersion\":2,")
            .replace(Regex(",\"backupId\":\"[^\"]+\""), "")
            .replace(Regex(",\"createdAtUtc\":\"[^\"]+\""), "")
            .replace(Regex(",\"reminderSettings\":\\{\"enabled\":(?:true|false)\\}"), "")
        val old = BackupCodec.decode(v2)
        assertEquals(2, old.schemaVersion)
        assertEquals(base.items.map { null }, old.items.map { it.restockThreshold })
    }

    @Test fun invalidRestockThresholdRejected() {
        val base = sample()
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { threshold ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupCodec.validate(base.copy(items = base.items.mapIndexed { index, item ->
                    if (index == 0) item.copy(restockThreshold = threshold) else item
                }))
            }
        }
        val encoded = BackupCodec.encode(base)
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replaceFirst("\"restockThreshold\":null", "\"restockThreshold\":-1")) }
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replaceFirst("\"restockThreshold\":null", "\"restockThreshold\":\"1\"")) }
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replaceFirst(",\"restockThreshold\":null", "")) }
        assertThrows(Exception::class.java) { BackupCodec.decode(encoded.replaceFirst("\"schemaVersion\":4", "\"schemaVersion\":3")
            .replaceFirst("\"restockThreshold\":null", "\"restockThreshold\":1")) }
    }

    @Test fun rejectsInvalidUtf8AndWrongReminderBoolean() {
        assertThrows(Exception::class.java) { BackupCodec.decode(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
        val source = BackupCodec.encode(sample())
        assertThrows(Exception::class.java) { BackupCodec.decode(source.replace("\"enabled\":true", "\"enabled\":\"true\"")) }
    }

    @Test fun previewStreamsRowsAndAcceptsAnyObjectFieldOrder() {
        val source = """{"operations":[],"locationRecords":[],"items":[],"schemaVersion":2}"""
        val preview = BackupCodec.inspect(source.byteInputStream())
        assertEquals(0, preview.items)
        assertEquals(0, preview.locations)
        assertEquals(0, preview.operations)
        assertEquals(2, preview.schemaVersion)
        assertEquals(null, preview.reminderSettings)
        val bad = """{"schemaVersion":2,"items":[],"locationRecords":[{"id":"l","itemId":"missing","locationCode":"A","quantity":1,"deletedAt":null,"createdAt":1,"updatedAt":1}],"operations":[]}"""
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.inspect(bad.byteInputStream()) }
    }

    @Test fun exactDecimalByteBoundaryWithoutAllocatingBackup() {
        class RepeatingInput(private var remaining: Long) : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 0x20 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                remaining -= count
                return count
            }
        }
        val discard = object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }
        assertEquals(499_999_999L, BackupCodec.copyLimited(RepeatingInput(499_999_999L), discard))
        assertEquals(500_000_000L, BackupCodec.copyLimited(RepeatingInput(500_000_000L), discard))
        assertThrows(IllegalStateException::class.java) {
            BackupCodec.copyLimited(RepeatingInput(500_000_001L), discard)
        }
    }
}
