package com.localarchive.wechat.core.archive

import com.localarchive.wechat.core.parser.ParsedArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleArchiverTest {

    private fun parsed(title: String = "测试文章") = ParsedArticle(
        title = title,
        accountName = "测试公众号",
        author = "张三",
        publishTime = "1700000000",
        coverUrl = "https://mmbiz.qpic.cn/cover.jpg",
        contentHtml = "",
        text = "正文纯文本",
        contentHash = "deadbeef",
        discoveredLinks = emptyList(),
        imageUrls = listOf("https://mmbiz.qpic.cn/a.jpg", "https://mmbiz.qpic.cn/b.png"),
    )

    @Test
    fun rewritesImagesToLocalRealSrc() {
        val content =
            """<p>hi</p><img class="x" data-src="https://mmbiz.qpic.cn/a.jpg"><img src="https://mmbiz.qpic.cn/b.png">"""
        val assetMap = mapOf(
            "https://mmbiz.qpic.cn/a.jpg" to "assets/image_001.jpg",
            "https://mmbiz.qpic.cn/b.png" to "assets/image_002.png",
        )
        val out = ArticleArchiver.rewriteImageSources(content, assetMap)
        assertTrue(out.contains("src=\"assets/image_001.jpg\""))
        assertTrue(out.contains("src=\"assets/image_002.png\""))
        assertFalse("no remote CDN refs remain", out.contains("https://mmbiz.qpic.cn/"))
        assertFalse("data-src dropped", out.contains("data-src"))
    }

    @Test
    fun matchesDespiteHtmlEntitiesAndFragment() {
        // The page's <img> carries &amp; and a #imgIndex fragment; the asset map is
        // keyed by the decoded URL. This used to fail (images stayed remote).
        val content = """<img data-src="https://mmbiz.qpic.cn/x?a=1&amp;b=2#imgIndex=0">"""
        val assetMap = mapOf("https://mmbiz.qpic.cn/x?a=1&b=2" to "assets/image_001.jpg")
        val out = ArticleArchiver.rewriteImageSources(content, assetMap)
        assertTrue(out.contains("src=\"assets/image_001.jpg\""))
    }

    @Test
    fun stripsInlineWidthThatShrinksImages() {
        val content = """<img data-src="https://m/a.jpg" style="width: 147px !important; height: auto;">"""
        val assetMap = mapOf("https://m/a.jpg" to "assets/image_001.jpg")
        val out = ArticleArchiver.rewriteImageSources(content, assetMap)
        assertTrue(out.contains("src=\"assets/image_001.jpg\""))
        assertFalse("forced small width removed", out.contains("147px"))
    }

    @Test
    fun indexHtmlHasTitleSourceLinkAndLocalImages() {
        val html = ArticleArchiver.buildIndexHtml(
            parsed(),
            sourceUrl = "https://mp.weixin.qq.com/s/abc",
            contentHtml = """<img src="assets/image_001.jpg">""",
        )
        assertTrue(html.contains("<title>测试文章</title>"))
        assertTrue(html.contains("原文链接"))
        assertTrue(html.contains("https://mp.weixin.qq.com/s/abc"))
        assertTrue(html.contains("assets/image_001.jpg"))
    }

    @Test
    fun metadataJsonHasExpectedFieldsNoMarkdownNoRaw() {
        val json = ArticleArchiver.buildMetadataJson(
            parsed = parsed(),
            normalizedUrl = "https://mp.weixin.qq.com/s/abc",
            originalUrl = "https://mp.weixin.qq.com/s/abc?x=1",
            imagesTotal = 2,
            imagesDownloaded = 2,
        )
        listOf("\"title\"", "\"account_name\"", "\"source_url\"", "\"images_downloaded\"", "android_webview")
            .forEach { assertTrue("missing $it", json.contains(it)) }
    }

    @Test
    fun dirNameUsesStableHashSuffix() {
        val url = "https://mp.weixin.qq.com/s/abc"
        val dir = ArticleArchiver.articleDirName("a/b:c", url)
        assertTrue(dir.startsWith("a_b_c_"))
        val suffix = dir.removePrefix("a_b_c_")
        assertTrue("16 hex chars", suffix.matches(Regex("[0-9a-f]{16}")))
        // Same article URL → same folder (stable, not a counter).
        assertEquals(dir, ArticleArchiver.articleDirName("a/b:c", url))
    }
}
