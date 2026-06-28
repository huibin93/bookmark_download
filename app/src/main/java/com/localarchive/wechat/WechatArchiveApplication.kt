package com.localarchive.wechat

import android.app.Application
import android.content.Context
import com.localarchive.wechat.core.archive.ArchiveFileStore
import com.localarchive.wechat.core.archive.DownloadsArchiveExporter
import com.localarchive.wechat.data.db.ArchiveDatabase
import com.localarchive.wechat.data.repository.ArchiveRepository
import com.localarchive.wechat.data.settings.SettingsRepository

class WechatArchiveApplication : Application() {
    val database: ArchiveDatabase by lazy { ArchiveDatabase.get(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val archiveRepository: ArchiveRepository by lazy {
        ArchiveRepository(
            context = this,
            dao = database.archiveDao(),
            fileStore = ArchiveFileStore(this),
            downloadsExporter = DownloadsArchiveExporter(this),
            settingsRepository = settingsRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            DownloadsArchiveExporter(this).ensureRootFolder()
        }
    }
}

val Context.archiveApplication: WechatArchiveApplication
    get() = applicationContext as WechatArchiveApplication
