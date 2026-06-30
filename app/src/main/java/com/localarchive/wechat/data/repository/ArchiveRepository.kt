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

    /** 批量导出专辑链接为 JSON（仅名称 + 地址），返回写入的相对路径。 */
    suspend fun exportAlbums(albums: List<LinkRecordEntity>): String =
        withContext(Dispatchers.IO) {
            downloadsExporter.exportAlbumsJson(
                albums.filter { it.linkType == LinkType.ALBUM }
                    .map { (it.title.orEmpty()) to it.normalizedUrl },
            )
        }

    suspend fun deleteArticlesForLinks(linkIds: Set<Long>): Int {
        if (linkIds.isEmpty()) return 0
        val ids = linkIds.toList()
        val articles = dao.getArticlesByLinkIds(ids)
        // 删除本机归档文件
        articles.forEach { article ->
            runCatching { File(article.archiveDir).deleteRecursively() }
        }
        // 连同这些文章发现的链接台账一起清掉
        val articleIds = articles.map { it.id }
        if (articleIds.isNotEmpty()) dao.deleteDiscoveredLinksByArticleIds(articleIds)
        dao.deleteTasksByLinkIds(ids)
        dao.deleteArticlesByLinkIds(ids)
        // 彻底删除链接记录本身，不再保留“见过”的去重记录。
        dao.deleteLinksByIds(ids)
        return ids.size
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
            // 只有深度 0 的【文章】才发现并登记它引用的专辑。专辑本身只保存名称+地址，
            // 不扫描里面有哪些文章；深度 1 的文章也不再继续发现，避免扩散。
            discoverDepthOne = discoverDepthOne && link.depth == 0 && link.linkType != LinkType.ALBUM,
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
            // 专辑只记录名称+地址，不扫描其文章目录。
            discoverDepthOne = false,
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
        // 解析（大量正则、整页扫描）放到后台线程，避免阻塞主线程导致界面卡顿/无响应。
        val parsed = withContext(Dispatchers.Default) {
            WechatHtmlParser.parse(currentUrl, rawHtml, fallbackTitle)
        }
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
                // 只登记带 album_id 的真实专辑；上一篇/下一篇等不完整的 appmsgalbum 导航链接跳过。
                if (link.linkType != LinkType.ALBUM &&
                    discovered.linkType == LinkType.ALBUM &&
                    discovered.normalizedUrl.contains("album_id=")
                ) {
                    captureNormalizedUrl(
                        NormalizedWechatUrl(discovered.originalUrl, discovered.normalizedUrl, discovered.linkType),
                        LinkSource.DISCOVERED,
                        depth = 1,
                        parentLinkId = link.id,
                        title = parsed.albumNames[discovered.normalizedUrl] ?: discovered.anchorText.ifBlank { null },
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
        title: String? = null,
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
                title = existing.title ?: title,
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
            title = title,
        )
        val id = dao.insertLink(record)
        return record.copy(id = id)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
