package com.localarchive.wechat.core.url

import com.localarchive.wechat.data.model.LinkType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class NormalizedWechatUrl(
    val originalUrl: String,
    val normalizedUrl: String,
    val type: LinkType,
)

object WechatUrlNormalizer {
    private val urlPattern = Regex("""https?://[^\s<>"'（）()，。；;]+""", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = Regex("""[.,，。；;!！?？]+$""")
    private val stableArticleKeys = listOf("__biz", "mid", "idx", "sn")
    private val stableAlbumKeys = listOf("__biz", "album_id", "action")
    private val stableProfileKeys = listOf("__biz", "action", "scene")

    fun extractFirstSupportedUrl(text: String): NormalizedWechatUrl? =
        extractUrls(text).firstOrNull { it.type == LinkType.ARTICLE || it.type == LinkType.ALBUM }

    fun extractUrls(text: String): List<NormalizedWechatUrl> =
        urlPattern.findAll(text)
            .mapNotNull { normalize(it.value) }
            .toList()

    fun normalize(raw: String): NormalizedWechatUrl? {
        val cleaned = raw
            .trim()
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace(trailingPunctuation, "")
        if (cleaned.isBlank()) return null

        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return NormalizedWechatUrl(cleaned, cleaned, LinkType.UNKNOWN)
        if (host != "mp.weixin.qq.com") {
            return NormalizedWechatUrl(cleaned, cleaned, LinkType.EXTERNAL)
        }

        val path = uri.path.orEmpty()
        val params = parseQuery(uri.rawQuery.orEmpty())
        val normalized = when {
            path.startsWith("/s/") -> normalizePathArticle(path)
            path == "/s" || params.containsKey("__biz") && params.containsKey("mid") && params.containsKey("idx") ->
                normalizeQueryArticle(path, params)
            path.startsWith("/mp/appmsgalbum") ->
                normalizeQuery("/mp/appmsgalbum", params, stableAlbumKeys)
            path.startsWith("/mp/profile_ext") ->
                normalizeQuery("/mp/profile_ext", params, stableProfileKeys)
            else -> null
        }

        val type = when {
            normalized == null -> LinkType.UNKNOWN
            normalized.contains("/mp/appmsgalbum") -> LinkType.ALBUM
            normalized.contains("/mp/profile_ext") -> LinkType.PROFILE
            normalized.contains("/s/") || normalized.contains("/s?") -> LinkType.ARTICLE
            else -> LinkType.UNKNOWN
        }
        return NormalizedWechatUrl(cleaned, normalized ?: cleaned, type)
    }

    fun resolveAgainstBase(baseUrl: String, candidate: String): String? {
        val cleaned = candidate
            .trim()
            .replace("&amp;", "&")
        if (cleaned.isBlank() || cleaned.startsWith("javascript:", ignoreCase = true)) return null
        if (cleaned.startsWith("//")) return "https:$cleaned"
        return runCatching {
            URI(baseUrl).resolve(cleaned).toString()
        }.getOrNull()
    }

    private fun normalizePathArticle(path: String): String {
        val token = path.removePrefix("/s/").trim('/')
        return "https://mp.weixin.qq.com/s/${encodePathSegment(decode(token))}"
    }

    private fun normalizeQueryArticle(path: String, params: Map<String, String>): String {
        val selected = stableArticleKeys
            .mapNotNull { key -> params[key]?.takeIf { it.isNotBlank() }?.let { key to it } }
        if (selected.size == stableArticleKeys.size) {
            return "https://mp.weixin.qq.com/s?${selected.joinToString("&") { (key, value) -> "$key=${encode(value)}" }}"
        }
        return normalizeQuery(path.ifBlank { "/s" }, params, stableArticleKeys)
    }

    private fun normalizeQuery(path: String, params: Map<String, String>, preferredKeys: List<String>): String {
        val selected = preferredKeys
            .mapNotNull { key -> params[key]?.takeIf { it.isNotBlank() }?.let { key to it } }
            .ifEmpty {
                params.entries
                    .filterNot { (key, _) -> key.startsWith("utm_") || key in noisyKeys }
                    .sortedBy { it.key }
                    .map { it.key to it.value }
            }
        val query = selected.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return if (query.isBlank()) {
            "https://mp.weixin.qq.com$path"
        } else {
            "https://mp.weixin.qq.com$path?$query"
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                decode(part.substring(0, index)) to decode(part.substring(index + 1))
            }
            .toMap()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodePathSegment(value: String): String =
        encode(value).replace("%2F", "/")

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private val noisyKeys = setOf(
        "scene",
        "subscene",
        "clicktime",
        "enterid",
        "ascene",
        "devicetype",
        "version",
        "nettype",
        "lang",
        "sessionid",
        "exportkey",
        "pass_ticket",
        "wx_header",
    )
}
