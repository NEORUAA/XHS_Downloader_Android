package com.neoruaa.xhsdn.domain.download

/** Byte counts include resumed bytes; completion means the source file was validated. */
data class MediaTransferProgress(
    val downloaded: Long = 0,
    val total: Long = 0,
    val complete: Boolean = false,
    val failed: Boolean = false,
) {
    val fraction: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        fun combine(parts: List<MediaTransferProgress>): MediaTransferProgress = MediaTransferProgress(
            downloaded = parts.sumOf { it.downloaded },
            total = if (parts.isNotEmpty() && parts.all { it.total > 0 }) parts.sumOf { it.total } else 0,
            complete = parts.isNotEmpty() && parts.all { it.complete },
            failed = parts.any { it.failed },
        )
    }
}
