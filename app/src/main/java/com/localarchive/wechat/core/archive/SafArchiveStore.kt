package com.localarchive.wechat.core.archive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * The single owner of article files, stored under a user-granted SAF folder.
 *
 * Unlike MediaStore (which can delete file rows but leaves empty directories
 * behind — junk that File deletion can't clear on MIUI/scoped storage),
 * DocumentFile gives real recursive directory create/delete. So an article is
 * one folder `<tree>/articles/{dir}/` and deleting it removes the whole folder
 * cleanly, on every supported version.
 */
class SafArchiveStore(private val context: Context) {

    fun writeArticle(treeUri: Uri, built: BuiltArchive): String {
        val articles = articlesDir(treeUri, create = true) ?: error("无法访问所选保存文件夹")
        // Overwrite cleanly: drop any previous copy first (recursive). The new dir
        // is then empty, so per-file findFile() below is unnecessary (overwrite=false).
        articles.findFile(built.dirName)?.delete()
        val dir = articles.createDirectory(built.dirName) ?: error("无法创建文章目录 ${built.dirName}")
        built.files.forEach { (name, bytes) -> writeChild(dir, name, bytes, overwrite = false) }
        if (built.assets.isNotEmpty()) {
            val assets = dir.createDirectory("assets") ?: error("无法创建 assets 目录")
            built.assets.forEach { (name, bytes) -> writeChild(assets, name, bytes, overwrite = false) }
        }
        return built.dirName
    }

    /** Read a file (e.g. `index.html` or `assets/image_001.jpg`) back for the reader. */
    fun readFile(treeUri: Uri, dirName: String, name: String): ByteArray? {
        val articles = articlesDir(treeUri, create = false) ?: return null
        var node: DocumentFile? = articles.findFile(dirName) ?: return null
        for (segment in name.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            node = node?.findFile(segment) ?: return null
        }
        val file = node ?: return null
        if (!file.isFile) return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    /** Delete a whole article folder (files + assets) — recursive, no leftover. */
    fun deleteArticleDir(treeUri: Uri, dirName: String): Boolean {
        val articles = articlesDir(treeUri, create = false) ?: return false
        return articles.findFile(dirName)?.delete() ?: true
    }

    /** Wipe every article folder (one-time clean-slate within the chosen folder). */
    fun deleteAllArticles(treeUri: Uri) {
        val articles = articlesDir(treeUri, create = false) ?: return
        articles.listFiles().forEach { runCatching { it.delete() } }
    }

    fun exportAlbumsJson(treeUri: Uri, albums: List<Pair<String, String>>): String? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val json = if (albums.isEmpty()) {
            "[]"
        } else {
            albums.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { (name, url) ->
                """  {"name": ${name.jsonString()}, "url": ${url.jsonString()}}"""
            }
        }
        writeChild(root, "albums.json", json.toByteArray())
        return "albums.json"
    }

    private fun articlesDir(treeUri: Uri, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return root.findFile("articles") ?: if (create) root.createDirectory("articles") else null
    }

    private fun writeChild(dir: DocumentFile, name: String, bytes: ByteArray, overwrite: Boolean = true) {
        // Skipped for a freshly-created folder — avoids an O(n) directory listing per file.
        if (overwrite) dir.findFile(name)?.delete()
        val file = dir.createFile(mimeFor(name), name) ?: error("无法创建文件 $name")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: error("无法写入文件 $name")
    }

    private fun mimeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }

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
}
