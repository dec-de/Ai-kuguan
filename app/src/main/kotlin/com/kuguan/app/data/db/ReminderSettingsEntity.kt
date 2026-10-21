package com.kuguan.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminder_settings")
data class ReminderSettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
)
