package com.neoruaa.xhsdn.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// https://github.com/NEORUAA/XHS_Downloader_Android/issues/40
class UrlUtilsTest {
    @Test
    fun extractsAndRecognizesXhslinkCnFromShareText() {
        val shareText = """
            我现在理解了人的心脏为什么会被比喻成牡蛎 我们很少... http://xhslink.cn/o/5tNOqVGqSNG 复制文字，打开【小红书】，笔记立刻呈现~
        """.trimIndent()

        val url = UrlUtils.extractFirstUrl(shareText)

        assertEquals("http://xhslink.cn/o/5tNOqVGqSNG", url)
        assertTrue(UrlUtils.isXhsLink(url))
    }

    @Test
    fun continuesToRecognizeLegacyXhslinkCom() {
        assertTrue(UrlUtils.isXhsLink("https://xhslink.com/o/example"))
    }
}
