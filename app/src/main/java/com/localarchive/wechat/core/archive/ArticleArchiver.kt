package com.localarchive.wechat.core.archive

import com.localarchive.wechat.core.ArchiveLog
import com.localarchive.wechat.core.parser.ParsedArticle
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A fully built, in-memory archive ready to be written as one copy. */
data class BuiltArchive(
    val dirName: String,
    /** Top-level files: index.html / text.txt / metadata.json. */
    val files: Map<String, ByteArray>,
    /** Local image bytes keyed by file name, written under assets/. */
    val assets: Map<String, ByteArray>,
    val imagesTotal: Int,
    val imagesDownloaded: Int,
)

/**
 * Builds a self-contained article archive: a clean, offline-readable `index.html`
 * with every image downloaded and rewritten to a local `assets/` reference, plus
 * `text.txt` and `metadata.json`. No markdown — HTML is the storage format. The
 * folder is `{slug}_{urlHash}` (a stable unique suffix, like the desktop script).
 */
object ArticleArchiver {
    suspend fun build(
        linkId: Long,
        normalizedUrl: String,
        originalUrl: String,
        parsed: ParsedArticle,
        // Bytes already fetched by the WebView (keyed by normalized URL) — reused
        // so each image is downloaded exactly once, not again here.
        capturedImages: Map<String, ByteArray> = emptyMap(),
    ): BuiltArchive {
        // remote url -> (local file name, bytes)
        val downloaded = downloadImages(parsed.imageUrls, referer = normalizedUrl, captured = capturedImages)
        // Key by the NORMALIZED url (entity-decoded, fragment-stripped) so the
        // entity-encoded URLs in the page's <img> tags still match.
        val assetMap = downloaded.entries.associate { (url, value) ->
            normalizeImageUrl(url) to "assets/${value.first}"
        }
        val assets = downloaded.values.associate { it.first to it.second }
        val rewritten = rewriteImageSources(parsed.contentHtml, assetMap)

        val files = linkedMapOf(
            "index.html" to buildIndexHtml(parsed, normalizedUrl, rewritten).toByteArray(),
            "text.txt" to parsed.text.toByteArray(),
            "metadata.json" to buildMetadataJson(
                parsed = parsed,
                normalizedUrl = normalizedUrl,
                originalUrl = originalUrl,
                imagesTotal = parsed.imageUrls.size,
                imagesDownloaded = assets.size,
            ).toByteArray(),
        )
        return BuiltArchive(
            dirName = articleDirName(parsed.title, normalizedUrl),
            files = files,
            assets = assets,
            imagesTotal = parsed.imageUrls.size,
            imagesDownloaded = assets.size,
        )
    }

    /** `{slug}_{16-hex-hash-of-url}` — stable + unique, mirroring the desktop layout. */
    fun articleDirName(title: String, normalizedUrl: String): String {
        val slug = title
            .ifBlank { "untitled" }
            .replace(invalidPathChars, "_")
            .replace(whitespacePattern, "_")
            .trim('_')
            .take(48)
            .ifBlank { "article" }
        return "${slug}_${urlHash(normalizedUrl)}"
    }

    private fun urlHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    // --- generated documents (internal so unit tests can exercise them offline) ---

    internal fun buildIndexHtml(parsed: ParsedArticle, sourceUrl: String, contentHtml: String): String {
        val metaLine = listOfNotNull(
            parsed.accountName.takeIf { it.isNotBlank() },
            parsed.publishTime?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        return buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"zh-CN\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\">")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("  <title>${parsed.title.escapeHtml()}</title>")
            appendLine("  <style>")
            appendLine("    body { max-width: 820px; margin: 0 auto; padding: 16px 18px; line-height: 1.75; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif; color: #111827; word-wrap: break-word; }")
            appendLine("    img { max-width: 100% !important; width: auto; height: auto !important; display: block; margin: 10px auto; }")
            appendLine("    section, p, div, span { max-width: 100% !important; }")
            appendLine("    pre { overflow: auto; padding: 12px; background: #f6f8fa; }")
            appendLine("    .meta { color: #64748b; margin: 6px 0 20px; font-size: 0.92rem; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <h1>${parsed.title.escapeHtml()}</h1>")
            if (metaLine.isNotBlank()) appendLine("  <div class=\"meta\">${metaLine.escapeHtml()}</div>")
            appendLine("  <p><a href=\"${sourceUrl.escapeHtml()}\">原文链接</a></p>")
            appendLine(contentHtml)
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    internal fun buildMetadataJson(
        parsed: ParsedArticle,
        normalizedUrl: String,
        originalUrl: String,
        imagesTotal: Int,
        imagesDownloaded: Int,
    ): String = buildString {
        appendLine("{")
        appendLine("  \"title\": ${parsed.title.jsonString()},")
        appendLine("  \"account_name\": ${parsed.accountName.jsonString()},")
        appendLine("  \"author\": ${parsed.author.orEmpty().jsonString()},")
        appendLine("  \"publish_time\": ${parsed.publishTime.orEmpty().jsonString()},")
        appendLine("  \"source_url\": ${normalizedUrl.jsonString()},")
        appendLine("  \"original_url\": ${originalUrl.jsonString()},")
        appendLine("  \"cover\": ${parsed.coverUrl.orEmpty().jsonString()},")
        appendLine("  \"content_hash\": ${parsed.contentHash.jsonString()},")
        appendLine("  \"archive_mode\": ${"android_webview".jsonString()},")
        appendLine("  \"images_total\": $imagesTotal,")
        appendLine("  \"images_downloaded\": $imagesDownloaded")
        appendLine("}")
    }

    /**
     * Point every <img> at its local copy and make it render cleanly offline:
     * match by entity-decoded URL, inject a real `src`, drop lazy/remote source
     * attributes, and strip WeChat's inline width/height (which otherwise forces
     * images to tiny fixed sizes).
     */
    internal fun rewriteImageSources(html: String, assetMap: Map<String, String>): String {
        if (assetMap.isEmpty()) return html
        return imgTagPattern.replace(html) { match ->
            var tag = match.value
            val local = imgCandidateUrls(tag).firstNotNullOfOrNull { assetMap[it] }
            if (local != null) {
                tag = if (realSrcPattern.containsMatchIn(tag)) {
                    realSrcPattern.replace(tag) { "src=\"$local\"" }
                } else {
                    tag.replaceFirst("<img", "<img src=\"$local\"")
                }
            }
            tag = lazyAttrPattern.replace(tag, "")
            tag = imgStyleAttrPattern.replace(tag, "")
            tag = dimensionAttrPattern.replace(tag, "")
            tag
        }
    }

    private fun imgCandidateUrls(tag: String): List<String> =
        imgUrlAttrPattern.findAll(tag).mapNotNull { m ->
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (value.isBlank() || value.startsWith("data:", ignoreCase = true)) null else normalizeImageUrl(value)
        }.toList()

    private fun normalizeImageUrl(url: String): String =
        url.decodeEntities().substringBefore('#').trim()

    // --- image download ---

    private suspend fun downloadImages(
        imageUrls: List<String>,
        referer: String,
        captured: Map<String, ByteArray>,
    ): Map<String, Pair<String, ByteArray>> = coroutineScope {
        if (imageUrls.isEmpty()) return@coroutineScope emptyMap()
        val started = ArchiveLog.now()
        val reused = AtomicInteger()
        val downloaded = AtomicInteger()
        val failed = AtomicInteger()
        val gate = Semaphore(MAX_IMAGE_CONCURRENCY)
        val result = imageUrls.mapIndexed { index, url ->
            async(Dispatchers.IO) {
                val seq = (index + 1).toString().padStart(3, '0')
                val cachedBytes = captured[normalizeImageUrl(url)]
                if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                    // Reuse what the WebView already downloaded — no second fetch.
                    reused.incrementAndGet()
                    val ext = sniffImageExt(cachedBytes) ?: extensionFor(url, "")
                    url to ("image_$seq.$ext" to cachedBytes)
                } else {
                    gate.withPermit {
                        runCatching {
                            val image = downloadImage(url, referer)
                            downloaded.incrementAndGet()
                            val ext = extensionFor(url, image.contentType)
                            url to ("image_$seq.$ext" to image.bytes)
                        }.getOrElse { failed.incrementAndGet(); null }
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        ArchiveLog.done(
            "【归档】图片处理", started,
            "复用缓存 ${reused.get()} · 网络补下 ${downloaded.get()} · 失败 ${failed.get()}",
        )
        result
    }

    /** Detect image type from magic bytes (captured images have no content-type). */
    private fun sniffImageExt(b: ByteArray): String? = when {
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() -> "png"
        b.size >= 3 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() -> "gif"
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
            b[8] == 0x57.toByte() && b[9] == 0x45.toByte() -> "webp"
        else -> null
    }

    private fun downloadImage(rawUrl: String, referer: String): DownloadedImage {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", MOBILE_USER_AGENT)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return DownloadedImage(
                bytes = connection.inputStream.use { it.readBytes() },
                contentType = connection.contentType.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extensionFor(rawUrl: String, contentType: String): String =
        when {
            contentType.contains("png", ignoreCase = true) -> "png"
            contentType.contains("webp", ignoreCase = true) -> "webp"
            contentType.contains("gif", ignoreCase = true) -> "gif"
            contentType.contains("svg", ignoreCase = true) -> "svg"
            contentType.contains("jpeg", ignoreCase = true) || contentType.contains("jpg", ignoreCase = true) -> "jpg"
            rawUrl.substringBefore('?').endsWith(".png", ignoreCase = true) -> "png"
            rawUrl.substringBefore('?').endsWith(".webp", ignoreCase = true) -> "webp"
            rawUrl.substringBefore('?').endsWith(".gif", ignoreCase = true) -> "gif"
            rawUrl.substringBefore('?').endsWith(".svg", ignoreCase = true) -> "svg"
            else -> "jpg"
        }

    private data class DownloadedImage(val bytes: ByteArray, val contentType: String)

    // --- text helpers ---

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.decodeEntities(): String =
        replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

    private fun String.jsonString(): String = buildString {
        append('"')
        for (ch in this@jsonString) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private const val MAX_IMAGE_CONCURRENCY = 8
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val DOT_IC = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val invalidPathChars = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val whitespacePattern = Regex("""\s+""")
    private val imgTagPattern = Regex("""<img\b[^>]*>""", DOT_IC)
    private val imgUrlAttrPattern =
        Regex("""\b(?:data-src|data-original|data-croporisrc|data-backsrc|data-wxsrc|src)\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val realSrcPattern =
        Regex("""(?<![\w-])src\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)
    private val lazyAttrPattern =
        Regex("""\s(?:data-src|data-original|data-croporisrc|data-backsrc|data-wxsrc|srcset|data-srcset)\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)
    private val imgStyleAttrPattern =
        Regex("""\sstyle\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)
    private val dimensionAttrPattern =
        Regex("""\s(?:width|height)\s*=\s*("[^"]*"|'[^']*'|[0-9]+)""", RegexOption.IGNORE_CASE)
}
