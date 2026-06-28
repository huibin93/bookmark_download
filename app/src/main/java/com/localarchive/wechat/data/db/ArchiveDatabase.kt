package com.localarchive.wechat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LinkRecordEntity::class,
        ArticleEntity::class,
        DownloadTaskEntity::class,
        DiscoveredLinkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    companion object {
        @Volatile private var instance: ArchiveDatabase? = null

        fun get(context: Context): ArchiveDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArchiveDatabase::class.java,
                    "wechat_archive.db",
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }
    }
}
