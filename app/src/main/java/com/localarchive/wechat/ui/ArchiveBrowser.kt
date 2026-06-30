package com.localarchive.wechat.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.runtime.LaunchedEffect
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
import com.localarchive.wechat.core.ArchiveLog
import com.localarchive.wechat.data.repository.ArchiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

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
    // 离线归档在 Downloads（分库存储，无 file:// 可用）：读 index.html 文本 + 拦截 assets/* 喂字节。
    val storedDir = storedArticle?.archiveDir
    // 在线浏览时拦截到的图片字节（按去掉#的 URL 作键）：归档时复用，避免二次下载。
    val imageCache = remember { ConcurrentHashMap<String, ByteArray>() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(initialUrl, storedArticle?.sourceUrl) {
        mutableStateOf(storedArticle?.sourceUrl ?: initialUrl)
    }
    var title by remember(storedArticle?.title) { mutableStateOf(storedArticle?.title.orEmpty()) }
    var offlineMode by remember(storedDir) { mutableStateOf(storedDir != null) }
    var offlineHtml by remember(storedDir) { mutableStateOf<String?>(null) }
    var offlineMissing by remember(storedDir) { mutableStateOf(false) }
    var offlineLoaded by remember(storedDir) { mutableStateOf(false) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    // 本次在线浏览只自动保存一次：微信会重定向到多个 URL，按原 URL 去重会被重复触发保存。
    var autoSaved by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (storedDir != null) "正在查看本地保存内容" else "打开中")
    }

    // 进入离线模式时，从 Downloads 读取 index.html；文件被用户删了就提示重存。
    LaunchedEffect(storedDir, offlineMode) {
        if (storedDir != null && offlineMode && offlineHtml == null && !offlineMissing) {
            val bytes = repository.readArchiveFile(storedDir, "index.html")
            if (bytes == null) {
                offlineMissing = true
                status = "本地文件已被删除，请刷新后重新保存"
            } else {
                offlineHtml = bytes.decodeToString()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }

    fun saveCurrentPage(view: WebView, nextStatus: String) {
        if (saving || offlineMode) return
        saving = true
        scope.launch {
            val flowStart = ArchiveLog.now()
            ArchiveLog.log("【保存】开始 url=$currentUrl")
            // 触发懒加载：把 data-src 落到 src，并逐屏滚动到底。
            status = "正在加载完整内容…"
            view.evalAsync(PREPARE_LAZY_LOAD_SCRIPT)
            ArchiveLog.log("【保存】已注入懒加载脚本(data-src→src + 逐屏滚动)")
            // 进度轮询：按 <img> 的 complete 进度等待（拦截器在后台把图下完即算就绪），
            // 取代固定硬延时——快的页面 1 秒内就走，慢的最多等 MAX_LAZY_WAIT_MS。
            var waited = 0
            var ready = false
            var lastProgress = ""
            while (waited < MAX_LAZY_WAIT_MS) {
                delay(LAZY_POLL_MS.toLong())
                waited += LAZY_POLL_MS
                val progress = view.evalAsync(IMAGES_PROGRESS_SCRIPT).trim('"')
                if (progress != lastProgress) {
                    ArchiveLog.log("【保存】懒加载进度 图片 $progress complete (已等 ${waited}ms, 已拦截 ${imageCache.size} 张)")
                    lastProgress = progress
                }
                val parts = progress.split(",")
                if (parts.size == 2 && parts[1] != "0" && parts[0] == parts[1]) { ready = true; break }
            }
            ArchiveLog.log(
                if (ready) "【保存】图片全部就绪, 等待 ${waited}ms"
                else "【保存】等待达上限 ${MAX_LAZY_WAIT_MS}ms 仍未全部完成, 继续抓取",
            )
            status = nextStatus
            val encoded = view.evalAsync(OUTER_HTML_SCRIPT)
            // 解码这串很大的 outerHTML（JSON 反转义）也很重，放后台线程，别卡主线程。
            val html = withContext(Dispatchers.Default) { decodeJavascriptString(encoded) }
            ArchiveLog.log("【保存】抓取 outerHTML ${html.length} 字符, 浏览阶段共拦截缓存 ${imageCache.size} 张图")
            if (looksLikeVerificationWall(html)) {
                // 抓到的是验证墙而不是文章：不保存，并允许跳转到真文章后再保存一次。
                ArchiveLog.log("【保存】命中验证墙, 放弃保存")
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
                    // 复用浏览时已下载的图片字节，归档不再二次下载。
                    capturedImages = imageCache.toMap(),
                )
            }.onSuccess { result ->
                status = "已保存到本机" + if (result.queuedCount > 0) " · ${result.queuedCount} 个专辑" else ""
                ArchiveLog.done("【保存】完成 标题=${result.title}", flowStart, "专辑=${result.queuedCount}")
            }.onFailure { error ->
                status = "保存失败：${error.message ?: "未知错误"}"
                ArchiveLog.log("【保存】失败: ${error.message ?: error::class.java.simpleName}")
            }
            saving = false
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
        if (offlineMode && offlineMissing) {
            Text(
                text = "本地归档文件已被删除，请点刷新重新联网保存。",
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
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                // 离线模式：把 https://archive.local/assets/* 的请求从 Downloads 里喂回去。
                                if (offlineMode) {
                                    val dir = storedDir ?: return null
                                    val url = request.url.toString()
                                    if (!url.startsWith(ARCHIVE_BASE)) return null
                                    val rel = url.removePrefix(ARCHIVE_BASE)
                                        .substringBefore('#').substringBefore('?')
                                    if (rel.isBlank() || rel.endsWith("/")) return null
                                    val bytes = repository.readArchiveFileSync(dir, rel) ?: return null
                                    return WebResourceResponse(guessMimeType(rel), "utf-8", ByteArrayInputStream(bytes))
                                }
                                // 在线浏览：只拦截微信图床，把图片下载一次并缓存，归档复用、不再二次下载。
                                val host = request.url.host.orEmpty()
                                if (!host.contains("qpic.cn") && !host.contains("qlogo.cn")) return null
                                val u = request.url.toString()
                                val referer = request.requestHeaders["Referer"] ?: currentUrl
                                val fetched = fetchImageBytes(u, referer)
                                if (fetched == null) {
                                    ArchiveLog.log("【拦截】图片下载失败, 交回 WebView 自行加载: $u")
                                    return null
                                }
                                imageCache[u.substringBefore('#')] = fetched.first
                                ArchiveLog.log("【拦截】第 ${imageCache.size} 张 ${fetched.first.size / 1024}KB $u")
                                val mime = fetched.second.substringBefore(';').trim().ifBlank { "image/jpeg" }
                                return WebResourceResponse(mime, null, ByteArrayInputStream(fetched.first))
                            }

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
                                ArchiveLog.log("【页面】加载完成 $loadedUrl")
                                if (loadedUrl.contains("wappoc_appmsgcaptcha") || loadedUrl.contains("safe.weixin")) {
                                    // 微信常在验证页停留片刻后【自动跳回真文章】，不要一上来就吓人：
                                    // 先显示"正在通过验证"，只有若干秒后仍停在验证页才提示需要人工验证。
                                    ArchiveLog.log("【页面】进入安全验证页, 等待自动跳转")
                                    status = "加载中…"
                                    scope.launch {
                                        delay(WALL_GRACE_MS)
                                        val now = webView?.url.orEmpty()
                                        if (now.contains("wappoc_appmsgcaptcha") || now.contains("safe.weixin")) {
                                            ArchiveLog.log("【页面】验证页停留超时, 提示人工验证")
                                            status = "页面要求验证：请在页面内完成验证（滑块/点按）后会自动保存"
                                        }
                                    }
                                    return
                                }
                                status = "页面已加载"
                                if (autoSaveOnLoad && !autoSaved && loadedUrl.isNotBlank()) {
                                    autoSaved = true
                                    ArchiveLog.log("【页面】触发自动保存")
                                    saveCurrentPage(view, "正在保存…")
                                }
                            }
                        }
                        webView = this
                    }
                },
                update = { view ->
                    applyBrowserSettings(view, offlineMode)
                    if (offlineMode) {
                        val html = offlineHtml
                        if (html != null && !offlineLoaded) {
                            offlineLoaded = true
                            loading = true
                            view.loadDataWithBaseURL(ARCHIVE_BASE, html, "text/html", "utf-8", null)
                        }
                    } else {
                        val targetUrl = currentUrl
                        if (targetUrl.isNotBlank() && targetUrl != lastLoadedUrl) {
                            loading = true
                            lastLoadedUrl = targetUrl
                            view.loadUrl(targetUrl)
                        }
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

// 离线阅读器的虚拟 base：相对的 assets/* 会解析到这个前缀，被 shouldInterceptRequest 命中。
private const val ARCHIVE_BASE = "https://archive.local/"

private fun guessMimeType(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "json" -> "application/json"
        else -> "image/jpeg"
    }

private const val FALLBACK_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

// 懒加载进度等待上限与轮询间隔（取代固定硬延时）。
private const val MAX_LAZY_WAIT_MS = 8000
private const val LAZY_POLL_MS = 350
// 进入微信验证页后，给它自动跳回真文章的宽限时间；超时才提示需人工验证。
private const val WALL_GRACE_MS = 6000L

// 返回 "已complete数,总数"，用于进度日志与就绪判断。
private const val IMAGES_PROGRESS_SCRIPT =
    "(function(){var a=document.images,n=0;for(var i=0;i<a.length;i++){if(a[i].complete)n++;}return n+','+a.length;})()"

// 只抓正文 #js_content + 必要的元信息，而不是整页 4MB outerHTML —— 解析输入从
// 数 MB 降到约 100KB，解析耗时从十几秒降到亚秒级。没有正文时退回整页（让验证墙检测生效）。
private val OUTER_HTML_SCRIPT = """
(function(){
  try {
    var c = document.getElementById('js_content');
    if (!c) { return document.documentElement.outerHTML; }
    var metas = '';
    var ms = document.querySelectorAll('meta[property], meta[name="description"]');
    for (var i = 0; i < ms.length; i++) { metas += ms[i].outerHTML; }
    var vars = '';
    try { if (window.nickname) { vars += 'var nickname="' + String(window.nickname).replace(/"/g, '') + '";'; } } catch (e) {}
    try { if (window.ct) { vars += 'var ct="' + window.ct + '";'; } } catch (e) {}
    var title = document.getElementById('activity-name');
    var name = document.getElementById('js_name');
    var author = document.getElementById('js_author_name');
    return '<html><head>' + metas + '<scr' + 'ipt>' + vars + '</scr' + 'ipt></head><body>'
      + (title ? title.outerHTML : '')
      + (name ? name.outerHTML : '')
      + (author ? author.outerHTML : '')
      + c.outerHTML + '</div></body></html>';
  } catch (e) { return document.documentElement.outerHTML; }
})()
""".trimIndent()

/** Await an evaluateJavascript result (its callback runs on the UI thread). */
private suspend fun WebView.evalAsync(script: String): String =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result -> if (cont.isActive) cont.resumeWith(Result.success(result ?: "")) }
    }

/**
 * Fetch image bytes once, used by the request interceptor so the WebView render
 * and the archiver share a single download. Returns (bytes, contentType) or null.
 */
private fun fetchImageBytes(urlStr: String, referer: String): Pair<ByteArray, String>? = runCatching {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", FALLBACK_MOBILE_USER_AGENT)
        setRequestProperty("Referer", referer)
        setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
    }
    try {
        if (conn.responseCode !in 200..299) return@runCatching null
        conn.inputStream.use { it.readBytes() } to conn.contentType.orEmpty()
    } finally {
        conn.disconnect()
    }
}.getOrNull()

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
