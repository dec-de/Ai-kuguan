package com.kuguan.app.data.reminder

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M5ReminderManagerTest {
    @Test fun channelAndSettingSurviveNewManagerInstance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ReminderManager(context)
        val original = manager.enabled()
        try {
            manager.createChannel()
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(ReminderManager.CHANNEL)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
            manager.setEnabled(false)
            assertFalse(ReminderManager(context).enabled())
            manager.setEnabled(true)
            assertTrue(ReminderManager(context).enabled())
        } finally {
            manager.setEnabled(original)
        }
    }
}
