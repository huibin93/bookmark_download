package com.localarchive.wechat.core.parser

import com.localarchive.wechat.core.url.WechatUrlNormalizer
import com.localarchive.wechat.data.model.LinkType
import java.security.MessageDigest

data class ParsedArticle(
    val title: String,
    val accountName: String,
    val author: String?,
    val publishTime: String?,
    val coverUrl: String?,
    val contentHtml: String,
    val text: String,
    val contentHash: String,
    val discoveredLinks: List<ParsedDiscoveredLink>,
    val imageUrls: List<String>,
)

data class ParsedDiscoveredLink(
    val originalUrl: String,
    val normalizedUrl: String,
    val linkType: LinkType,
    val anchorText: String,
)

object WechatHtmlParser {
    private val titlePatterns = listOf(
        Regex("""<h1[^>]*id=["']activity-name["'][^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    )
    private val accountPatterns = listOf(
        Regex("""<[^>]+id=["']js_name["'][^>]*>(.*?)</[^>]+>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""var\s+nickname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+property=["']og:article:author["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    )
    private val publishPattern = Regex("""var\s+ct\s*=\s*["']?(\d{10})["']?""", RegexOption.IGNORE_CASE)
    private val authorPattern = Regex("""<em[^>]+id=["']js_author_name["'][^>]*>(.*?)</em>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val coverPattern = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsContentPattern = Regex("""<div[^>]+id=["']js_content["'][^>]*>(.*?)</div>\s*</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val bodyPattern = Regex("""<body[^>]*>(.*?)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val anchorPattern = Regex(
        """<a\b[^>]*href\s*=\s*(["'])(.*?)\1[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val imagePattern = Regex("""<img\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val imageAttrPattern = Regex("""\b(?:data-src|data-original|src)\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val scriptStylePattern = Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagPattern = Regex("""<[^>]+>""")
    private val whitespacePattern = Regex("""[ \t\r\n]+""")

    fun parse(baseUrl: String, html: String, fallbackTitle: String?): ParsedArticle {
        val title = firstMatch(html, titlePatterns)
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle.orEmpty().ifBlank { "未命名文章" }
        val accountName = firstMatch(html, accountPatterns)
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: "未知公众号"
        val contentHtml = extractContentHtml(html)
        val text = htmlToText(contentHtml).ifBlank { htmlToText(html) }
        val discovered = extractSupportedLinks(baseUrl, html)
        val imageUrls = extractImageUrls(baseUrl, contentHtml)
        return ParsedArticle(
            title = title,
            accountName = accountName,
            author = authorPattern.find(html)?.groupValues?.getOrNull(1)?.cleanText()?.takeIf { it.isNotBlank() },
            publishTime = publishPattern.find(html)?.groupValues?.getOrNull(1),
            coverUrl = coverPattern.find(html)?.groupValues?.getOrNull(1)?.decodeEntities(),
            contentHtml = contentHtml,
            text = text,
            contentHash = sha256(text.ifBlank { html }),
            discoveredLinks = discovered,
            imageUrls = imageUrls,
        )
    }

    fun htmlToText(html: String): String =
        html
            .replace(scriptStylePattern, " ")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(tagPattern, " ")
            .decodeEntities()
            .replace(whitespacePattern, " ")
            .trim()

    private fun extractContentHtml(html: String): String =
        jsContentPattern.find(html)?.groupValues?.getOrNull(1)
            ?: bodyPattern.find(html)?.groupValues?.getOrNull(1)
            ?: html

    private fun extractSupportedLinks(baseUrl: String, html: String): List<ParsedDiscoveredLink> {
        val seen = linkedSetOf<String>()
        val links = anchorPattern.findAll(html)
            .mapNotNullTo(mutableListOf()) { match ->
                val href = match.groupValues.getOrNull(2).orEmpty()
                val resolved = WechatUrlNormalizer.resolveAgainstBase(baseUrl, href) ?: return@mapNotNullTo null
                val normalized = WechatUrlNormalizer.normalize(resolved) ?: return@mapNotNullTo null
                if (normalized.type != LinkType.ARTICLE && normalized.type != LinkType.ALBUM) return@mapNotNullTo null
                if (!seen.add(normalized.normalizedUrl)) return@mapNotNullTo null
                ParsedDiscoveredLink(
                    originalUrl = normalized.originalUrl,
                    normalizedUrl = normalized.normalizedUrl,
                    linkType = normalized.type,
                    anchorText = match.groupValues.getOrNull(3).orEmpty().cleanText(),
                )
            }
        WechatUrlNormalizer.extractUrls(html).forEach { normalized ->
            if (normalized.type != LinkType.ARTICLE && normalized.type != LinkType.ALBUM) return@forEach
            if (!seen.add(normalized.normalizedUrl)) return@forEach
            links.add(
                ParsedDiscoveredLink(
                    originalUrl = normalized.originalUrl,
                    normalizedUrl = normalized.normalizedUrl,
                    linkType = normalized.type,
                    anchorText = "",
                ),
            )
        }
        return links
    }

    private fun extractImageUrls(baseUrl: String, html: String): List<String> {
        val seen = linkedSetOf<String>()
        imagePattern.findAll(html).forEach { imageMatch ->
            val tag = imageMatch.value
            imageAttrPattern.findAll(tag).forEach { attrMatch ->
                val raw = attrMatch.groupValues.getOrNull(2).orEmpty()
                    .decodeEntities()
                    .trim()
                if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return@forEach
                val resolved = WechatUrlNormalizer.resolveAgainstBase(baseUrl, raw) ?: return@forEach
                seen.add(resolved)
            }
        }
        return seen.toList()
    }

    private fun firstMatch(html: String, patterns: List<Regex>): String? =
        patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        }

    private fun String.cleanText(): String =
        htmlToText(this).decodeEntities().trim()

    private fun String.decodeEntities(): String =
        replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""&#(\d+);""")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty()
            }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
