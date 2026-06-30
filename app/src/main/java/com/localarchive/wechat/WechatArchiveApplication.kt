package com.localarchive.wechat

import android.app.Application
import android.content.Context
import com.localarchive.wechat.core.ArchiveLog
import com.localarchive.wechat.core.archive.SafArchiveStore
import com.localarchive.wechat.data.db.ArchiveDatabase
import com.localarchive.wechat.data.repository.ArchiveRepository
import com.localarchive.wechat.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WechatArchiveApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: ArchiveDatabase by lazy { ArchiveDatabase.get(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val safStore: SafArchiveStore by lazy { SafArchiveStore(this) }
    val archiveRepository: ArchiveRepository by lazy {
        ArchiveRepository(
            context = this,
            dao = database.archiveDao(),
            safStore = safStore,
            settingsRepository = settingsRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        ArchiveLog.init(this)
        ArchiveLog.log("==== App 启动 ====")
        appScope.launch {
            runCatching { archiveRepository.runCleanSlateIfNeeded() }
        }
    }
}

val Context.archiveApplication: WechatArchiveApplication
    get() = applicationContext as WechatArchiveApplication
