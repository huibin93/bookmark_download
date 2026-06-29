package com.localarchive.wechat.core.parser

import com.localarchive.wechat.data.model.LinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatHtmlParserTest {
    // 回归：微信专辑目录用 <li data-link="...&amp;mid=...">，旧逻辑会在 &amp; 的分号处
    // 截断链接，使整本专辑塌缩成一条只剩 __biz 的链接。修复后应抽到每一篇独立文章。
    @Test
    fun extractsAllAlbumArticleLinksFromDataLink() {
        val html = """
            <html><body>
            <ul class="album__list">
              <li class="album__list-item" data-link="http://mp.weixin.qq.com/s?__biz=Mzg5MDIwNjIwMA==&amp;mid=2247502745&amp;idx=1&amp;sn=aaa111&amp;chksm=zzz">A</li>
              <li class="album__list-item" data-link="http://mp.weixin.qq.com/s?__biz=Mzg5MDIwNjIwMA==&amp;mid=2247501010&amp;idx=1&amp;sn=bbb222&amp;chksm=zzz">B</li>
              <li class="album__list-item" data-link="http://mp.weixin.qq.com/s?__biz=Mzg5MDIwNjIwMA==&amp;mid=2247498655&amp;idx=1&amp;sn=ccc333&amp;chksm=zzz">C</li>
            </ul>
            </body></html>
        """.trimIndent()
        val parsed = WechatHtmlParser.parse(
            "https://mp.weixin.qq.com/mp/appmsgalbum?__biz=Mzg5MDIwNjIwMA==&album_id=1&action=getalbum",
            html,
            "album",
        )
        val articleLinks = parsed.discoveredLinks.filter { it.linkType == LinkType.ARTICLE }
        assertEquals(3, articleLinks.size)
        assertTrue(articleLinks.all { it.normalizedUrl.contains("mid=") && it.normalizedUrl.contains("sn=") })
    }
}
