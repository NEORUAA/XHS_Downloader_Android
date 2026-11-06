package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.ResolvedNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Optional author metadata must never prevent the note's media from downloading. */
class XhsAuthorProfileResolver(
    private val parser: XhsNoteParser = XhsNoteParser(),
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 10_000,
) {
    private data class CachedId(val redId: String, val expiresAt: Long)
    private val cache = LinkedHashMap<String, CachedId>()

    suspend fun resolveRedId(note: ResolvedNote, fetchPage: suspend (String) -> XhsPage?): String? {
        note.authorRedId?.takeIf(String::isNotBlank)?.let { return it }
        val userId = note.authorId?.takeIf(String::isNotBlank) ?: return null
        synchronized(cache) {
            cache.entries.removeAll { it.value.expiresAt <= now() }
            cache[userId]?.let { return it.redId }
        }
        val profileUrl = note.authorProfileUrl?.let { normalizedProfileUrl(it, userId) }
        val url = profileUrl?.takeIf { !it.queryParameter("xsec_token").isNullOrBlank() }
            ?: profileUrlFromNote(note, userId)
            ?: profileUrl
            ?: profileBaseUrl(userId, note.canonicalUrl) ?: return null
        val redId = try {
            withTimeoutOrNull(timeoutMillis) {
                val page = fetchPage(url.toString()) ?: return@withTimeoutOrNull null
                if (normalizedProfileUrl(page.finalUrl, userId) == null) return@withTimeoutOrNull null
                parser.parseAuthorRedId(page.html, userId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        synchronized(cache) {
            cache[userId] = CachedId(redId, now() + 15 * 60_000)
            while (cache.size > 128) cache.remove(cache.keys.first())
        }
        return redId
    }
}

internal fun JSONObject.xhsText(vararg keys: String): String? = keys.firstNotNullOfOrNull {
    (opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty)
}

internal fun authorProfileUrl(user: JSONObject, userId: String, canonicalUrl: String): String? {
    val explicit = listOf("profileUrl", "profile_url", "userPageUrl", "user_page_url", "homepage", "homePageUrl")
        .firstNotNullOfOrNull { key -> user.xhsText(key)?.let { normalizedProfileUrl(it, userId) } }
    if (!explicit?.queryParameter("xsec_token").isNullOrBlank()) return explicit.toString()
    val token = user.xhsText("xsecToken", "xsec_token") ?: return explicit?.toString()
    return (explicit ?: profileBaseUrl(userId, canonicalUrl))?.newBuilder()
        ?.removeAllQueryParameters("xsec_source")
        ?.addQueryParameter("xsec_token", token)
        ?.addQueryParameter("xsec_source", user.xhsText("xsecSource", "xsec_source") ?: "pc_note")
        ?.build()?.toString()
}

private val profileHosts = setOf("www.xiaohongshu.com", "xiaohongshu.com", "www.rednote.com", "rednote.com")

private fun profileUrlFromNote(note: ResolvedNote, userId: String): HttpUrl? {
    val noteUrl = trustedNoteUrl(note.canonicalUrl, note.noteId) ?: return null
    val token = noteUrl.queryParameter("xsec_token")?.takeIf(String::isNotBlank) ?: return null
    // Shared note context is accepted by the mobile author profile endpoint as well.
    return profileBaseUrl(userId, note.canonicalUrl)?.newBuilder()?.apply {
        addQueryParameter("xsec_token", token)
        noteUrl.queryParameter("xsec_source")?.takeIf(String::isNotBlank)?.let { addQueryParameter("xsec_source", it) }
    }?.build()
}

internal fun trustedNoteUrl(url: String, noteId: String): HttpUrl? {
    val parsed = url.toHttpUrlOrNull()?.takeIf(::isTrustedPageUrl) ?: return null
    if (noteId.isBlank() || parsed.pathSegments.dropLastWhile(String::isEmpty).any(String::isEmpty) ||
        XhsUrlParser.extractPostId(parsed.toString()) != noteId) return null
    return parsed
}

private fun profileBaseUrl(userId: String, canonicalUrl: String): HttpUrl? {
    if (!userId.matches(Regex("[A-Za-z0-9_-]+"))) return null
    val host = canonicalUrl.toHttpUrlOrNull()?.host?.takeIf { it in profileHosts } ?: "www.xiaohongshu.com"
    return HttpUrl.Builder().scheme("https").host(host).addPathSegments("user/profile").addPathSegment(userId).build()
}

private fun normalizedProfileUrl(url: String, userId: String): HttpUrl? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (!isTrustedPageUrl(parsed) ||
        parsed.pathSegments.dropLastWhile(String::isEmpty) != listOf("user", "profile", userId)) return null
    return parsed.newBuilder().scheme("https").port(443).query(null).fragment(null).apply {
        listOf("xsec_token", "xsec_source").forEach { key ->
            parsed.queryParameter(key)?.takeIf(String::isNotBlank)?.let { addQueryParameter(key, it) }
        }
    }.build()
}

private fun isTrustedPageUrl(url: HttpUrl): Boolean = url.host in profileHosts &&
    url.username.isEmpty() && url.password.isEmpty() && url.port == (if (url.isHttps) 443 else 80)
