package com.neoruaa.xhsdn.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val DownloadJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
enum class ImageFormat { AUTO, JPEG, PNG, WEBP, HEIC, AVIF }
@Serializable
enum class VideoPreference { RESOLUTION, BITRATE, SIZE, COMPATIBILITY }
@Serializable
enum class LivePhotoMode { MERGED, SEPARATE, STILL }
@Serializable
enum class LivePhotoFormat {
    AUTO, STANDARD, XIAOMI, OPLUS, SAMSUNG, VIVO, VIVO_LEGACY, HUAWEI;

    fun resolve(manufacturer: String, brand: String): LivePhotoFormat {
        if (this != AUTO) return this
        val names = setOf(manufacturer.lowercase(java.util.Locale.ROOT), brand.lowercase(java.util.Locale.ROOT))
        return when {
            names.any { it in setOf("oppo", "oneplus", "realme", "oplus") } -> OPLUS
            "samsung" in names -> SAMSUNG
            names.any { it in setOf("vivo", "iqoo") } -> VIVO
            names.any { it in setOf("huawei", "honor") } -> HUAWEI
            names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
            else -> STANDARD
        }
    }
}
@Serializable
enum class NoteFormat { NONE, TXT, MARKDOWN, BOTH }

@Serializable
data class DownloadOptions(
    val imageFormat: ImageFormat = ImageFormat.AUTO,
    val videoPreference: VideoPreference = VideoPreference.RESOLUTION,
    val imageDownload: Boolean = true,
    val videoDownload: Boolean = true,
    val videoCoverDownload: Boolean = false,
    val commentImageDownload: Boolean = false,
    val livePhotoMode: LivePhotoMode = LivePhotoMode.MERGED,
    val livePhotoFormat: LivePhotoFormat = LivePhotoFormat.AUTO,
    val skipExisting: Boolean = false,
    val authorArchive: Boolean = false,
    val noteArchive: Boolean = false,
    val noteFormat: NoteFormat = NoteFormat.NONE,
    val writePublishTime: Boolean = false,
    val timeoutSeconds: Int = 45,
    val maxRetries: Int = 3,
    val proxy: String = "",
    val proxyDownload: Boolean = false,
    val useWebSession: Boolean = true,
)
