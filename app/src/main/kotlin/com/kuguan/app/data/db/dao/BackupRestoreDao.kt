package com.kuguan.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * 备份恢复专用的清库入口。
 *
 * 为什么单独开一个 DAO：operations 表是 append-only 审计日志，日常路径**不允许**删行。
 * 但 M6 的「全量覆盖式恢复」语义要求先清空再写入 —— 这是唯一被批准的例外。
 * 把它隔离在这里，是为了让「日常代码无法误删历史」成为结构性保证，
 * 而不是靠一条容易被无视的注释。
 */
@Dao
interface BackupRestoreDao {

    /** 仅供 M6 导入覆盖使用。调用方必须先向用户确认「将覆盖现有 N 条记录」。 */
    @Query("DELETE FROM operations")
    suspend fun clearOperations()
}