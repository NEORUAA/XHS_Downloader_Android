package com.neoruaa.xhsdn.data.xhs

import org.junit.Assert.assertEquals
import org.junit.Test

class XhsUrlParserTest {
    @Test
    fun keepsCaptionBeforeTheLinkWithoutSharingInstructions() {
        assertEquals("Shared caption", XhsUrlParser.extractShareTitle(
            "Shared caption\nhttps://xhslink.cn/o/abc Open the app to view this note"
        ))
        assertEquals("Shared caption", XhsUrlParser.extractShareTitle("Shared caption xhslink.cn/o/abc"))
    }

    @Test
    fun doesNotUseLinksOrBatchDescriptionsAsTitles() {
        assertEquals(null, XhsUrlParser.extractShareTitle("https://xhslink.cn/o/abc"))
        assertEquals(null, XhsUrlParser.extractShareTitle("Caption without a link"))
        assertEquals(null, XhsUrlParser.extractShareTitle("First https://xhslink.cn/o/abc\nSecond https://xhslink.cn/o/def"))
    }

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
    fun preservesShortUrlWithoutMistakingSlugForNoteId() {
        val shortUrl = "http://xhslink.cn/o/5tNOqVGqSNG"
        assertEquals(listOf(shortUrl), XhsUrlParser.extractLinks(shortUrl))
        assertEquals(null, XhsUrlParser.extractPostId(shortUrl))
    }
}
