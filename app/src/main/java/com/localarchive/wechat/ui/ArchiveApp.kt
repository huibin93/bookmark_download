package com.localarchive.wechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ArchiveDrawer(
                filter = filter,
                links = links,
                articles = articles,
                tasks = tasks,
                onFilterSelected = { next ->
                    filter = next
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            topBar = {
                TopAppBar(
                    title = { Text("公众号文章归档") },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("菜单")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴公众号文章链接或分享文本") },
                    minLines = 2,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
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
                    ) {
                        Text("打开并保存")
                    }
                    AssistChip(onClick = {}, label = { Text("文章 ${articles.size}") })
                    AssistChip(onClick = {}, label = { Text("队列 ${tasks.size}") })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("搜索标题、公众号或链接") },
                    singleLine = true,
                )
                if (selectedLinkIds.isNotEmpty()) {
                    SelectionActions(
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
                Text(
                    text = "${filter.label} ${listItems.size}",
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listItems, key = { it.link.id }) { item ->
                        ArchiveListRow(
                            item = item,
                            selected = item.link.id in selectedLinkIds,
                            onSelectedChange = { checked ->
                                selectedLinkIds = if (checked) {
                                    selectedLinkIds + item.link.id
                                } else {
                                    selectedLinkIds - item.link.id
                                }
                            },
                            onOpen = {
                                item.article?.let(onOpenStoredArticle) ?: onOpenRemoteLink(item.link)
                            },
                            onLongSelect = {
                                selectedLinkIds = selectedLinkIds + item.link.id
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

@Composable
private fun ArchiveDrawer(
    filter: DrawerFilter,
    links: List<LinkRecordEntity>,
    articles: List<ArticleEntity>,
    tasks: List<DownloadTaskEntity>,
    onFilterSelected: (DrawerFilter) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.requiredWidth(200.dp)) {
        Text(
            text = "归档视图",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        DrawerFilter.entries.forEach { item ->
            NavigationDrawerItem(
                label = { Text("${item.label} ${item.count(links, articles, tasks)}") },
                selected = filter == item,
                onClick = { onFilterSelected(item) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun SelectionActions(
    selectedCount: Int,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 $selectedCount", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onExport) { Text("导出") }
            OutlinedButton(onClick = onDelete) { Text("删除") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
private fun ArchiveListRow(
    item: ArchiveListItem,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onLongSelect: () -> Unit,
    onArchiveAlbum: () -> Unit,
) {
    val article = item.article
    val link = item.link
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongSelect,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = selected, onCheckedChange = onSelectedChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article?.title ?: link.displayName(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = article?.accountName ?: link.normalizedUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = item.statusLine(),
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatTime(article?.savedAt ?: link.lastSeenAt),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (link.linkType == LinkType.ALBUM && article != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onArchiveAlbum) { Text("存档专辑") }
                    }
                }
            }
        }
    }
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

private fun ArchiveListItem.statusLine(): String =
    buildList {
        add(link.linkType.label())
        add(if (article != null) "已下载" else link.status.label())
        add(if (article != null) "已导出" else "未导出")
        task?.let { add("队列 ${it.status.label()}") }
    }.joinToString(" · ")

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

private fun LinkRecordEntity.displayName(): String =
    when (linkType) {
        LinkType.ALBUM -> "公众号专辑"
        LinkType.ARTICLE -> normalizedUrl
        LinkType.PROFILE -> "公众号主页"
        LinkType.EXTERNAL -> "外部链接"
        LinkType.UNKNOWN -> "未知链接"
    }

private fun LinkType.label(): String =
    when (this) {
        LinkType.ARTICLE -> "文章"
        LinkType.ALBUM -> "专辑"
        LinkType.PROFILE -> "主页"
        LinkType.EXTERNAL -> "外部"
        LinkType.UNKNOWN -> "未知"
    }

private fun LinkStatus.label(): String =
    when (this) {
        LinkStatus.CAPTURED -> "未下载"
        LinkStatus.OPENED -> "已打开"
        LinkStatus.QUEUED -> "已入队"
        LinkStatus.SAVED -> "已下载"
        LinkStatus.SKIPPED_DUPLICATE -> "重复"
        LinkStatus.NEEDS_MANUAL_OPEN -> "需手动"
        LinkStatus.FAILED -> "失败"
    }

private fun TaskStatus.label(): String =
    when (this) {
        TaskStatus.PENDING -> "等待"
        TaskStatus.RUNNING -> "下载中"
        TaskStatus.SAVED -> "完成"
        TaskStatus.SKIPPED_DUPLICATE -> "重复"
        TaskStatus.NEEDS_MANUAL_OPEN -> "需手动"
        TaskStatus.FAILED -> "失败"
        TaskStatus.CANCELLED -> "取消"
    }

private fun formatTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
