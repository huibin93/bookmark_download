package com.localarchive.wechat.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.localarchive.wechat.core.archive.ArchiveFileStore
import com.localarchive.wechat.core.archive.DownloadsArchiveExporter
import com.localarchive.wechat.core.parser.WechatHtmlParser
import com.localarchive.wechat.core.url.NormalizedWechatUrl
import com.localarchive.wechat.core.url.WechatUrlNormalizer
import com.localarchive.wechat.data.db.ArchiveDao
import com.localarchive.wechat.data.db.ArticleEntity
import com.localarchive.wechat.data.db.DiscoveredLinkEntity
import com.localarchive.wechat.data.db.DownloadTaskEntity
import com.localarchive.wechat.data.db.LinkRecordEntity
import com.localarchive.wechat.data.model.LinkSource
import com.localarchive.wechat.data.model.LinkStatus
import com.localarchive.wechat.data.model.LinkType
import com.localarchive.wechat.data.model.TaskStatus
import com.localarchive.wechat.data.model.TaskType
import com.localarchive.wechat.data.settings.SettingsRepository
import com.localarchive.wechat.worker.SaveArticleWorker
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface CaptureLinkResult {
    data class Success(val link: LinkRecordEntity, val duplicate: Boolean) : CaptureLinkResult
    data class Unsupported(val input: String) : CaptureLinkResult
}

data class SavePageResult(
    val articleId: Long,
    val title: String,
    val discoveredCount: Int,
    val queuedCount: Int,
    val publicArchiveDir: String?,
)

class ArchiveRepository(
    private val context: Context,
    private val dao: ArchiveDao,
    private val fileStore: ArchiveFileStore,
    private val downloadsExporter: DownloadsArchiveExporter,
    private val settingsRepository: SettingsRepository,
) {
    fun observeLinks(): Flow<List<LinkRecordEntity>> = dao.observeLinks()

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = dao.observeTasks()

    fun observeArticles(query: String): Flow<List<ArticleEntity>> = dao.observeArticles(query.trim())

    suspend fun getLink(id: Long): LinkRecordEntity? = dao.getLink(id)

    suspend fun exportArticlesForLinks(linkIds: Set<Long>): Int {
        if (linkIds.isEmpty()) return 0
        val articles = dao.getArticlesByLinkIds(linkIds.toList())
        articles.forEach { article ->
            downloadsExporter.exportStoredArticle(article)
        }
        return articles.size
    }

    suspend fun deleteArticlesForLinks(linkIds: Set<Long>): Int {
        if (linkIds.isEmpty()) return 0
        val articles = dao.getArticlesByLinkIds(linkIds.toList())
        articles.forEach { article ->
            runCatching { File(article.archiveDir).deleteRecursively() }
            dao.updateLinkStatus(article.linkId, LinkStatus.CAPTURED, System.currentTimeMillis())
        }
        dao.deleteTasksByLinkIds(linkIds.toList())
        dao.deleteArticlesByLinkIds(linkIds.toList())
        return articles.size
    }

    suspend fun enqueueDiscoveredArticlesForAlbum(linkId: Long): Int {
        val album = dao.findArticleByLinkId(linkId) ?: return 0
        val sourceLink = dao.getLink(linkId) ?: return 0
        val discovered = dao.getDiscoveredLinks(album.id)
        var queued = 0
        discovered.forEach { item ->
            if (item.linkType != LinkType.ARTICLE) return@forEach
            val discoveredLink = captureNormalizedUrl(
                NormalizedWechatUrl(item.discoveredUrl, item.normalizedUrl, item.linkType),
                LinkSource.DISCOVERED,
                depth = 1,
                parentLinkId = sourceLink.id,
            )
            if (enqueueSaveTask(discoveredLink, priority = 70)) queued += 1
        }
        return queued
    }

    suspend fun captureIncomingText(
        text: String,
        source: LinkSource,
        depth: Int = 0,
        parentLinkId: Long? = null,
    ): CaptureLinkResult {
        val normalized = WechatUrlNormalizer.extractFirstSupportedUrl(text) ?: return CaptureLinkResult.Unsupported(text)
        val link = captureNormalizedUrl(normalized, source, depth, parentLinkId)
        settingsRepository.setLastOpenedUrl(link.normalizedUrl)
        return CaptureLinkResult.Success(link, duplicate = link.firstSeenAt != link.lastSeenAt)
    }

    suspend fun captureUrl(
        rawUrl: String,
        source: LinkSource,
        depth: Int = 0,
        parentLinkId: Long? = null,
    ): CaptureLinkResult {
        val normalized = WechatUrlNormalizer.normalize(rawUrl)
            ?.takeIf { it.type == LinkType.ARTICLE || it.type == LinkType.ALBUM }
            ?: return CaptureLinkResult.Unsupported(rawUrl)
        val link = captureNormalizedUrl(normalized, source, depth, parentLinkId)
        settingsRepository.setLastOpenedUrl(link.normalizedUrl)
        return CaptureLinkResult.Success(link, duplicate = link.firstSeenAt != link.lastSeenAt)
    }

    suspend fun saveCurrentPage(
        linkId: Long?,
        currentUrl: String,
        rawHtml: String,
        fallbackTitle: String?,
        discoverDepthOne: Boolean,
    ): SavePageResult {
        val link = resolveLinkForSave(linkId, currentUrl)
        return saveHtmlForLink(
            link = link,
            currentUrl = currentUrl,
            rawHtml = rawHtml,
            fallbackTitle = fallbackTitle,
            // 深度 0 的文章会发现并登记专辑；打开专辑页（无论它是直接打开还是从文章里发现的
            // 深度 1）都展开记录其文章目录。深度 1 的文章不再继续发现，避免无限扩散。
            discoverDepthOne = discoverDepthOne && (link.depth == 0 || link.linkType == LinkType.ALBUM),
        )
    }

    suspend fun saveFetchedPage(
        linkId: Long,
        fetchedUrl: String,
        rawHtml: String,
        fallbackTitle: String?,
    ): SavePageResult {
        val link = dao.getLink(linkId) ?: error("Link $linkId not found")
        return saveHtmlForLink(
            link = link,
            currentUrl = fetchedUrl,
            rawHtml = rawHtml,
            fallbackTitle = fallbackTitle,
            discoverDepthOne = link.linkType == LinkType.ALBUM,
        )
    }

    suspend fun enqueueSaveTask(link: LinkRecordEntity, priority: Int = 50): Boolean {
        val now = System.currentTimeMillis()
        val existing = dao.findTask(link.id, TaskType.SAVE_ARTICLE)
        if (existing != null) return false
        val inserted = dao.insertTask(
            DownloadTaskEntity(
                linkId = link.id,
                taskType = TaskType.SAVE_ARTICLE,
                priority = priority,
                status = TaskStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (inserted <= 0) return false
        dao.updateLinkStatus(link.id, LinkStatus.QUEUED, now)
        val request = OneTimeWorkRequestBuilder<SaveArticleWorker>()
            .setInputData(workDataOf(SaveArticleWorker.KEY_LINK_ID to link.id))
            .addTag(SaveArticleWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "save-article-${sha256(link.normalizedUrl).take(16)}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    suspend fun markTaskRunning(linkId: Long) {
        dao.updateTaskStatus(
            linkId = linkId,
            taskType = TaskType.SAVE_ARTICLE,
            status = TaskStatus.RUNNING,
            attemptDelta = 1,
            lastError = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun markTaskSaved(linkId: Long) {
        val now = System.currentTimeMillis()
        dao.updateTaskStatus(linkId, TaskType.SAVE_ARTICLE, TaskStatus.SAVED, 0, null, now)
        dao.updateLinkStatus(linkId, LinkStatus.SAVED, now)
    }

    suspend fun markTaskNeedsManualOpen(linkId: Long, message: String) {
        val now = System.currentTimeMillis()
        dao.updateTaskStatus(linkId, TaskType.SAVE_ARTICLE, TaskStatus.NEEDS_MANUAL_OPEN, 0, message, now)
        dao.updateLinkStatus(linkId, LinkStatus.NEEDS_MANUAL_OPEN, now)
    }

    suspend fun markTaskFailed(linkId: Long, message: String) {
        val now = System.currentTimeMillis()
        dao.updateTaskStatus(linkId, TaskType.SAVE_ARTICLE, TaskStatus.FAILED, 0, message, now)
        dao.updateLinkStatus(linkId, LinkStatus.FAILED, now)
    }

    private suspend fun resolveLinkForSave(linkId: Long?, currentUrl: String): LinkRecordEntity {
        val existing = linkId?.let { dao.getLink(it) }
        if (existing != null) return existing
        val normalized = WechatUrlNormalizer.normalize(currentUrl)
            ?.takeIf { it.type == LinkType.ARTICLE || it.type == LinkType.ALBUM }
            ?: error("Unsupported URL: $currentUrl")
        return captureNormalizedUrl(normalized, LinkSource.VIEW_INTENT, depth = 0, parentLinkId = null)
    }

    private suspend fun saveHtmlForLink(
        link: LinkRecordEntity,
        currentUrl: String,
        rawHtml: String,
        fallbackTitle: String?,
        discoverDepthOne: Boolean,
    ): SavePageResult {
        val parsed = WechatHtmlParser.parse(currentUrl, rawHtml, fallbackTitle)
        val archiveDir = withContext(Dispatchers.IO) {
            fileStore.saveArticle(link.id, link.normalizedUrl, rawHtml, parsed)
        }
        val existingArticle = dao.findArticleByNormalizedUrl(link.normalizedUrl)
        val article = ArticleEntity(
            id = existingArticle?.id ?: 0,
            linkId = link.id,
            title = parsed.title,
            accountName = parsed.accountName,
            author = parsed.author,
            publishTime = parsed.publishTime,
            coverUrl = parsed.coverUrl,
            originalUrl = link.originalUrl,
            normalizedUrl = link.normalizedUrl,
            contentHash = parsed.contentHash,
            archiveDir = archiveDir,
            savedAt = System.currentTimeMillis(),
        )
        val articleId = dao.upsertArticle(article)
        val publicArchiveDir = withContext(Dispatchers.IO) {
            downloadsExporter.exportStoredArticle(article.copy(id = articleId))
        }
        dao.updateLinkStatus(link.id, LinkStatus.SAVED, System.currentTimeMillis())

        var queued = 0
        if (discoverDepthOne) {
            parsed.discoveredLinks.forEach { discovered ->
                dao.insertDiscoveredLink(
                    DiscoveredLinkEntity(
                        sourceArticleId = articleId,
                        discoveredUrl = discovered.originalUrl,
                        normalizedUrl = discovered.normalizedUrl,
                        linkType = discovered.linkType,
                        anchorText = discovered.anchorText,
                        depth = 1,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                // 把"分享/打开的文章"里发现的【专辑】登记成一条专辑记录（出现在"专辑"筛选里），
                // 但【不后台抓取】：微信对非浏览器请求会弹验证墙，裸 HTTP 抓专辑只会得到
                // "验证/未命名"垃圾页。专辑的展开放到用户在 App 内打开它时由 WebView 完成
                // （带 Cookie/JS，可靠），那时只记录专辑目录、不抓取其中文章。
                // 普通文章链接只记进 discovered_links 台账，不抓取。
                if (link.linkType != LinkType.ALBUM && discovered.linkType == LinkType.ALBUM) {
                    captureNormalizedUrl(
                        NormalizedWechatUrl(discovered.originalUrl, discovered.normalizedUrl, discovered.linkType),
                        LinkSource.DISCOVERED,
                        depth = 1,
                        parentLinkId = link.id,
                    )
                    queued += 1
                }
            }
        }

        return SavePageResult(
            articleId = articleId,
            title = parsed.title,
            discoveredCount = parsed.discoveredLinks.size,
            queuedCount = queued,
            publicArchiveDir = publicArchiveDir,
        )
    }

    private suspend fun captureNormalizedUrl(
        normalized: NormalizedWechatUrl,
        source: LinkSource,
        depth: Int,
        parentLinkId: Long?,
    ): LinkRecordEntity {
        val now = System.currentTimeMillis()
        val existing = dao.findLinkByNormalizedUrl(normalized.normalizedUrl)
        if (existing != null) {
            val status = when (existing.status) {
                LinkStatus.SAVED -> existing.status
                LinkStatus.QUEUED -> existing.status
                LinkStatus.NEEDS_MANUAL_OPEN -> existing.status
                else -> LinkStatus.CAPTURED
            }
            val updated = existing.copy(
                originalUrl = normalized.originalUrl,
                source = source,
                depth = minOf(existing.depth, depth),
                parentLinkId = existing.parentLinkId ?: parentLinkId,
                lastSeenAt = now,
                status = status,
            )
            dao.updateLink(updated)
            return updated
        }
        val record = LinkRecordEntity(
            originalUrl = normalized.originalUrl,
            normalizedUrl = normalized.normalizedUrl,
            linkType = normalized.type,
            source = source,
            depth = depth,
            parentLinkId = parentLinkId,
            firstSeenAt = now,
            lastSeenAt = now,
            status = LinkStatus.CAPTURED,
        )
        val id = dao.insertLink(record)
        return record.copy(id = id)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
