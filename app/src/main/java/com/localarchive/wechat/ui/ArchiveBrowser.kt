package com.localarchive.wechat.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.localarchive.wechat.data.repository.ArchiveRepository
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

data class StoredArticleInput(
    val title: String,
    val sourceUrl: String,
    val archiveDir: String,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArchiveBrowser(
    repository: ArchiveRepository,
    initialUrl: String,
    linkId: Long?,
    autoSaveOnLoad: Boolean = true,
    storedArticle: StoredArticleInput? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val localUrl = remember(storedArticle?.archiveDir) {
        storedArticle?.archiveDir
            ?.let { File(it) }
            ?.let { dir ->
                when {
                    dir.resolve("readable.html").exists() -> dir.resolve("readable.html").toURI().toString()
                    dir.resolve("raw.html").exists() -> dir.resolve("raw.html").toURI().toString()
                    else -> null
                }
            }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(initialUrl, storedArticle?.sourceUrl) {
        mutableStateOf(storedArticle?.sourceUrl ?: initialUrl)
    }
    var title by remember(storedArticle?.title) { mutableStateOf(storedArticle?.title.orEmpty()) }
    var offlineMode by remember(localUrl) { mutableStateOf(localUrl != null) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var autoSavedUrls by remember { mutableStateOf(emptySet<String>()) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (offlineMode) "正在查看本地保存内容" else "打开中")
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }

    fun saveCurrentPage(view: WebView, nextStatus: String) {
        if (saving || offlineMode) return
        saving = true
        status = nextStatus
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encoded ->
            val html = decodeJavascriptString(encoded)
            scope.launch {
                runCatching {
                    repository.saveCurrentPage(
                        linkId = linkId,
                        currentUrl = currentUrl,
                        rawHtml = html,
                        fallbackTitle = title,
                        discoverDepthOne = true,
                    )
                }.onSuccess { result ->
                    status = "已保存：发现 ${result.discoveredCount}，入队 ${result.queuedCount}"
                }.onFailure { error ->
                    status = error.message ?: "保存失败"
                }
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTextButton(onClick = { webView?.goBack() }, enabled = canGoBack) { Text("<") }
            CompactTextButton(onClick = { webView?.goForward() }, enabled = canGoForward) { Text(">") }
            CompactTextButton(
                onClick = {
                    if (offlineMode) {
                        offlineMode = false
                        loading = true
                        status = "打开在线页面"
                    } else {
                        loading = true
                        status = "刷新在线页面"
                        webView?.reload()
                    }
                },
            ) { Text("刷新") }
            Button(
                onClick = {
                    val view = webView ?: return@Button
                    applyDesktopViewport(view) {
                        saveCurrentPage(view, "正在覆盖保存")
                    }
                },
                enabled = !offlineMode && !saving && !loading,
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 40.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(if (saving) "保存中" else "保存")
            }
            CompactTextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(if (offlineMode) currentUrl else currentUrl))
                    status = "已复制链接"
                },
            ) { Text("复制") }
            CompactTextButton(onClick = onClose) { Text("关闭") }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            text = title.ifBlank { currentUrl },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = status,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        if (offlineMode && localUrl == null) {
            Text(
                text = "本地归档文件不存在，请刷新后重新保存。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, newTitle: String?) {
                                if (!offlineMode) title = newTitle.orEmpty()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                val loadedUrl = url ?: view.url.orEmpty()
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                loading = false
                                if (offlineMode || loadedUrl.startsWith("file:", ignoreCase = true)) {
                                    status = "正在查看本地保存内容"
                                    return
                                }
                                currentUrl = loadedUrl
                                status = "可保存当前页面"
                                applyDesktopViewport(view) {
                                    if (autoSaveOnLoad && loadedUrl.isNotBlank() && loadedUrl !in autoSavedUrls) {
                                        autoSavedUrls = autoSavedUrls + loadedUrl
                                        saveCurrentPage(view, "正在自动保存")
                                    }
                                }
                            }
                        }
                        webView = this
                    }
                },
                update = { view ->
                    val targetUrl = if (offlineMode) localUrl else currentUrl
                    if (targetUrl.isNullOrBlank()) return@AndroidView
                    applyBrowserSettings(view, offlineMode)
                    if (targetUrl != lastLoadedUrl) {
                        loading = true
                        lastLoadedUrl = targetUrl
                        if (offlineMode) {
                            view.loadUrl(targetUrl)
                        } else {
                            view.loadUrl(targetUrl, desktopHeaders())
                        }
                    }
                },
            )
        }
        if (saving) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Text(" 正在写入本机归档")
            }
        }
    }
}

private fun applyBrowserSettings(view: WebView, offlineMode: Boolean) {
    view.settings.apply {
        javaScriptEnabled = !offlineMode
        allowFileAccess = offlineMode
        allowContentAccess = true
        domStorageEnabled = !offlineMode
        loadsImagesAutomatically = true
        useWideViewPort = !offlineMode
        loadWithOverviewMode = !offlineMode
        textZoom = 100
        minimumFontSize = 1
        minimumLogicalFontSize = 1
        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        defaultTextEncodingName = "utf-8"
        if (!offlineMode) {
            userAgentString = DESKTOP_USER_AGENT
            javaScriptCanOpenWindowsAutomatically = true
        }
    }
    view.setInitialScale(if (offlineMode) 0 else DESKTOP_INITIAL_SCALE)
}

private fun applyDesktopViewport(view: WebView, onApplied: () -> Unit = {}) {
    view.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT) {
        onApplied()
    }
}

@Composable
private fun CompactTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 40.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        content()
    }
}

private fun decodeJavascriptString(encoded: String?): String {
    if (encoded.isNullOrBlank() || encoded == "null") return ""
    return runCatching { JSONArray("[$encoded]").optString(0) }.getOrDefault("")
}

private fun desktopHeaders(): Map<String, String> =
    mapOf("User-Agent" to DESKTOP_USER_AGENT)

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

private const val DESKTOP_VIEWPORT_WIDTH = 1280
private const val DESKTOP_INITIAL_SCALE = 35

private val DESKTOP_VIEWPORT_SCRIPT = """
    (function() {
      var content = 'width=$DESKTOP_VIEWPORT_WIDTH, initial-scale=0.35, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes';
      var meta = document.querySelector('meta[name="viewport"]');
      if (!meta) {
        meta = document.createElement('meta');
        meta.setAttribute('name', 'viewport');
        document.head.appendChild(meta);
      }
      meta.setAttribute('content', content);
      document.documentElement.style.setProperty('min-width', '${DESKTOP_VIEWPORT_WIDTH}px', 'important');
      if (document.body) {
        document.body.style.setProperty('min-width', '${DESKTOP_VIEWPORT_WIDTH}px', 'important');
      }
    })();
""".trimIndent()
