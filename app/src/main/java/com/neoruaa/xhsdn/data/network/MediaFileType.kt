package com.neoruaa.xhsdn.data.network

import java.io.File

/** Identify content before publishing, regardless of the server's filename or Content-Type. */
data class MediaFileType(val extension: String, val mimeType: String) {
    companion object {
        fun detect(file: File): MediaFileType? = file.inputStream().use { input ->
            val bytes = ByteArray(64)
            val count = input.read(bytes)
            fun starts(vararg signature: Int) = count >= signature.size && signature.indices.all { (bytes[it].toInt() and 255) == signature[it] }
            fun text(start: Int, size: Int) = if (count >= start + size) String(bytes, start, size, Charsets.ISO_8859_1) else ""
            when {
                starts(255, 216, 255) -> MediaFileType("jpg", "image/jpeg")
                starts(137, 80, 78, 71, 13, 10, 26, 10) -> MediaFileType("png", "image/png")
                text(0, 3) == "GIF" -> MediaFileType("gif", "image/gif")
                text(0, 4) == "RIFF" && text(8, 4) == "WEBP" -> MediaFileType("webp", "image/webp")
                text(4, 4) == "ftyp" -> when {
                    text(8, count.coerceAtMost(64) - 8).contains("avif") || text(8, 4) == "avis" -> MediaFileType("avif", "image/avif")
                    text(8, 4) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> MediaFileType("heic", "image/heic")
                    text(8, 4) == "qt  " -> MediaFileType("mov", "video/quicktime")
                    else -> MediaFileType("mp4", "video/mp4")
                }
                starts(26, 69, 223, 163) -> MediaFileType("webm", "video/webm")
                else -> null
            }
        }
    }
}
