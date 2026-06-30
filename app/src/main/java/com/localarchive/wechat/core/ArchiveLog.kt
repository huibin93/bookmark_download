package com.localarchive.wechat.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight flow logger so the whole save/load pipeline is auditable.
 *
 * Every step (page load, lazy-load trigger, per-image interception, progress
 * polling, parse, archive build, SAF write) is recorded both to logcat (tag
 * [TAG]) and to a persistent file at `filesDir/save-flow.log`, with a timestamp.
 * Pull it with: `adb shell run-as com.localarchive.wechat cat files/save-flow.log`.
 */
object ArchiveLog {
    const val TAG = "ArchiveFlow"
    private const val MAX_BYTES = 512 * 1024

    @Volatile private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, "save-flow.log")
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val file = logFile ?: return
        synchronized(lock) {
            runCatching {
                if (file.exists() && file.length() > MAX_BYTES) file.writeText("")
                file.appendText("${fmt.format(Date())}  $message\n")
            }
        }
    }

    /** Mark the start of a timed step; returns a token for [done]. */
    fun now(): Long = System.currentTimeMillis()

    fun done(label: String, startMs: Long, extra: String = "") {
        val ms = System.currentTimeMillis() - startMs
        log("$label 用时 ${ms}ms${if (extra.isNotBlank()) " · $extra" else ""}")
    }
}
