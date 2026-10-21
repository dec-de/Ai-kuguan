package com.kuguan.app

import android.app.Application
import com.kuguan.app.di.AppContainer

/**
 * Application 只做一件事：持有 [AppContainer]。
 *
 * Room 的初始化刻意放在 `by lazy` 里 —— 数据库首次真正被用到才建，
 * 这样冷启动路径上不会平白多一次磁盘操作。
 */
class KuguanApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}