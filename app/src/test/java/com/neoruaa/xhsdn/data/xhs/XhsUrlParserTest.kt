package com.neoruaa.xhsdn.data.xhs

import org.junit.Assert.assertEquals
import org.junit.Test

class XhsUrlParserTest {
    @Test
    fun extractsComCnAndShareTextInOrder() {
        val input = "复制文字 https://xhslink.cn/o/cn123，另一个 https://xhslink.com/o/com456"
        val links = XhsUrlParser.extractLinks(input) { short ->
            when {
                short.contains("xhslink.cn") -> "https://www.xiaohongshu.com/explore/CN123?x=1"
                else -> "https://www.xiaohongshu.com/discovery/item/COM456"
            }
        }

        assertEquals(
            listOf(
                "https://www.xiaohongshu.com/explore/CN123?x=1",
                "https://www.xiaohongshu.com/discovery/item/COM456",
            ),
            links,
        )
    }

    @Test
    fun extractsExploreDiscoveryAndUserUrlsFromMixedText() {
        val links = XhsUrlParser.extractLinks(
            "https://www.xiaohongshu.com/explore/abc_1?foo=bar " +
                "https://www.xiaohongshu.com/discovery/item/def-2 " +
                "www.xiaohongshu.com/user/profile/abc123/note3",
        )

        assertEquals(3, links.size)
        assertEquals("abc_1", XhsUrlParser.extractPostId(links[0]))
        assertEquals("def-2", XhsUrlParser.extractPostId(links[1]))
        assertEquals("note3", XhsUrlParser.extractPostId(links[2]))
    }

    @Test
    fun preservesShortUrlWhenResolutionFailsAndExtractsShortId() {
        val shortUrl = "http://xhslink.cn/o/5tNOqVGqSNG"
        assertEquals(listOf(shortUrl), XhsUrlParser.extractLinks(shortUrl))
        assertEquals("5tNOqVGqSNG", XhsUrlParser.extractPostId(shortUrl))
    }
}
