package com.localarchive.wechat.core.archive

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.localarchive.wechat.core.parser.ParsedArticle
import com.localarchive.wechat.data.db.ArticleEntity
import java.io.File

class DownloadsArchiveExporter(private val context: Context) {
    fun ensureRootFolder() {
        writeText(
            relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_FOLDER/",
            fileName = "README.txt",
            mimeType = "text/plain",
            text = "WechatArchive stores exported WeChat article archives in the articles folder.",
        )
    }

    fun exportArticle(linkId: Long, normalizedUrl: String, rawHtml: String, parsed: ParsedArticle): String {
        val dirName = buildArticleDirName(linkId, parsed)
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_FOLDER/articles/$dirName/"
        writeText(relativeDir, "raw.html", "text/html", rawHtml)
        writeText(relativeDir, "readable.html", "text/html", parsed.toReadableDocument())
        writeText(relativeDir, "text.txt", "text/plain", parsed.text)
        writeText(relativeDir, "metadata.json", "application/json", buildMetadataJson(normalizedUrl, parsed))
        writeText(relativeDir, "links.json", "application/json", buildLinksJson(parsed))
        return "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_FOLDER/articles/$dirName"
    }

    fun exportStoredArticle(article: ArticleEntity): String {
        val sourceDir = File(article.archiveDir)
        val dirName = buildArticleDirName(article.linkId, article.title)
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_FOLDER/articles/$dirName/"
        writeText(relativeDir, "raw.html", "text/html", sourceDir.resolve("raw.html").readTextOrBlank())
        writeText(relativeDir, "readable.html", "text/html", sourceDir.resolve("readable.html").readTextOrBlank())
        writeText(relativeDir, "text.txt", "text/plain", sourceDir.resolve("text.txt").readTextOrBlank())
        writeText(relativeDir, "metadata.json", "application/json", sourceDir.resolve("metadata.json").readTextOrBlank())
        writeText(relativeDir, "links.json", "application/json", sourceDir.resolve("links.json").readTextOrBlank())
        sourceDir.resolve("assets")
            .listFiles()
            ?.filter { it.isFile }
            ?.forEach { asset ->
                writeBytes(
                    relativeDir = "${relativeDir}assets/",
                    fileName = asset.name,
                    mimeType = mimeTypeFor(asset.name),
                    bytes = asset.readBytes(),
                )
            }
        return "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_FOLDER/articles/$dirName"
    }

    /** 批量导出专辑（名称 + 地址）到 Downloads/WechatArchive/albums.json，返回相对路径。 */
    fun exportAlbumsJson(albums: List<Pair<String, String>>): String {
        val json = if (albums.isEmpty()) {
            "[]"
        } else {
            albums.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { (name, url) ->
                """  {"name": ${name.jsonString()}, "url": ${url.jsonString()}}"""
            }
        }
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_FOLDER/"
        writeText(relativeDir, "albums.json", "application/json", json)
        return "$ROOT_FOLDER/albums.json"
    }

    private fun writeText(relativeDir: String, fileName: String, mimeType: String, text: String) {
        writeBytes(relativeDir, fileName, mimeType, text.toByteArray())
    }

    private fun writeBytes(relativeDir: String, fileName: String, mimeType: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        deleteExisting(relativeDir, fileName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Cannot create $relativeDir$fileName")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
            } ?: error("Cannot write $relativeDir$fileName")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun deleteExisting(relativeDir: String, fileName: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND (${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?)"
        val args = arrayOf(fileName, relativeDir, relativeDir.trimEnd('/'))
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun buildArticleDirName(linkId: Long, parsed: ParsedArticle): String {
        return buildArticleDirName(linkId, parsed.title)
    }

    private fun buildArticleDirName(linkId: Long, title: String): String {
        val titlePart = title
            .ifBlank { "untitled" }
            .replace(invalidPathChars, "_")
            .replace(Regex("""\s+"""), "_")
            .trim('_')
            .take(48)
            .ifBlank { "article" }
        return "${linkId}_$titlePart"
    }

    private fun File.readTextOrBlank(): String =
        runCatching { if (exists()) readText() else "" }.getOrDefault("")

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }

    private fun buildMetadataJson(normalizedUrl: String, parsed: ParsedArticle): String =
        buildString {
            appendLine("{")
            appendLine("  \"title\": ${parsed.title.jsonString()},")
            appendLine("  \"account_name\": ${parsed.accountName.jsonString()},")
            appendLine("  \"normalized_url\": ${normalizedUrl.jsonString()},")
            appendLine("  \"content_hash\": ${parsed.contentHash.jsonString()}")
            appendLine("}")
        }

    private fun buildLinksJson(parsed: ParsedArticle): String =
        parsed.discoveredLinks.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { link ->
            """  {"url": ${link.normalizedUrl.jsonString()}, "anchor_text": ${link.anchorText.jsonString()}}"""
        }

    private fun ParsedArticle.toReadableDocument(): String =
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
          $contentHtml
        </body>
        </html>
        """.trimIndent()

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

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    companion object {
        const val ROOT_FOLDER = "WechatArchive"
        private val invalidPathChars = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    }
}
