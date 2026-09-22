package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.settings.*
import java.net.URI
import kotlinx.serialization.encodeToString
import com.neoruaa.xhsdn.data.network.ResumableTransfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared naming and candidate policy for normal, selective and WebView downloads. */
object NoteOutput {
    fun eligible(note: ResolvedNote, options: DownloadOptions): List<ResolvedMedia> = note.orderedMedia.filter {
        when (it) {
            is ResolvedMedia.Image -> if (it.cover) options.videoCoverDownload else options.imageDownload
            is ResolvedMedia.Video -> options.videoDownload
            is ResolvedMedia.LivePhoto -> options.imageDownload || (options.videoDownload && options.livePhotoMode != LivePhotoMode.STILL)
        }
    }

    fun imageUrls(image: ResolvedMedia.Image, format: ImageFormat): List<String> {
        val raw = image.originalUrl
        val uri = runCatching { URI(raw) }.getOrNull()
        val parts = uri?.path.orEmpty().trimStart('/').split('/')
        val token = (if (parts.size > 2 && parts[1].matches(Regex("[a-fA-F0-9]{32}"))) parts.drop(2) else parts)
            .joinToString("/").substringBefore('!')
        val knownCdn = uri?.host?.let { it.endsWith(".xhscdn.com") || it.endsWith(".xiaohongshu.com") } == true
        if (!knownCdn || token.isBlank()) return listOf(image.sourceUrl, raw).distinct()
        val preferred = if (format == ImageFormat.AUTO) "https://sns-img-bd.xhscdn.com/$token"
            else "https://ci.xiaohongshu.com/$token?imageView2/format/${format.name.lowercase(Locale.ROOT)}"
        return listOf(preferred, image.sourceUrl, raw).distinct()
    }

    fun videoUrls(video: ResolvedMedia.Video, preference: VideoPreference): List<String> =
        video.candidates.sortedWith(compareByDescending<com.neoruaa.xhsdn.core.model.MediaCandidate> { it.original }
            .thenByDescending { when (preference) {
                VideoPreference.RESOLUTION -> it.height.toLong() * it.width
                VideoPreference.BITRATE -> it.bitrate
                VideoPreference.SIZE -> it.size
            } }).map { it.url }.plus(video.sourceUrl).distinct()

    fun safePart(value: String, fallback: String): String {
        val clean = value.replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_").trim().trim('.').ifBlank { fallback }
        val result = StringBuilder()
        for (codepoint in clean.codePoints().toArray()) {
            val next = String(Character.toChars(codepoint))
            if ((result.toString() + next).toByteArray().size > 160) break
            result.append(next)
        }
        return result.toString().ifBlank { fallback }
    }

    fun fileName(note: ResolvedNote, settings: AppSettings, index: Int, extension: String, createdAt: Long): String {
        val date = note.publishedAt?.let { SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(it)) }
            ?: note.publishTime.orEmpty()
        val values = mapOf("username" to note.authorName.orEmpty(), "userId" to note.authorId.orEmpty(),
            "title" to note.title.orEmpty(), "postId" to note.noteId, "publishTime" to date,
            "index" to (index + 1).toString(), "index_padded" to (index + 1).toString().padStart(2, '0'),
            "downloadTimestamp" to createdAt.toString())
        val template = if (settings.useCustomNamingFormat) settings.customNamingTemplate else "{postId}"
        val base = Regex("\\{([a-zA-Z_]+)\\}").replace(template) { values[it.groupValues[1]].orEmpty() }
        return safePart(base, note.noteId.ifBlank { "note" }) + "_${(index + 1).toString().padStart(2, '0')}.$extension"
    }

    fun recordKey(noteId: String, mediaId: String, settings: AppSettings, folders: List<String>): String {
        val options = settings.downloadOptions
        val identity = listOf(noteId, mediaId, settings.customStorageTreeUri.orEmpty(),
            if (settings.useCustomNamingFormat) settings.customNamingTemplate else "{postId}",
            options.imageFormat.name, options.videoPreference.name, options.livePhotoMode.name,
            options.imageDownload.toString(), options.videoDownload.toString(), options.videoCoverDownload.toString(),
            options.writePublishTime.toString()) + folders
        return ResumableTransfer.fingerprint(DownloadJson.encodeToString(identity))
    }

    fun folders(note: ResolvedNote, options: DownloadOptions, remark: String): List<String> = buildList {
        if (options.authorArchive) add(safePart("${remark.ifBlank { note.authorName.orEmpty() }}_${note.authorId.orEmpty()}", "author"))
        if (options.noteArchive) add(safePart("${note.title.orEmpty()}_${note.noteId}", "note"))
    }

    fun text(note: ResolvedNote, markdown: Boolean): String = buildString {
        append(if (markdown) "# " else "").append(note.title.orEmpty()).append("\n\n")
        append(note.body.ifBlank { note.description.orEmpty() }).append("\n\n")
        append(note.authorName.orEmpty()).append(" [").append(note.authorId.orEmpty()).append("]\n")
        append(note.publishTime.orEmpty()).append('\n')
        if (note.tags.isNotEmpty()) append(note.tags.joinToString(" ") { "#$it" }).append('\n')
        append(note.canonicalUrl).append('\n')
    }
}
