package com.localarchive.wechat.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 本次在线浏览只自动保存一次：微信会重定向到多个 URL，按原 URL 去重会被重复触发保存。
    var autoSaved by remember { mutableStateOf(false) }
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
        // 微信文章图片是懒加载：先把 data-src 落到 src、逐屏滚动到底触发懒加载，等内容补齐
        // 再抓取整页 HTML 保存，避免“只存到一半图”或漏掉滚动后才出现的内容。
        status = "正在加载完整内容…"
        view.evaluateJavascript(PREPARE_LAZY_LOAD_SCRIPT) {
            scope.launch {
                delay(LAZY_LOAD_SETTLE_MS)
                status = nextStatus
                view.evaluateJavascript(OUTER_HTML_SCRIPT) { encoded ->
                    scope.launch {
                        // 解码这串很大的 outerHTML（JSON 反转义）也很重，放后台线程，别卡主线程。
                        val html = withContext(Dispatchers.Default) { decodeJavascriptString(encoded) }
                        if (looksLikeVerificationWall(html)) {
                            // 抓到的是验证墙而不是文章：不保存（不产生“未命名”垃圾），并允许等它
                            // 自动跳转到真文章后再保存一次。
                            status = "⚠ 命中验证墙，未保存。等待自动跳转或在页面内完成验证。"
                            autoSaved = false
                            saving = false
                            return@launch
                        }
                        runCatching {
                            repository.saveCurrentPage(
                                linkId = linkId,
                                currentUrl = currentUrl,
                                rawHtml = html,
                                fallbackTitle = title,
                                discoverDepthOne = true,
                            )
                        }.onSuccess { result ->
                            status = "已保存到本机" + if (result.queuedCount > 0) " · ${result.queuedCount} 个专辑" else ""
                        }.onFailure { error ->
                            status = "保存失败：${error.message ?: "未知错误"}"
                        }
                        saving = false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                }
                IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                }
                IconButton(
                    onClick = {
                        // 刷新是“重新联网并覆盖保存”，允许本次再自动保存一次。
                        autoSaved = false
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
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(currentUrl))
                        status = "已复制链接"
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制链接")
                }
                FilledIconButton(
                    onClick = {
                        val view = webView ?: return@FilledIconButton
                        saveCurrentPage(view, "正在保存…")
                    },
                    enabled = !offlineMode && !saving && !loading,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = if (saving) "保存中" else "保存")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        }
        if (loading || saving) {
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
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
                                // 微信会重定向到带参数的规范 URL；同步 lastLoadedUrl，避免 update
                                // 把重定向后的地址当成新目标再次 loadUrl，从而打断正在进行的保存。
                                lastLoadedUrl = loadedUrl
                                if (loadedUrl.contains("wappoc_appmsgcaptcha") || loadedUrl.contains("safe.weixin")) {
                                    // 撞到微信验证墙：不自动保存验证页，提示用户在页面内过验证后再保存。
                                    status = "⚠ 页面要求验证，请在页面内完成验证后再点保存"
                                    return
                                }
                                status = "页面已加载"
                                if (autoSaveOnLoad && !autoSaved && loadedUrl.isNotBlank()) {
                                    autoSaved = true
                                    saveCurrentPage(view, "正在保存…")
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
                        view.loadUrl(targetUrl)
                    }
                },
            )
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
        useWideViewPort = true
        loadWithOverviewMode = true
        textZoom = 100
        minimumFontSize = 1
        minimumLogicalFontSize = 1
        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        defaultTextEncodingName = "utf-8"
        if (!offlineMode) {
            // 伪装成本机常规 Chrome 移动浏览器：去掉 WebView 的“; wv”和“Version/4.0”标记，
            // 让微信看到的 UA 与系统 Chrome 一致，减少“环境异常/去验证”墙。
            userAgentString = normalBrowserUserAgent(view.context)
            javaScriptCanOpenWindowsAutomatically = true
        }
    }
    view.setInitialScale(0)
}

private fun normalBrowserUserAgent(context: android.content.Context): String =
    runCatching {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("""\s*Version/\d+\.\d+"""), "")
    }.getOrDefault(FALLBACK_MOBILE_USER_AGENT)

private fun decodeJavascriptString(encoded: String?): String {
    if (encoded.isNullOrBlank() || encoded == "null") return ""
    return runCatching { JSONArray("[$encoded]").optString(0) }.getOrDefault("")
}

// 正文正向校验：有 js_content 正文容器即真文章；否则若含验证/风控标记，判为验证墙。
private fun looksLikeVerificationWall(html: String): Boolean {
    if (html.contains("js_content")) return false
    return html.contains("环境异常") ||
        html.contains("完成验证") ||
        html.contains("wappoc_appmsgcaptcha") ||
        html.contains("appmsg_captcha") ||
        html.contains("操作过于频繁")
}

private const val FALLBACK_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

// 抓取前等待懒加载内容补齐的时间。
private const val LAZY_LOAD_SETTLE_MS = 2500L

private const val OUTER_HTML_SCRIPT = "(function(){return document.documentElement.outerHTML;})()"

// 触发微信文章的懒加载：把 data-src/data-original 落到 src，并逐屏滚动到底再回到顶部。
private val PREPARE_LAZY_LOAD_SCRIPT = """
    (function() {
      try {
        var imgs = document.querySelectorAll('img[data-src],img[data-original]');
        for (var i = 0; i < imgs.length; i++) {
          var s = imgs[i].getAttribute('data-src') || imgs[i].getAttribute('data-original');
          if (s) imgs[i].setAttribute('src', s);
        }
        var step = Math.max(600, window.innerHeight || 800), y = 0;
        function hop() {
          y += step;
          window.scrollTo(0, y);
          if (y < (document.body ? document.body.scrollHeight : 0)) {
            setTimeout(hop, 120);
          } else {
            window.scrollTo(0, document.body ? document.body.scrollHeight : 0);
            setTimeout(function() { window.scrollTo(0, 0); }, 200);
          }
        }
        hop();
      } catch (e) {}
    })();
""".trimIndent()
