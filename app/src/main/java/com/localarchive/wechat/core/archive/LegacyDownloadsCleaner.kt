package com.localarchive.wechat.core.archive

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * One-time cleanup of the OLD version's dual-copy output in public Downloads
 * (`Download/WechatArchive/articles/...`, written via MediaStore). Only used by
 * the clean-slate migration; going forward all storage is SAF-based.
 *
 * MediaStore can delete the file rows (so the user's old archives stop taking
 * space); any leftover empty folders are harmless old junk the user can remove
 * once — the new SAF path never creates MediaStore entries.
 */
object LegacyDownloadsCleaner {
    fun deleteLegacyArticles(context: Context): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val pattern = "${Environment.DIRECTORY_DOWNLOADS}/WechatArchive/articles/%"
        var deleted = 0
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(pattern),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    deleted += resolver.delete(uri, null, null)
                }
            }
        }
        return deleted
    }
}
