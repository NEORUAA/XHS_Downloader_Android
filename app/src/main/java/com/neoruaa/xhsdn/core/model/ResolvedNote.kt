package com.neoruaa.xhsdn.core.model

import kotlinx.serialization.Serializable

/** A snapshot of one note. Signed URLs are transport details, never media identities. */
@Serializable
data class ResolvedNote(
    val canonicalUrl: String,
    val title: String?,
    val description: String?,
    val authorName: String?,
    val authorId: String?,
    val publishTime: String?,
    val images: List<ResolvedMedia.Image> = emptyList(),
    val videos: List<ResolvedMedia.Video> = emptyList(),
    val livePhotos: List<ResolvedMedia.LivePhoto> = emptyList(),
    val noteId: String = "",
    val type: String = "normal",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: Long? = null,
    val publishedAt: Long? = null,
    val interactions: Map<String, String> = emptyMap(),
    val items: List<ResolvedMedia> = emptyList(),
) {
    val orderedMedia: List<ResolvedMedia>
        get() = items.ifEmpty { images + videos + livePhotos }
    val mediaCount: Int get() = orderedMedia.size
}

@Serializable
data class MediaCandidate(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0,
    val size: Long = 0,
    val codec: String = "",
    val original: Boolean = false,
)

@Serializable
sealed interface ResolvedMedia {
    val sourceUrl: String
    val id: String
    val previewUrl: String

    @Serializable
    data class Image(
        override val sourceUrl: String,
        val originalUrl: String = sourceUrl,
        override val id: String = sourceUrl,
        override val previewUrl: String = originalUrl,
        val cover: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
    ) : ResolvedMedia

    @Serializable
    data class Video(
        override val sourceUrl: String,
        val originalUrl: String = sourceUrl,
        override val id: String = sourceUrl,
        override val previewUrl: String = "",
        val candidates: List<MediaCandidate> = listOf(MediaCandidate(sourceUrl)),
    ) : ResolvedMedia

    @Serializable
    data class LivePhoto(
        val image: Image,
        val video: Video,
        override val id: String = image.id,
    ) : ResolvedMedia {
        override val sourceUrl: String get() = image.sourceUrl
        override val previewUrl: String get() = image.previewUrl
    }
}
