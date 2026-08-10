package com.neoruaa.xhsdn.data.xhs

/**
 * URL extraction for text copied from Xiaohongshu's share sheet.
 *
 * The share sheet often wraps a URL in Chinese punctuation or adds a short
 * piece of explanatory text. Keeping extraction independent from networking
 * makes it deterministic in tests; callers may provide a short-link resolver
 * when they need canonical URLs.
 */
object XhsUrlParser {
    private val explorePattern = Regex("(?:https?://)?www\\.xiaohongshu\\.com/explore/\\S+")
    private val userPattern = Regex("(?:https?://)?www\\.xiaohongshu\\.com/user/profile/[a-z0-9]+/\\S+")
    private val sharePattern = Regex("(?:https?://)?www\\.xiaohongshu\\.com/discovery/item/\\S+")
    private val shortPattern = Regex(
        "(?:https?://)?xhslink\\.(?:com|cn)/[^\\s\\\"<>\\\\\\^`{|}，。；！？、【】《》]+"
    )
    private val idPattern = Regex("(?:explore|item)/([a-zA-Z0-9_-]+)(?:/)?(?:\\?.*)?$")
    private val userIdPattern = Regex("user/profile/[a-z0-9]+/([a-zA-Z0-9_-]+)(?:/)?(?:\\?.*)?$")

    /**
     * Extract all supported URLs while preserving their input order.
     *
     * A short URL is resolved synchronously when a resolver is supplied. If
     * resolution fails, the original short URL is retained so the caller can
     * still report a useful error or retry later.
     */
    fun extractLinks(input: String?, resolveShortUrl: (String) -> String? = { null }): List<String> {
        if (input.isNullOrBlank()) return emptyList()

        return buildList {
            input.split(Regex("\\s+")).forEach { part ->
                if (part.isBlank()) return@forEach

                val shortMatch = shortPattern.find(part)
                if (shortMatch != null) {
                    val shortUrl = shortMatch.value
                    add(resolveShortUrl(shortUrl) ?: shortUrl)
                    return@forEach
                }

                val shareMatch = sharePattern.find(part)
                if (shareMatch != null) {
                    add(shareMatch.value)
                    return@forEach
                }

                val exploreMatch = explorePattern.find(part)
                if (exploreMatch != null) {
                    add(exploreMatch.value)
                    return@forEach
                }

                userPattern.find(part)?.let { add(it.value) }
            }
        }
    }

    /** Extracts a note id from explore, discovery/item, user and short URLs. */
    fun extractPostId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        idPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        userIdPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        if (url.contains("xhslink.com/") || url.contains("xhslink.cn/")) {
            val parts = url.split('/')
            val last = parts.lastOrNull().orEmpty().substringBefore('?')
            if (last.isNotEmpty() && last != "o") return last
            parts.dropLast(1).lastOrNull()?.substringBefore('?')?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }
}
