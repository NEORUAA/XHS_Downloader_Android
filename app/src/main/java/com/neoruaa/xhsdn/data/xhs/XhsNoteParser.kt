package com.neoruaa.xhsdn.data.xhs

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayDeque

/** Immutable media item returned by [XhsNoteParser]. */
data class XhsMedia(
    val url: String,
    val isVideo: Boolean,
)

/** A confirmed image/video pair representing one Live Photo. */
data class XhsLivePhoto(
    val imageUrl: String,
    val videoUrl: String,
)

/** Metadata used by the naming layer and by history previews. */
data class XhsNoteMetadata(
    val userName: String?,
    val userId: String?,
    val title: String?,
    val publishTime: String?,
) {
    fun hasRequiredFields(): Boolean = !userName.isNullOrBlank() && !userId.isNullOrBlank()
}

/** Result of parsing one HTML response. */
data class ParsedXhsNote(
    val mediaUrls: List<String>,
    val livePhotos: List<XhsLivePhoto>,
    val description: String?,
    val metadata: XhsNoteMetadata?,
    val containsVideo: Boolean,
    /** Original CDN URL for transformed media, used for download fallbacks. */
    val originalUrlByTransformed: Map<String, String> = emptyMap(),
)

/**
 * Extracts note state from the HTML returned by xiaohongshu.com.
 *
 * XHS has used several state layouts over time. This parser deliberately
 * handles the known layouts first and then performs a bounded deep scan for a
 * note-shaped object, keeping malformed/huge pages from causing unbounded
 * work on the download thread.
 */
class XhsNoteParser(
    private val urlTransformer: (String) -> String = { it },
    private val logError: (String) -> Unit = {},
) {
    companion object {
        private val imageUrlPattern = Regex(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+\\.(jpg|jpeg|png|gif|mp4|avi|mov|webm|wmv|flv|f4v|swf|mpg|mpeg|asf|3gp|3g2|mkv|webp|heic|heif)",
            RegexOption.IGNORE_CASE,
        )
        private val imageTagPattern = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
        private val controlCharacters = Regex("[\\p{Cntrl}]")
    }

    fun parse(html: String?): ParsedXhsNote {
        if (html.isNullOrBlank()) return ParsedXhsNote(emptyList(), emptyList(), null, null, false)

        val rawMedia = mutableListOf<String>()
        val pairs = mutableListOf<RawPair>()
        val notes = parseInitialStateRootFromHtml(html)?.let(::findNoteObjects).orEmpty()
        if (notes.isNotEmpty()) {
            notes.forEach { note -> rawMedia += extractMediaUrlsFromNote(note, pairs) }
        } else {
            rawMedia += extractUrlsFromHtml(html)
        }

        val originalMedia = rawMedia.toList()
        val originalUrlByTransformed = linkedMapOf<String, String>()
        val transformedPairs = pairs.map { pair ->
            val image = urlTransformer(pair.imageUrl)
            originalUrlByTransformed[image] = pair.imageUrl
            val video = pair.videoUrl?.let { originalVideo ->
                urlTransformer(originalVideo).also { transformedVideo ->
                    originalUrlByTransformed[transformedVideo] = originalVideo
                }
            }
            TransformedPair(image, video, pair.isLivePhoto)
        }

        val finalMedia = mutableListOf<String>()
        val livePhotos = mutableListOf<XhsLivePhoto>()
        transformedPairs.forEach { pair ->
            finalMedia += pair.imageUrl
            if (pair.isLivePhoto && pair.videoUrl != null) {
                livePhotos += XhsLivePhoto(pair.imageUrl, pair.videoUrl)
                finalMedia += pair.videoUrl
            }
        }
        originalMedia.forEach { if (it !in finalMedia) finalMedia += it }

        val firstNote = notes.firstOrNull()
        val description = firstNote?.let(::extractNoteDescription)
        val metadata = firstNote?.let(::buildMetadata)
        return ParsedXhsNote(
            mediaUrls = finalMedia.distinct(),
            livePhotos = livePhotos,
            description = description,
            metadata = metadata,
            containsVideo = finalMedia.any(::isVideoUrl),
            originalUrlByTransformed = originalUrlByTransformed,
        )
    }

    fun description(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val root = parseInitialStateRootFromHtml(html) ?: return null
        return findNoteObjects(root).asSequence()
            .mapNotNull(::extractNoteDescription)
            .firstOrNull { it.isNotBlank() }
    }

    private data class RawPair(
        val imageUrl: String,
        val videoUrl: String?,
        val isLivePhoto: Boolean,
    )

    private data class TransformedPair(
        val imageUrl: String,
        val videoUrl: String?,
        val isLivePhoto: Boolean,
    )

    private fun parseInitialStateRootFromHtml(html: String): JSONObject? {
        val start = html.indexOf("window.__INITIAL_STATE__")
        if (start < 0) return null
        val end = html.indexOf("</script>", start).takeIf { it >= 0 } ?: return null
        val script = html.substring(start, end)
        val equals = script.indexOf('=').takeIf { it >= 0 } ?: return null
        var objectLiteral = extractFirstJsObjectLiteral(script.substring(equals + 1).trim())
            ?: script.substring(equals + 1).trim()
        objectLiteral = objectLiteral.trim().removeSuffix(";").trim()
        objectLiteral = replaceJsUndefinedWithNull(objectLiteral)
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

    private fun replaceJsUndefinedWithNull(input: String): String {
        if (!input.contains("undefined")) return input
        val out = StringBuilder(input.length)
        var inString = false
        var quote = '\u0000'
        var escaped = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (inString) {
                out.append(char)
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) inString = false
                index++
                continue
            }
            if (char == '\'' || char == '"') {
                inString = true
                quote = char
                out.append(char)
                index++
                continue
            }
            if (input.startsWith("undefined", index)) {
                val previous = input.getOrNull(index - 1)
                val next = input.getOrNull(index + "undefined".length)
                if (!isJsIdentifierChar(previous) && !isJsIdentifierChar(next)) {
                    out.append("null")
                    index += "undefined".length
                    continue
                }
            }
            out.append(char)
            index++
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
                while (stack.isNotEmpty() && visited < 50_000 && notes.size < 5) {
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
            val imageArray = obj.optJSONArray("imageList") ?: obj.optJSONArray("images")
            val image = imageArray?.optJSONObject(0)
            image?.let { it.has("urlDefault") || it.has("url") || it.has("traceId") || it.has("infoList") } == true ||
                (obj.optJSONObject("video")?.let { it.has("consumer") || it.has("media") } == true)
        } catch (_: Exception) {
            false
        }
    }

    private fun extractMediaUrlsFromNote(note: JSONObject, pairs: MutableList<RawPair>): List<String> {
        val media = mutableListOf<String>()
        try {
            note.optJSONObject("video")?.let { video ->
                val consumerKey = video.optJSONObject("consumer")?.optString("originVideoKey").orEmpty()
                if (consumerKey.isNotBlank()) {
                    media += "https://sns-video-bd.xhscdn.com/$consumerKey"
                } else {
                    val h265 = video.optJSONObject("media")?.optJSONObject("stream")?.optJSONArray("h265")
                    if (h265 != null) {
                        for (index in 0 until h265.length()) {
                            val value = h265.opt(index)
                            val url = when (value) {
                                is String -> value
                                is JSONObject -> value.optString("url").ifBlank { value.optString("masterUrl") }
                                else -> ""
                            }
                            if (url.startsWith("http")) media += url
                        }
                    }
                }
            }

            val imageList = note.optJSONArray("imageList") ?: note.optJSONArray("images") ?:
                note.optJSONObject("image")?.let { JSONArray().put(it) }
            if (imageList != null) {
                for (index in 0 until imageList.length()) {
                    val image = imageList.optJSONObject(index) ?: continue
                    val imageUrl = image.optString("urlDefault").ifBlank {
                        image.optString("url").ifBlank {
                            image.optString("traceId").takeIf { it.isNotBlank() }?.let { "https://sns-img-qc.xhscdn.com/$it" }
                                ?: image.optJSONArray("infoList")?.let { info ->
                                    (0 until info.length()).asSequence().mapNotNull { info.optJSONObject(it)?.optString("url") }
                                        .firstOrNull { it.isNotBlank() }
                                }.orEmpty()
                        }
                    }
                    if (imageUrl.isBlank()) continue
                    val h264 = image.optJSONObject("stream")?.optJSONArray("h264")?.optJSONObject(0)
                    val liveVideo = h264?.optString("masterUrl").orEmpty().ifBlank { h264?.optString("url").orEmpty() }
                    pairs += RawPair(imageUrl, liveVideo.ifBlank { null }, liveVideo.isNotBlank())
                }
            } else {
                note.keys().forEach { key ->
                    val value = note.optString(key)
                    if (value.contains("xhscdn.com") || value.contains(".mp4") || value.contains(".jpg") || value.contains(".png")) {
                        media += value
                    }
                }
            }
        } catch (error: JSONException) {
            logError("Unable to extract note media: ${error.message}")
        }
        return media
    }

    private fun extractUrlsFromHtml(html: String): List<String> {
        val urls = linkedSetOf<String>()
        imageTagPattern.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf(::isValidMediaUrl)?.let(urls::add)
        }
        imageUrlPattern.findAll(html).forEach { match ->
            match.value.takeIf(::isValidMediaUrl)?.let(urls::add)
        }
        return urls.toList()
    }

    private fun isValidMediaUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains(".jpg") || normalized.contains(".jpeg") || normalized.contains(".png") ||
            normalized.contains(".gif") || normalized.contains(".mp4") || normalized.contains(".webm") ||
            normalized.contains("xhscdn.com") || normalized.contains("xiaohongshu.com")
    }

    private fun isVideoUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains(".mp4") || normalized.contains(".mov") || normalized.contains(".avi") ||
            normalized.contains(".webm") || normalized.contains("video") || normalized.contains("masterurl") ||
            normalized.contains("stream") || normalized.contains("sns-video") || normalized.contains("/spectrum/")
    }

    private fun extractNoteDescription(note: JSONObject): String? {
        val title = note.optString("title")
        val description = note.optString("desc")
        return (title + description).takeIf { it.isNotBlank() }
    }

    private fun buildMetadata(note: JSONObject): XhsNoteMetadata? {
        val user = note.optJSONObject("user") ?: note.optJSONObject("user_info")
        val username = firstNonBlank(
            user?.optString("nickname"), user?.optString("name"), user?.optString("userName"),
            user?.optString("nickName"), note.optString("author"), note.optString("userName"),
        )
        val userId = firstNonBlank(
            user?.optString("redId"), user?.optString("red_id"), user?.optString("userId"),
            user?.optString("userid"), user?.optString("user_id"), user?.optString("id"),
            note.optString("userId"), note.optString("uid"), note.optString("user_id"),
        )
        if (username.isNullOrBlank() || userId.isNullOrBlank()) return null

        val title = firstNonBlank(note.optString("title"), note.optString("desc"), note.optString("description"), note.optString("noteId"))
        val publishTime = firstNonBlank(
            note.optString("time"), note.optString("timeText"), note.optString("displayTime"),
            note.optString("publishTime"), note.optString("publish_time"), note.optString("createTime"),
        )
        return XhsNoteMetadata(
            userName = sanitize(username, 40),
            userId = sanitize(userId, 40),
            title = sanitize(title, 80),
            publishTime = sanitize(publishTime, 60),
        ).takeIf(XhsNoteMetadata::hasRequiredFields)
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun sanitize(value: String?, maxLength: Int): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .replace(controlCharacters, "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return normalized.take(maxLength).takeIf { it.isNotBlank() }
    }
}
