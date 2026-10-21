package com.kuguan.app.data.backup.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudBackupSettingsTest {
    @Test fun independentDefaultsAndFrequencyBounds() {
        assertEquals(720L, DestinationSettings().intervalMinutes)
        assertEquals(15L, DestinationSettings(intervalMinutes = 15).intervalMinutes)
        BackupFrequency.validate(15)
        BackupFrequency.validate(43_200)
        for (bad in listOf(0L, 14L, 16L, 43_215L)) {
            assertThrows(IllegalArgumentException::class.java) { BackupFrequency.validate(bad) }
        }
    }

    @Test fun monthlyTrafficUsesEachDestinationsOwnInterval() {
        assertEquals(60_000_000.0, BackupFrequency.monthlyBytes(1_000_000, 720), 0.01)
        assertEquals(720_000_000.0, BackupFrequency.monthlyBytes(1_000_000, 60), 0.01)
    }

    @Test fun selfHostedRequiresHttpsAndFixedApiPath() {
        for (bad in listOf("http://example.com/api/v1", "https://example.com/", "https://user:pass@example.com/api/v1")) {
            assertThrows(IllegalArgumentException::class.java) { SelfHostedBackupStore(bad, "token") }
        }
        SelfHostedBackupStore("https://example.com/api/v1", "token")
    }
}
