package com.localarchive.wechat.core.url

import com.localarchive.wechat.data.model.LinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WechatUrlNormalizerTest {
    @Test
    fun normalizesArticleParameterUrl() {
        val result = WechatUrlNormalizer.normalize(
            "https://mp.weixin.qq.com/s?__biz=abc&mid=123&idx=1&sn=xyz&scene=21#wechat_redirect",
        )

        assertNotNull(result)
        assertEquals(LinkType.ARTICLE, result!!.type)
        assertEquals(
            "https://mp.weixin.qq.com/s?__biz=abc&mid=123&idx=1&sn=xyz",
            result.normalizedUrl,
        )
    }

    @Test
    fun extractsArticleUrlFromSharedText() {
        val result = WechatUrlNormalizer.extractFirstSupportedUrl(
            "这篇文章不错 https://mp.weixin.qq.com/s/demoToken?utm_source=test ，收藏一下",
        )

        assertNotNull(result)
        assertEquals(LinkType.ARTICLE, result!!.type)
        assertEquals("https://mp.weixin.qq.com/s/demoToken", result.normalizedUrl)
    }

    @Test
    fun ignoresExternalUrlForSupportedExtraction() {
        val result = WechatUrlNormalizer.extractFirstSupportedUrl("https://example.com/article")

        assertNull(result)
    }
}
