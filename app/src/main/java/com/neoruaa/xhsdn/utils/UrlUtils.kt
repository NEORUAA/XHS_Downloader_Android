package com.neoruaa.xhsdn.utils

object UrlUtils {
    /**
     * 从文本中提取第一个 URL
     */
    fun extractFirstUrl(text: String): String? {
        val regex = Regex("https?://[\\w\\-.]+(?:/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?")
        return regex.find(text)?.value
    }

    /**
     * 检查是否为有效的小红书链接
     */
    fun isXhsLink(url: String?): Boolean {
        if (url == null) return false
        return url.contains("xhslink.com") ||
            url.contains("xhslink.cn") ||
            url.contains("xiaohongshu.com")
    }
}
