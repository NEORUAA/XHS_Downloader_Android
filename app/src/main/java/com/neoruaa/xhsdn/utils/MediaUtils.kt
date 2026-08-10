package com.neoruaa.xhsdn.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.neoruaa.xhsdn.ImageOrientationUtils
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import com.neoruaa.xhsdn.viewmodels.MediaType
import java.io.File

fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    return runCatching {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(filePath, options) ?: return null
        bitmap.applyExifOrientation(readExifOrientation(filePath))
    }.getOrNull()
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}

fun createVideoThumbnail(file: File): Bitmap? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) // 获取第1秒的帧
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

fun Context.decodeSampledBitmap(ref: StoredMediaRef, reqWidth: Int, reqHeight: Int): Bitmap? {
    ref.legacyPath?.let { return decodeSampledBitmap(it, reqWidth, reqHeight) }
    val orientation = readExifOrientation(ref)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsDescriptor = contentResolver.openFileDescriptor(ref.androidUri, "r")
            ?: return null
        boundsDescriptor.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        }
        contentResolver.openFileDescriptor(ref.androidUri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
        }?.applyExifOrientation(orientation)
    }.getOrNull()
}

fun readImageAspectRatio(filePath: String): Float? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(filePath, options)
    ImageOrientationUtils.aspectRatio(
        width = options.outWidth,
        height = options.outHeight,
        orientation = readExifOrientation(filePath),
    )
}.getOrNull()

fun Context.createVideoThumbnail(ref: StoredMediaRef): Bitmap? {
    ref.legacyPath?.let { return createVideoThumbnail(File(it)) }
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, ref.androidUri)
            retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

fun Context.readMediaAspectRatio(ref: StoredMediaRef, type: MediaType): Float? = runCatching {
    when (type) {
        MediaType.IMAGE -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (ref.legacyPath != null) {
                BitmapFactory.decodeFile(ref.legacyPath, options)
            } else {
                contentResolver.openFileDescriptor(ref.androidUri, "r")?.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
                }
            }
            ImageOrientationUtils.aspectRatio(
                width = options.outWidth,
                height = options.outHeight,
                orientation = readExifOrientation(ref),
            )
        }

        MediaType.VIDEO -> {
            val retriever = MediaMetadataRetriever()
            try {
                if (ref.legacyPath != null) {
                    retriever.setDataSource(ref.legacyPath)
                } else {
                    retriever.setDataSource(this, ref.androidUri)
                }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toFloatOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toFloatOrNull()
                if (width != null && height != null && height > 0f) width / height else null
            } finally {
                retriever.release()
            }
        }

        MediaType.OTHER -> null
    }
}.getOrNull()

private fun readExifOrientation(filePath: String): Int = runCatching {
    ExifInterface(filePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun Context.readExifOrientation(ref: StoredMediaRef): Int {
    ref.legacyPath?.let { return readExifOrientation(it) }
    return runCatching {
        contentResolver.openFileDescriptor(ref.androidUri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}

private fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return this
    }

    val orientedBitmap = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (orientedBitmap !== this) recycle()
    return orientedBitmap
}

fun Context.storedMediaSize(ref: StoredMediaRef): Long? {
    if (ref.legacyPath != null) {
        return File(ref.legacyPath).takeIf(File::exists)?.length()
    }
    if (ref.sizeBytes > 0L) return ref.sizeBytes
    return runCatching {
        contentResolver.query(
            ref.androidUri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()
}

fun Context.storedMediaExists(ref: StoredMediaRef): Boolean {
    ref.legacyPath?.let { return File(it).exists() }
    return runCatching {
        contentResolver.openFileDescriptor(ref.androidUri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}

fun Context.deleteStoredMedia(ref: StoredMediaRef): Boolean {
    ref.legacyPath?.let { path ->
        val file = File(path)
        return !file.exists() || file.delete()
    }
    return runCatching {
        if (DocumentsContract.isDocumentUri(this, ref.androidUri)) {
            DocumentsContract.deleteDocument(contentResolver, ref.androidUri)
        } else {
            contentResolver.delete(ref.androidUri, null, null) > 0
        }
    }.getOrDefault(false)
}

fun detectMediaType(path: String): MediaType {
    val extension = path.substringAfterLast(".", "")
    return if (extension.lowercase() in listOf("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm")) {
        MediaType.VIDEO
    } else if (extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif")) {
        MediaType.IMAGE
    } else {
        MediaType.OTHER
    }
}

fun detectMediaType(ref: StoredMediaRef): MediaType {
    return when {
        ref.mimeType.startsWith("video/") -> MediaType.VIDEO
        ref.mimeType.startsWith("image/") -> MediaType.IMAGE
        else -> detectMediaType(ref.displayName.ifBlank { ref.path })
    }
}
