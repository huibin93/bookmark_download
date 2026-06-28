package com.localarchive.wechat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.localarchive.wechat.data.model.LinkSource
import com.localarchive.wechat.data.model.LinkStatus
import com.localarchive.wechat.data.model.LinkType
import com.localarchive.wechat.data.model.TaskStatus
import com.localarchive.wechat.data.model.TaskType

@Entity(
    tableName = "link_records",
    indices = [
        Index(value = ["normalized_url"], unique = true),
        Index(value = ["link_type"]),
        Index(value = ["status"]),
    ],
)
data class LinkRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "original_url") val originalUrl: String,
    @ColumnInfo(name = "normalized_url") val normalizedUrl: String,
    @ColumnInfo(name = "link_type") val linkType: LinkType,
    val source: LinkSource,
    val depth: Int,
    @ColumnInfo(name = "parent_link_id") val parentLinkId: Long? = null,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    val status: LinkStatus = LinkStatus.CAPTURED,
)

@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(
            entity = LinkRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["link_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["link_id"], unique = true),
        Index(value = ["normalized_url"], unique = true),
        Index(value = ["title"]),
        Index(value = ["account_name"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "link_id") val linkId: Long,
    val title: String,
    @ColumnInfo(name = "account_name") val accountName: String,
    val author: String? = null,
    @ColumnInfo(name = "publish_time") val publishTime: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    @ColumnInfo(name = "original_url") val originalUrl: String,
    @ColumnInfo(name = "normalized_url") val normalizedUrl: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "archive_dir") val archiveDir: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(
            entity = LinkRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["link_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["link_id", "task_type"], unique = true),
        Index(value = ["status"]),
    ],
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "link_id") val linkId: Long,
    @ColumnInfo(name = "task_type") val taskType: TaskType,
    val priority: Int,
    val status: TaskStatus,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "discovered_links",
    indices = [
        Index(value = ["source_article_id"]),
        Index(value = ["normalized_url"]),
    ],
)
data class DiscoveredLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_article_id") val sourceArticleId: Long,
    @ColumnInfo(name = "discovered_url") val discoveredUrl: String,
    @ColumnInfo(name = "normalized_url") val normalizedUrl: String,
    @ColumnInfo(name = "link_type") val linkType: LinkType,
    @ColumnInfo(name = "anchor_text") val anchorText: String,
    val depth: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
