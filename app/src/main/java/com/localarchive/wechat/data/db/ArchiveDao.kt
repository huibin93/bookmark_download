package com.localarchive.wechat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.localarchive.wechat.data.model.LinkStatus
import com.localarchive.wechat.data.model.TaskStatus
import com.localarchive.wechat.data.model.TaskType
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM link_records ORDER BY last_seen_at DESC")
    fun observeLinks(): Flow<List<LinkRecordEntity>>

    @Query("SELECT * FROM link_records WHERE id = :id LIMIT 1")
    suspend fun getLink(id: Long): LinkRecordEntity?

    @Query("SELECT * FROM link_records WHERE normalized_url = :normalizedUrl LIMIT 1")
    suspend fun findLinkByNormalizedUrl(normalizedUrl: String): LinkRecordEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: LinkRecordEntity): Long

    @Update
    suspend fun updateLink(link: LinkRecordEntity)

    @Query("UPDATE link_records SET last_seen_at = :time, status = :status WHERE id = :id")
    suspend fun updateLinkStatus(id: Long, status: LinkStatus, time: Long)

    @Query(
        """
        SELECT * FROM articles
        WHERE (:query = '' OR title LIKE '%' || :query || '%' OR account_name LIKE '%' || :query || '%')
        ORDER BY saved_at DESC
        """,
    )
    fun observeArticles(query: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE normalized_url = :normalizedUrl LIMIT 1")
    suspend fun findArticleByNormalizedUrl(normalizedUrl: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE link_id = :linkId LIMIT 1")
    suspend fun findArticleByLinkId(linkId: Long): ArticleEntity?

    @Query("SELECT * FROM articles WHERE link_id IN (:linkIds)")
    suspend fun getArticlesByLinkIds(linkIds: List<Long>): List<ArticleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticle(article: ArticleEntity): Long

    @Query("DELETE FROM articles WHERE link_id IN (:linkIds)")
    suspend fun deleteArticlesByLinkIds(linkIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscoveredLink(link: DiscoveredLinkEntity): Long

    @Query("SELECT * FROM discovered_links WHERE source_article_id = :articleId ORDER BY created_at ASC")
    fun observeDiscoveredLinks(articleId: Long): Flow<List<DiscoveredLinkEntity>>

    @Query("SELECT * FROM discovered_links WHERE source_article_id = :articleId ORDER BY created_at ASC")
    suspend fun getDiscoveredLinks(articleId: Long): List<DiscoveredLinkEntity>

    @Query("SELECT * FROM download_tasks ORDER BY updated_at DESC")
    fun observeTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE link_id = :linkId AND task_type = :taskType LIMIT 1")
    suspend fun findTask(linkId: Long, taskType: TaskType): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(task: DownloadTaskEntity): Long

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query(
        """
        UPDATE download_tasks
        SET status = :status, attempt_count = attempt_count + :attemptDelta,
            last_error = :lastError, updated_at = :updatedAt
        WHERE link_id = :linkId AND task_type = :taskType
        """,
    )
    suspend fun updateTaskStatus(
        linkId: Long,
        taskType: TaskType,
        status: TaskStatus,
        attemptDelta: Int,
        lastError: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM download_tasks WHERE link_id IN (:linkIds)")
    suspend fun deleteTasksByLinkIds(linkIds: List<Long>)
}
