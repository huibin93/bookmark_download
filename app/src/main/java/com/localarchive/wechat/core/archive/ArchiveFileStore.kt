package com.localarchive.wechat.core.archive

import android.content.Context
import com.localarchive.wechat.core.parser.ParsedArticle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ArchiveFileStore(context: Context) {
    private val root = File(context.filesDir, "archive/articles")

    fun saveArticle(linkId: Long, normalizedUrl: String, rawHtml: String, parsed: ParsedArticle): String {
        val dir = File(root, linkId.toString())
        if (!dir.exists()) dir.mkdirs()
        val assetsDir = File(dir, "assets").apply { mkdirs() }
        val assetMap = downloadImages(parsed.imageUrls, normalizedUrl, assetsDir)
        File(dir, "raw.html").writeText(rawHtml)
        File(dir, "readable.html").writeText(parsed.toReadableDocument(assetMap))
        File(dir, "text.txt").writeText(parsed.text)
        File(dir, "metadata.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"title\": ${parsed.title.jsonString()},")
                appendLine("  \"account_name\": ${parsed.accountName.jsonString()},")
                appendLine("  \"normalized_url\": ${normalizedUrl.jsonString()},")
                appendLine("  \"content_hash\": ${parsed.contentHash.jsonString()},")
                appendLine("  \"image_count\": ${assetMap.size}")
                appendLine("}")
            },
        )
        File(dir, "links.json").writeText(
            parsed.discoveredLinks.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { link ->
                """  {"url": ${link.normalizedUrl.jsonString()}, "anchor_text": ${link.anchorText.jsonString()}}"""
            },
        )
        return dir.absolutePath
    }

    private fun downloadImages(imageUrls: List<String>, referer: String, assetsDir: File): Map<String, String> {
        if (imageUrls.isEmpty()) return emptyMap()
        return imageUrls.mapIndexedNotNull { index, url ->
            runCatching {
                val bytesAndType = downloadImage(url, referer)
                val extension = extensionFor(url, bytesAndType.contentType)
                val fileName = "image_${(index + 1).toString().padStart(3, '0')}.$extension"
                val file = File(assetsDir, fileName)
                file.writeBytes(bytesAndType.bytes)
                url to "assets/$fileName"
            }.getOrNull()
        }.toMap()
    }

    private fun downloadImage(rawUrl: String, referer: String): DownloadedImage {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
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

    private fun String.jsonString(): String =
        buildString {
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

    private fun ParsedArticle.toReadableDocument(assetMap: Map<String, String>): String =
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${title.escapeHtml()}</title>
          <style>
            body { font-family: sans-serif; line-height: 1.7; padding: 20px; color: #111827; }
            h1 { font-size: 1.45rem; line-height: 1.35; }
            .meta { color: #64748b; margin-bottom: 24px; }
            img { max-width: 100%; height: auto; }
          </style>
        </head>
        <body>
          <h1>${title.escapeHtml()}</h1>
          <div class="meta">${accountName.escapeHtml()} ${publishTime.orEmpty().escapeHtml()}</div>
          ${contentHtml.rewriteImageSources(assetMap)}
        </body>
        </html>
        """.trimIndent()

    private fun String.rewriteImageSources(assetMap: Map<String, String>): String {
        if (assetMap.isEmpty()) return this
        return imgTagPattern.replace(this) { match ->
            var tag = match.value
            val local = assetMap.entries.firstOrNull { (remote, _) -> tag.contains(remote) }?.value
                ?: return@replace tag
            tag = attrWithRemoteImagePattern.replace(tag) { attr ->
                val name = attr.groupValues[1]
                val quote = attr.groupValues[2]
                "$name=$quote$local$quote"
            }
            if (!srcAttrPattern.containsMatchIn(tag)) {
                tag = tag.replaceFirst("<img", "<img src=\"$local\"")
            }
            tag
        }
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private data class DownloadedImage(
        val bytes: ByteArray,
        val contentType: String,
    )

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private val imgTagPattern = Regex("""<img\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val attrWithRemoteImagePattern = Regex("""\b(src|data-src|data-original)\s*=\s*(["'])[^"']*\2""", RegexOption.IGNORE_CASE)
        private val srcAttrPattern = Regex("""\bsrc\s*=""", RegexOption.IGNORE_CASE)
    }
}
