package com.localarchive.wechat.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localarchive.wechat.archiveApplication
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SaveArticleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val linkId = inputData.getLong(KEY_LINK_ID, -1L)
        if (linkId <= 0) return Result.failure()
        val repository = applicationContext.archiveApplication.archiveRepository
        val link = repository.getLink(linkId) ?: return Result.failure()

        repository.markTaskRunning(linkId)
        return try {
            val html = runCatching { fetchHtml(link.originalUrl) }.getOrDefault("")
                .ifBlank { fetchHtml(link.normalizedUrl) }
            if (html.isBlank()) {
                repository.markTaskNeedsManualOpen(linkId, "后台下载为空，可能需要在 WebView 中手动打开")
                Result.success()
            } else {
                repository.saveFetchedPage(
                    linkId = linkId,
                    fetchedUrl = link.normalizedUrl,
                    rawHtml = html,
                    fallbackTitle = null,
                )
                repository.markTaskSaved(linkId)
                Result.success()
            }
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            if (message.contains("403") || message.contains("401") || message.contains("验证码")) {
                repository.markTaskNeedsManualOpen(linkId, message)
                Result.success()
            } else {
                repository.markTaskFailed(linkId, message)
                Result.retry()
            }
        }
    }

    private suspend fun fetchHtml(rawUrl: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_LINK_ID = "link_id"
        const val TAG = "save_article"
    }
}
