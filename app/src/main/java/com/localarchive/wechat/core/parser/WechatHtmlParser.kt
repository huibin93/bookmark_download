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
    // 微信专辑目录的文章项不是 <a href>，而是 <li data-link="...">；链接里还带 &amp; 实体。
    private val dataLinkPattern = Regex(
        """\bdata-(?:link|url|src-link)\s*=\s*(["'])(.*?)\1""",
        RegexOption.IGNORE_CASE,
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
        val links = mutableListOf<ParsedDiscoveredLink>()

        fun consider(rawHref: String, anchorText: String) {
            val cleaned = rawHref.decodeEntities().trim()
            if (cleaned.isBlank()) return
            val resolved = WechatUrlNormalizer.resolveAgainstBase(baseUrl, cleaned) ?: return
            val normalized = WechatUrlNormalizer.normalize(resolved) ?: return
            if (normalized.type != LinkType.ARTICLE && normalized.type != LinkType.ALBUM) return
            if (!seen.add(normalized.normalizedUrl)) return
            links.add(
                ParsedDiscoveredLink(
                    originalUrl = normalized.originalUrl,
                    normalizedUrl = normalized.normalizedUrl,
                    linkType = normalized.type,
                    anchorText = anchorText,
                ),
            )
        }

        // <a href> 文章/专辑链接
        anchorPattern.findAll(html).forEach { match ->
            consider(match.groupValues.getOrNull(2).orEmpty(), match.groupValues.getOrNull(3).orEmpty().cleanText())
        }
        // 专辑目录项 <li data-link="..."> —— 专辑展开记录的关键来源
        dataLinkPattern.findAll(html).forEach { match ->
            consider(match.groupValues.getOrNull(2).orEmpty(), "")
        }
        // 整页兜底扫描：先把 &amp; 还原成 &，否则 URL 会在 &amp; 的分号处被截断，
        // 导致一个专辑里所有文章都塌缩成同一个只剩 __biz 的链接。
        WechatUrlNormalizer.extractUrls(html.replace("&amp;", "&")).forEach { normalized ->
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
