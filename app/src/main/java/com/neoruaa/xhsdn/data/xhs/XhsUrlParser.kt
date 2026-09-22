package com.neoruaa.xhsdn.data.xhs

import java.net.URI

object XhsUrlParser {
    private val links = Regex(
        "(?:https?://)?(?:www\\.)?(?:xiaohongshu\\.com|rednote\\.com|xhslink\\.(?:com|cn))/[^\\s\\\"<>\\\\^`{|}，。；！？、【】《》]+",
        RegexOption.IGNORE_CASE,
    )
    private val hosts = setOf("xiaohongshu.com", "www.xiaohongshu.com", "rednote.com", "www.rednote.com", "xhslink.com", "xhslink.cn")

    fun extractLinks(input: String?, resolveShortUrl: (String) -> String? = { null }): List<String> =
        links.findAll(input.orEmpty()).mapNotNull { match ->
            val preceding = input?.getOrNull(match.range.first - 1)
            if (preceding != null && (preceding in 'a'..'z' || preceding in 'A'..'Z' || preceding in '0'..'9' || preceding in "._-")) return@mapNotNull null
            val raw = match.value.trimEnd(')', ']', '}', '.', ',', ';', '!', '，', '。', '）', '】')
            val url = if (raw.startsWith("http", true)) raw else "https://$raw"
            if (!isSupportedUrl(url)) null else if (isShortUrl(url)) resolveShortUrl(url) ?: url else url
        }.toList()

    fun isSupportedUrl(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme in setOf("http", "https") && uri.host?.lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)

    fun isShortUrl(url: String): Boolean = runCatching {
        URI(url).host?.lowercase() in setOf("xhslink.com", "xhslink.cn")
    }.getOrDefault(false)

    fun extractPostId(url: String?): String? = runCatching {
        val normalized = url?.let { if (it.startsWith("http")) it else "https://$it" } ?: return null
        if (!isSupportedUrl(normalized) || isShortUrl(normalized)) return null
        val segments = URI(normalized).path.trim('/').split('/')
        when {
            segments.size == 2 && segments[0] == "explore" -> segments[1]
            segments.size == 3 && segments.take(2) == listOf("discovery", "item") -> segments[2]
            segments.size == 4 && segments.take(2) == listOf("user", "profile") -> segments[3]
            else -> null
        }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
    }.getOrNull()
}
