package com.kuguan.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kuguan.app.data.db.ReminderSettingsEntity

@Dao
interface ReminderSettingsDao {
    @Query("SELECT enabled FROM reminder_settings WHERE id = 1")
    suspend fun enabledOrNull(): Boolean?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(settings: ReminderSettingsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(settings: ReminderSettingsEntity)
}
