package com.neoruaa.xhsdn.data.xhs

import com.neoruaa.xhsdn.core.model.MediaCandidate
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.util.ArrayDeque

/** Compatibility views for callers migrating to the immutable note model. */
data class XhsMedia(val url: String, val isVideo: Boolean)
data class XhsLivePhoto(val imageUrl: String, val videoUrl: String)
data class XhsNoteMetadata(val userName: String?, val userId: String?, val title: String?, val publishTime: String?) {
    fun hasRequiredFields(): Boolean = !userName.isNullOrBlank() && !userId.isNullOrBlank()
}
data class ParsedXhsNote(
    val mediaUrls: List<String>,
    val livePhotos: List<XhsLivePhoto>,
    val description: String?,
    val metadata: XhsNoteMetadata?,
    val containsVideo: Boolean,
    val originalUrlByTransformed: Map<String, String> = emptyMap(),
    val resolved: ResolvedNote? = null,
)

class XhsNoteParser(
    private val urlTransformer: (String) -> String = { it },
    private val logError: (String) -> Unit = {},
) {
    fun parse(html: String?, expectedNoteId: String? = null, canonicalUrl: String = ""): ParsedXhsNote {
        if (html.isNullOrBlank()) return empty()
        val notes = parseInitialStateRootFromHtml(html)?.let(::findNoteObjects).orEmpty()
            .filter(::isLikelyNoteObject)
        val selected = if (expectedNoteId != null) {
            notes.firstOrNull { it.optString("noteId") == expectedNoteId }
                ?: notes.singleOrNull()?.takeIf { it.optString("noteId").isBlank() }
        } else notes.singleOrNull()
        if (selected == null) {
            // Legacy extraction remains available, but the repository requires a real note.
            if (notes.isNotEmpty()) return empty()
            val urls = Regex("https?://[^\\s\\\"<>]+?\\.(?:jpg|jpeg|png|gif|webp|mp4|mov)(?:\\?[^\\s\\\"<>]*)?", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value }.distinct().toList()
            return empty().copy(mediaUrls = urls, containsVideo = urls.any { it.contains(".mp4") || it.contains(".mov") })
        }
        val note = parseNote(selected, canonicalUrl, expectedNoteId)
        val urls = note.orderedMedia.flatMap { media -> when (media) {
            is ResolvedMedia.LivePhoto -> listOf(media.image.sourceUrl, media.video.sourceUrl)
            else -> listOf(media.sourceUrl)
        } }
        val originals = note.orderedMedia.flatMap { media -> when (media) {
            is ResolvedMedia.Image -> listOf(media.sourceUrl to media.originalUrl)
            is ResolvedMedia.Video -> listOf(media.sourceUrl to media.originalUrl)
            is ResolvedMedia.LivePhoto -> listOf(media.image.sourceUrl to media.image.originalUrl, media.video.sourceUrl to media.video.originalUrl)
        } }.toMap()
        return ParsedXhsNote(
            urls, note.livePhotos.map { XhsLivePhoto(it.image.sourceUrl, it.video.sourceUrl) }, note.description,
            XhsNoteMetadata(note.authorName, note.authorId, note.title, note.publishTime),
            note.videos.isNotEmpty() || note.livePhotos.isNotEmpty(), originals, note,
        )
    }

    fun description(html: String?): String? = parse(html).description

    fun parseNote(note: JSONObject, canonicalUrl: String, expectedNoteId: String? = null): ResolvedNote {
        val noteId = note.optString("noteId").ifBlank { expectedNoteId.orEmpty() }
        require(expectedNoteId == null || noteId == expectedNoteId) { "Note identity mismatch" }
        val imageList = note.optJSONArray("imageList") ?: note.optJSONArray("images") ?: JSONArray()
        val mainVideo = note.optJSONObject("video")
        val type = note.optString("type").ifBlank { if (mainVideo != null && imageList.length() <= 1 && imageList.optJSONObject(0)?.optJSONObject("stream") == null) "video" else "normal" }
        val isVideoNote = type == "video"
        val items = mutableListOf<ResolvedMedia>()
        for (index in 0 until imageList.length()) {
            val item = imageList.optJSONObject(index) ?: continue
            val original = item.optString("urlDefault").ifBlank { item.optString("url") }.ifBlank {
                val info = item.optJSONArray("infoList") ?: JSONArray()
                (0 until info.length()).mapNotNull { info.optJSONObject(it)?.optString("url") }.firstOrNull { it.isNotBlank() }.orEmpty()
            }.ifBlank { item.optString("traceId").takeIf(String::isNotBlank)?.let { "https://sns-img-qc.xhscdn.com/$it" }.orEmpty() }
            if (!isHttp(original)) continue
            val image = ResolvedMedia.Image(
                sourceUrl = urlTransformer(original), originalUrl = original,
                id = "$noteId:${if (isVideoNote) "cover" else "image"}:${index + 1}",
                previewUrl = original, cover = isVideoNote,
                width = item.optInt("width"), height = item.optInt("height"),
            )
            val streams = candidates(item.optJSONObject("stream"))
            if (!isVideoNote && streams.isNotEmpty()) {
                items += ResolvedMedia.LivePhoto(image, ResolvedMedia.Video(
                    streams.first().url, id = "$noteId:live:${index + 1}", previewUrl = original, candidates = streams,
                ))
            } else items += image
        }
        if (mainVideo != null && (isVideoNote || !note.has("type"))) {
            val streams = mutableListOf<MediaCandidate>()
            mainVideo.optJSONObject("consumer")?.optString("originVideoKey")?.takeIf(String::isNotBlank)?.let {
                streams += MediaCandidate("https://sns-video-bd.xhscdn.com/$it", original = true)
            }
            streams += candidates(mainVideo.optJSONObject("media")?.optJSONObject("stream"))
            val distinct = streams.distinctBy { it.url }
            if (distinct.isNotEmpty()) items += ResolvedMedia.Video(
                distinct.first().url, id = "$noteId:video", previewUrl = items.firstOrNull()?.previewUrl.orEmpty(), candidates = distinct,
            )
        }
        val user = note.optJSONObject("user") ?: note.optJSONObject("user_info") ?: JSONObject()
        val title = note.optString("title").takeIf(String::isNotBlank)
        val body = note.optString("desc").ifBlank { note.optString("description") }
        val tags = note.optJSONArray("tagList") ?: JSONArray()
        val published = note.optLong("time").takeIf { it > 0 }
        val interact = note.optJSONObject("interactInfo") ?: JSONObject()
        return ResolvedNote(
            canonicalUrl = canonicalUrl, noteId = noteId, type = type,
            title = title, body = body, description = listOfNotNull(title, body.takeIf(String::isNotBlank)).joinToString("\n").takeIf(String::isNotBlank),
            authorName = listOf("nickname", "nickName", "name", "userName").firstNotNullOfOrNull { user.optString(it).takeIf(String::isNotBlank) },
            authorId = listOf("userId", "user_id", "id", "redId").firstNotNullOfOrNull { user.optString(it).takeIf(String::isNotBlank) },
            publishTime = published?.toString() ?: note.optString("timeText").takeIf(String::isNotBlank),
            publishedAt = published, updatedAt = note.optLong("lastUpdateTime").takeIf { it > 0 },
            tags = (0 until tags.length()).mapNotNull { tags.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) },
            interactions = listOf("likedCount", "collectedCount", "commentCount", "shareCount").associateWith { interact.optString(it, "") },
            images = items.filterIsInstance<ResolvedMedia.Image>(), videos = items.filterIsInstance<ResolvedMedia.Video>(),
            livePhotos = items.filterIsInstance<ResolvedMedia.LivePhoto>(), items = items,
        )
    }

    private fun candidates(stream: JSONObject?): List<MediaCandidate> = buildList {
        stream?.keys()?.forEach { codec ->
            val array = stream.optJSONArray(codec) ?: return@forEach
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    array.optString(index).takeIf(::isHttp)?.let { add(MediaCandidate(it, codec = codec)) }
                    continue
                }
                val backups = item.optJSONArray("backupUrls") ?: JSONArray()
                val urls = (0 until backups.length()).map { backups.optString(it) } + listOf(item.optString("masterUrl"), item.optString("url"))
                urls.filter(::isHttp).distinct().forEach { url -> add(MediaCandidate(
                    url, item.optInt("width"), item.optInt("height"), item.optLong("videoBitrate"), item.optLong("size"), codec,
                )) }
            }
        }
    }.distinctBy { it.url }

    private fun isHttp(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")
    private fun empty() = ParsedXhsNote(emptyList(), emptyList(), null, null, false)

    private fun parseInitialStateRootFromHtml(html: String): JSONObject? {
        val start = html.indexOf("window.__INITIAL_STATE__")
        if (start < 0) return null
        val end = html.indexOf("</script>", start).takeIf { it >= 0 } ?: return null
        val script = html.substring(start, end)
        val equals = script.indexOf('=').takeIf { it >= 0 } ?: return null
        var objectLiteral = extractFirstJsObjectLiteral(script.substring(equals + 1).trim())
            ?: script.substring(equals + 1).trim()
        objectLiteral = objectLiteral.trim().removeSuffix(";").trim()
        objectLiteral = normalizeStateLiteral(objectLiteral)
        return try {
            JSONObject(objectLiteral)
        } catch (error: JSONException) {
            logError("Unable to parse __INITIAL_STATE__: ${error.message}")
            null
        }
    }

    private fun extractFirstJsObjectLiteral(snippet: String): String? {
        var inString = false
        var quote = '\u0000'
        var escaped = false
        var depth = 0
        var start = -1
        snippet.forEachIndexed { index, char ->
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    inString = false
                }
                return@forEachIndexed
            }
            if (char == '\'' || char == '"') {
                inString = true
                quote = char
                return@forEachIndexed
            }
            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0 && --depth == 0 && start >= 0) return snippet.substring(start, index + 1)
            }
        }
        return null
    }

    private fun normalizeStateLiteral(input: String): String {
        val out = StringBuilder(input.length)
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < input.length) {
            val c = input[index]
            if (quote != null) {
                if (c.code < 32) {
                    out.append("\\u%04x".format(c.code))
                } else out.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                index++
                continue
            }
            if (c == '"' || c == '\'') quote = c
            val replacement = when {
                input.startsWith("undefined", index) && !isJsIdentifierChar(input.getOrNull(index - 1)) &&
                    !isJsIdentifierChar(input.getOrNull(index + 9)) -> "undefined" to "null"
                input.startsWith("new Map([])", index) -> "new Map([])" to "[]"
                else -> null
            }
            if (replacement != null) {
                out.append(replacement.second)
                index += replacement.first.length
            } else {
                if (c.code >= 32 || c in "\n\r\t") out.append(c)
                index++
            }
        }
        return out.toString()
    }

    private fun isJsIdentifierChar(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char == '_' || char == '$')

    private fun findNoteObjects(root: JSONObject): List<JSONObject> {
        val notes = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<String>()

        fun addCandidate(note: JSONObject?) {
            if (note == null || note.length() == 0) return
            val id = note.optString("noteId").takeIf { it.isNotBlank() }
            if (id != null && !seenIds.add(id)) return
            notes += note
        }

        try {
            root.optJSONObject("note")?.let { noteRoot ->
                noteRoot.optJSONObject("noteDetailMap")?.let { map ->
                    map.keys().forEach { key -> addCandidate(map.optJSONObject(key)?.optJSONObject("note")) }
                } ?: noteRoot.optJSONObject("note")?.let(::addCandidate)
                    ?: noteRoot.optJSONObject("feed")?.optJSONArray("items")?.let { items ->
                        for (index in 0 until items.length()) addCandidate(items.optJSONObject(index))
                    } ?: addCandidate(noteRoot)
            }
            root.optJSONObject("feed")?.optJSONArray("items")?.let { items ->
                for (index in 0 until items.length()) addCandidate(items.optJSONObject(index))
            }
            root.optJSONObject("noteData")?.optJSONObject("data")?.let { data ->
                addCandidate(data.optJSONObject("noteData") ?: data.optJSONObject("note"))
            }

            val hasLikely = notes.any(::isLikelyNoteObject)
            if (notes.isEmpty() || !hasLikely) {
                val stack = ArrayDeque<Any>()
                stack.add(root)
                var visited = 0
                while (stack.isNotEmpty() && visited < 50_000 && notes.size < 50) {
                    val current = stack.removeLast()
                    visited++
                    when (current) {
                        is JSONObject -> {
                            current.optJSONObject("note")?.let(stack::addLast)
                            if (isLikelyNoteObject(current)) addCandidate(current)
                            current.keys().forEach { key ->
                                when (val value = current.opt(key)) {
                                    is JSONObject, is JSONArray -> stack.addLast(value)
                                }
                            }
                        }
                        is JSONArray -> for (index in 0 until current.length()) {
                            when (val value = current.opt(index)) {
                                is JSONObject, is JSONArray -> stack.addLast(value)
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            logError("Unable to find note objects: ${error.message}")
        }
        return notes
    }

    private fun isLikelyNoteObject(obj: JSONObject): Boolean {
        return try {
            if (obj.has("noteId") && (obj.has("title") || obj.has("desc"))) return true
            val imageArray = obj.optJSONArray("imageList") ?: obj.optJSONArray("images")
            val image = imageArray?.optJSONObject(0)
            image?.let { it.has("urlDefault") || it.has("url") || it.has("traceId") || it.has("infoList") } == true ||
                (obj.optJSONObject("video")?.let { it.has("consumer") || it.has("media") } == true)
        } catch (_: Exception) {
            false
        }
    }

}
