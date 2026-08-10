package com.neoruaa.xhsdn.core.model

/** Immutable note model shared by resolution, selection and download features. */
data class ResolvedNote(
    val canonicalUrl: String,
    val title: String?,
    val description: String?,
    val authorName: String?,
    val authorId: String?,
    val publishTime: String?,
    val images: List<ResolvedMedia.Image>,
    val videos: List<ResolvedMedia.Video>,
    val livePhotos: List<ResolvedMedia.LivePhoto>
) {
    val mediaCount: Int
        get() = images.size + videos.size + livePhotos.size
}

sealed interface ResolvedMedia {
    val sourceUrl: String

    data class Image(
        override val sourceUrl: String,
        val originalUrl: String = sourceUrl
    ) : ResolvedMedia

    data class Video(
        override val sourceUrl: String,
        val originalUrl: String = sourceUrl
    ) : ResolvedMedia

    data class LivePhoto(
        val image: Image,
        val video: Video
    ) : ResolvedMedia {
        override val sourceUrl: String
            get() = image.sourceUrl
    }
}
