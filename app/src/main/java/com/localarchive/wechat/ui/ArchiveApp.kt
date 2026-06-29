package com.localarchive.wechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localarchive.wechat.data.db.ArticleEntity
import com.localarchive.wechat.data.db.DownloadTaskEntity
import com.localarchive.wechat.data.db.LinkRecordEntity
import com.localarchive.wechat.data.model.LinkSource
import com.localarchive.wechat.data.model.LinkStatus
import com.localarchive.wechat.data.model.LinkType
import com.localarchive.wechat.data.model.TaskStatus
import com.localarchive.wechat.data.repository.ArchiveRepository
import com.localarchive.wechat.data.repository.CaptureLinkResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DrawerFilter(val label: String) {
    All("全部"),
    Saved("已保存"),
    NotSaved("未下载"),
    Albums("专辑"),
    Failed("失败"),
}

private enum class ItemState { Saved, Queued, Failed, NeedsManual, NotSaved }

private data class ArchiveListItem(
    val link: LinkRecordEntity,
    val article: ArticleEntity?,
    val task: DownloadTaskEntity?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveApp(
    repository: ArchiveRepository,
    onOpenRemoteLink: (LinkRecordEntity) -> Unit,
    onOpenStoredArticle: (ArticleEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val links by repository.observeLinks().collectAsStateWithLifecycle(initialValue = emptyList())
    val articles by repository.observeArticles("").collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by repository.observeTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf(DrawerFilter.All) }
    var query by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var selectedLinkIds by remember { mutableStateOf(emptySet<Long>()) }

    val articleByLinkId = articles.associateBy { it.linkId }
    val taskByLinkId = tasks.associateBy { it.linkId }
    val listItems = links
        .map { link -> ArchiveListItem(link, articleByLinkId[link.id], taskByLinkId[link.id]) }
        .filter { item -> item.matchesFilter(filter) }
        .filter { item -> item.matchesQuery(query) }
    val counts = DrawerFilter.entries.associateWith { it.count(links, articles, tasks) }
    val selectionMode = selectedLinkIds.isNotEmpty()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ArchiveDrawer(
                filter = filter,
                counts = counts,
                onFilterSelected = { next ->
                    filter = next
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text("公众号文章归档", fontWeight = FontWeight.SemiBold)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "筛选视图")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (selectionMode) {
                    SelectionBar(
                        selectedCount = selectedLinkIds.size,
                        onExport = {
                            scope.launch {
                                val count = repository.exportArticlesForLinks(selectedLinkIds)
                                snackbarHostState.showSnackbar("已导出 $count 篇到 Downloads/WechatArchive")
                                selectedLinkIds = emptySet()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val count = repository.deleteArticlesForLinks(selectedLinkIds)
                                snackbarHostState.showSnackbar("已删除 $count 篇，链接记录已保留")
                                selectedLinkIds = emptySet()
                            }
                        },
                        onCancel = { selectedLinkIds = emptySet() },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ComposerCard(
                    input = input,
                    onInputChange = { input = it },
                    articleCount = articles.size,
                    queueCount = tasks.size,
                    onOpen = {
                        scope.launch {
                            when (val result = repository.captureIncomingText(input, LinkSource.PASTE)) {
                                is CaptureLinkResult.Success -> {
                                    input = ""
                                    onOpenRemoteLink(result.link)
                                }
                                is CaptureLinkResult.Unsupported -> {
                                    snackbarHostState.showSnackbar("没有找到可支持的公众号链接")
                                }
                            }
                        }
                    },
                )
                FilterChipsRow(
                    filter = filter,
                    counts = counts,
                    onSelect = { filter = it },
                )
                SearchField(query = query, onQueryChange = { query = it })
                Text(
                    text = "${filter.label} · ${listItems.size} 项",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (listItems.isEmpty()) {
                    EmptyState(query = query, filter = filter)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(listItems, key = { it.link.id }) { item ->
                            ArchiveListRow(
                                item = item,
                                selected = item.link.id in selectedLinkIds,
                                selectionMode = selectionMode,
                                onOpen = {
                                    item.article?.let(onOpenStoredArticle) ?: onOpenRemoteLink(item.link)
                                },
                                onToggleSelect = {
                                    selectedLinkIds = if (item.link.id in selectedLinkIds) {
                                        selectedLinkIds - item.link.id
                                    } else {
                                        selectedLinkIds + item.link.id
                                    }
                                },
                                onArchiveAlbum = {
                                    scope.launch {
                                        val count = repository.enqueueDiscoveredArticlesForAlbum(item.link.id)
                                        snackbarHostState.showSnackbar("已加入 $count 篇专辑文章")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerCard(
    input: String,
    onInputChange: (String) -> Unit,
    articleCount: Int,
    queueCount: Int,
    onOpen: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("粘贴公众号文章链接或分享文本") },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                minLines = 1,
                maxLines = 4,
                shape = MaterialTheme.shapes.medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onOpen,
                    enabled = input.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开并保存")
                }
                Spacer(Modifier.weight(1f))
                StatPill(icon = Icons.Filled.Article, value = articleCount, label = "文章")
                StatPill(icon = Icons.Filled.Schedule, value = queueCount, label = "队列")
            }
        }
    }
}

@Composable
private fun StatPill(icon: ImageVector, value: Int, label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                "$value $label",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    filter: DrawerFilter,
    counts: Map<DrawerFilter, Int>,
    onSelect: (DrawerFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DrawerFilter.entries.forEach { item ->
            FilterChip(
                selected = filter == item,
                onClick = { onSelect(item) },
                label = { Text("${item.label} ${counts[item] ?: 0}") },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        label = { Text("搜索标题、公众号或链接") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun ArchiveDrawer(
    filter: DrawerFilter,
    counts: Map<DrawerFilter, Int>,
    onFilterSelected: (DrawerFilter) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp).size(24.dp),
                )
            }
            Column {
                Text("归档视图", style = MaterialTheme.typography.titleMedium)
                Text(
                    "按状态筛选文章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        DrawerFilter.entries.forEach { item ->
            NavigationDrawerItem(
                icon = { Icon(item.icon(), contentDescription = null) },
                label = { Text(item.label) },
                badge = { Text("${counts[item] ?: 0}") },
                selected = filter == item,
                onClick = { onFilterSelected(item) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 $selectedCount 项",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onCancel) { Text("取消") }
            OutlinedButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除")
            }
            Button(onClick = onExport) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出")
            }
        }
    }
}

@Composable
private fun ArchiveListRow(
    item: ArchiveListItem,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onArchiveAlbum: () -> Unit,
) {
    val article = item.article
    val link = item.link
    val containerColor =
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LeadingAvatar(type = link.linkType, selected = selected)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article?.title ?: link.displayName(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = article?.accountName ?: link.normalizedUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(item.state())
                    Text(
                        text = formatTime(article?.savedAt ?: link.lastSeenAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (link.linkType == LinkType.ALBUM && article != null) {
                    TextButton(
                        onClick = onArchiveAlbum,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("存档专辑")
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadingAvatar(type: LinkType, selected: Boolean) {
    val (bg, fg, icon) = when {
        selected -> Triple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
            Icons.Filled.Check,
        )
        type == LinkType.ALBUM -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Filled.Collections,
        )
        else -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.Article,
        )
    }
    Box(
        modifier = Modifier.size(40.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StatusBadge(state: ItemState) {
    val (container, content, icon, label) = when (state) {
        ItemState.Saved -> BadgeStyle(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Filled.CheckCircle, "已下载",
        )
        ItemState.Queued -> BadgeStyle(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.Schedule, "队列中",
        )
        ItemState.Failed -> BadgeStyle(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.ErrorOutline, "失败",
        )
        ItemState.NeedsManual -> BadgeStyle(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.OpenInNew, "需手动",
        )
        ItemState.NotSaved -> BadgeStyle(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.CloudDownload, "未下载",
        )
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = content,
            disabledLeadingIconContentColor = content,
        ),
        border = null,
        modifier = Modifier.height(28.dp),
    )
}

private data class BadgeStyle(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun EmptyState(query: String, filter: DrawerFilter) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = when {
                    query.isNotBlank() -> "没有匹配“$query”的结果"
                    filter == DrawerFilter.All -> "还没有归档。分享或粘贴一条公众号文章链接开始。"
                    else -> "“${filter.label}”分类下暂时没有内容"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun ArchiveListItem.state(): ItemState = when {
    link.status == LinkStatus.FAILED || task?.status == TaskStatus.FAILED -> ItemState.Failed
    link.status == LinkStatus.NEEDS_MANUAL_OPEN || task?.status == TaskStatus.NEEDS_MANUAL_OPEN -> ItemState.NeedsManual
    article != null -> ItemState.Saved
    task?.status == TaskStatus.RUNNING || task?.status == TaskStatus.PENDING || link.status == LinkStatus.QUEUED -> ItemState.Queued
    else -> ItemState.NotSaved
}

private fun ArchiveListItem.matchesFilter(filter: DrawerFilter): Boolean =
    when (filter) {
        DrawerFilter.All -> true
        DrawerFilter.Saved -> article != null
        DrawerFilter.NotSaved -> article == null
        DrawerFilter.Albums -> link.linkType == LinkType.ALBUM
        DrawerFilter.Failed -> link.status == LinkStatus.FAILED ||
            link.status == LinkStatus.NEEDS_MANUAL_OPEN ||
            task?.status == TaskStatus.FAILED ||
            task?.status == TaskStatus.NEEDS_MANUAL_OPEN
    }

private fun ArchiveListItem.matchesQuery(query: String): Boolean {
    val value = query.trim()
    if (value.isBlank()) return true
    return listOf(
        article?.title,
        article?.accountName,
        link.normalizedUrl,
        link.originalUrl,
    ).filterNotNull().any { it.contains(value, ignoreCase = true) }
}

private fun DrawerFilter.count(
    links: List<LinkRecordEntity>,
    articles: List<ArticleEntity>,
    tasks: List<DownloadTaskEntity>,
): Int {
    val articleLinkIds = articles.map { it.linkId }.toSet()
    val taskByLinkId = tasks.associateBy { it.linkId }
    return links.count { link ->
        ArchiveListItem(link, articles.firstOrNull { it.linkId == link.id }, taskByLinkId[link.id]).matchesFilter(this)
    }.takeIf { this != DrawerFilter.Saved } ?: articleLinkIds.size
}

private fun DrawerFilter.icon(): ImageVector =
    when (this) {
        DrawerFilter.All -> Icons.Filled.Inbox
        DrawerFilter.Saved -> Icons.Filled.CheckCircle
        DrawerFilter.NotSaved -> Icons.Filled.CloudDownload
        DrawerFilter.Albums -> Icons.Filled.Collections
        DrawerFilter.Failed -> Icons.Filled.ErrorOutline
    }

private fun LinkRecordEntity.displayName(): String =
    when (linkType) {
        LinkType.ALBUM -> "公众号专辑"
        LinkType.ARTICLE -> normalizedUrl
        LinkType.PROFILE -> "公众号主页"
        LinkType.EXTERNAL -> "外部链接"
        LinkType.UNKNOWN -> "未知链接"
    }

private fun formatTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
