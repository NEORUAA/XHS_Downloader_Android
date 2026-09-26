package com.neoruaa.xhsdn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.os.Build
import android.util.Log
import com.neoruaa.xhsdn.data.settings.LivePhotoFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Creates device-specific live photos without changing the source video or its audio tracks. */
object LivePhotoCreator {
    private const val TAG = "LivePhotoCreator"

    fun resolveFormat(format: LivePhotoFormat): LivePhotoFormat = format.resolve(Build.MANUFACTURER, Build.BRAND)

    @JvmStatic
    fun createLivePhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        context: Context?,
        format: LivePhotoFormat = LivePhotoFormat.AUTO,
        checkActive: () -> Unit = ::checkThread,
    ): Boolean {
        require(outputFile.canonicalFile !in listOf(imageFile.canonicalFile, videoFile.canonicalFile))
        var jpeg: File? = null
        var staged: File? = null
        try {
            checkActive()
            val resolved = resolveFormat(format)
            require(resolved != LivePhotoFormat.VIVO_LEGACY) { "The legacy vivo format requires paired output" }
            val mime = MotionPhotoContainer.videoMime(videoFile, checkActive)
            if (resolved in setOf(LivePhotoFormat.OPLUS, LivePhotoFormat.HUAWEI) && mime != "video/mp4") {
                throw IOException("Selected live photo format requires MP4")
            }
            val countFrames = resolved == LivePhotoFormat.HUAWEI
            val sourceTracks = inspectTracks(videoFile, 0, videoFile.length(), countFrames, checkActive)
            jpeg = File.createTempFile("live_cover_", ".jpg", outputFile.absoluteFile.parentFile)
            if (!convertToJpeg(imageFile, jpeg)) return false
            checkActive()
            addVendorExif(jpeg, resolved)
            staged = File.createTempFile("live_output_", ".jpg", outputFile.absoluteFile.parentFile)
            val layout = MotionPhotoContainer.write(jpeg, videoFile, staged, resolved, mime, sourceTracks.frameCount, checkActive)
            val embeddedTracks = inspectTracks(staged, layout.videoOffset, layout.videoLength, countFrames, checkActive)
            if (sourceTracks != embeddedTracks) throw IOException("Embedded media tracks changed")
            checkActive()
            if (!staged.renameTo(outputFile)) throw IOException("Unable to publish live photo")
            if (context != null) {
                MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), arrayOf("image/jpeg"), null)
            }
            Log.d(TAG, "Created $resolved motion photo with ${sourceTracks.mimeTypes}")
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Unable to create live photo", error)
            return false
        } finally {
            jpeg?.delete()
            staged?.delete()
        }
    }

    /** Both files carry the same persistent ID so a retried download can reuse either saved half. */
    fun createVivoPair(
        imageFile: File, videoFile: File, outputImage: File, outputVideo: File,
        pairId: String, checkActive: () -> Unit = ::checkThread,
    ): Boolean {
        require(outputImage.canonicalFile != outputVideo.canonicalFile)
        require(listOf(outputImage.canonicalFile, outputVideo.canonicalFile).none {
            it == imageFile.canonicalFile || it == videoFile.canonicalFile
        })
        try {
            checkActive()
            if (MotionPhotoContainer.videoMime(videoFile, checkActive) != "video/mp4") return false
            val tracks = inspectTracks(videoFile, 0, videoFile.length())
            if (!convertToJpeg(imageFile, outputImage)) return false
            outputImage.appendBytes(MotionPhotoContainer.vivoImageTail(pairId))
            videoFile.inputStream().use { input -> outputVideo.outputStream().use { out ->
                MotionPhotoContainer.copy(input, out, checkActive)
            } }
            // A size-zero final BMFF box extends to EOF; terminate it before adding a uuid box.
            RandomAccessFile(outputVideo, "rw").use { file ->
                var position = 0L
                while (position < file.length()) {
                    checkActive()
                    file.seek(position)
                    val size = file.readInt().toLong() and 0xffffffffL
                    if (size == 0L) {
                        val remaining = file.length() - position
                        if (remaining > 0xffffffffL) throw IOException("Video box is too large")
                        file.seek(position); file.writeInt(remaining.toInt()); break
                    }
                    if (size == 1L) { file.skipBytes(4); position += file.readLong() } else position += size
                }
            }
            outputVideo.appendBytes(MotionPhotoContainer.vivoVideoBox(pairId))
            if (inspectTracks(outputVideo, 0, outputVideo.length()) != tracks) throw IOException("Paired media tracks changed")
            checkActive()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Unable to create vivo pair", error)
            return false
        }
    }

    private data class Tracks(val mimeTypes: List<String>, val durationMs: Long, val frameCount: Int)

    private fun inspectTracks(
        file: File, offset: Long, length: Long, countFrames: Boolean = false, checkActive: () -> Unit = ::checkThread,
    ): Tracks {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd, offset, length) }
            val types = mutableListOf<String>()
            var durationUs = 0L
            var hasVideo = false
            var frameCount = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                types += mime
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (format.getInteger(MediaFormat.KEY_WIDTH) <= 0 || format.getInteger(MediaFormat.KEY_HEIGHT) <= 0) {
                        throw IOException("Invalid video dimensions")
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                // AAC priming samples can have negative presentation times; only a missing track is EOS.
                if (extractor.sampleTrackIndex != index) throw IOException("Empty media track")
                if (countFrames && mime.startsWith("video/")) {
                    do {
                        checkActive()
                        if (frameCount == Int.MAX_VALUE) throw IOException("Too many video frames")
                        frameCount++
                    } while (extractor.advance())
                }
                extractor.unselectTrack(index)
            }
            if (!hasVideo) throw IOException("No readable video track")
            return Tracks(types, durationUs / 1000, frameCount)
        } finally {
            extractor.release()
        }
    }

    private fun addVendorExif(jpeg: File, format: LivePhotoFormat) {
        val marker = when (format) {
            LivePhotoFormat.OPLUS -> "Oplus_8388608"
            LivePhotoFormat.VIVO -> vivoUserComment()
            else -> return
        }
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_USER_COMMENT, marker)
            saveAttributes()
        }
    }

    internal fun vivoUserComment(date: String = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Date())): String {
        // Observed X300 capture-state structure. Neutral fields are not original capture metadata.
        return "filter: 2237; fileterIntensity: 0.000000; filterMask: 0; captureOrientation: 90;\n" +
            "niceRunStatus: 1002; hdrForward: 7; shaking: 0.000000; highlight: 1; motionR: 0; algolist: 0;\n" +
            "multi-frame: 1;\nbrp_mask: 0;\nbrp_del_th: 0.0000,0.0000;\nbrp_del_sen: 0.0000,0.0000;\n" +
            "delta:1;\nbokeh:1;\nispap:1;\npapproctime: $date;\n" +
            "module: photo;hw-remosaic: false;touch: (-1.0, -1.0);sceneMode: 13107200;cct_value: 0;" +
            "AI_Scene: (-1, -1);aec_lux: 130.098;aec_lux_index: 0;albedo:  ;confidence:  ;motionLevel: -1;" +
            "weatherinfo: weather: cloudy,icon:1,weatherInfo:100;temperature: 37;zeissColor: bright;"
    }

    private fun checkThread() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Live photo creation cancelled")
    }

    /** Converts any Android-decodable image to an orientation-normalized JPEG. */
    private fun convertToJpeg(inputFile: File, jpegFile: File): Boolean {
        var bitmap: Bitmap? = null
        var normalizedBitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image: ${inputFile.absolutePath}")
                false
            } else {
                normalizedBitmap = normalizeBitmapOrientation(inputFile, bitmap)
                Log.d(
                    TAG,
                    "Decoded image: ${bitmap.width}x${bitmap.height}, " +
                        "normalized: ${normalizedBitmap.width}x${normalizedBitmap.height}",
                )
                FileOutputStream(jpegFile).use { output ->
                    normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting to JPEG: ${e.message}")
            false
        } finally {
            if (normalizedBitmap != null && normalizedBitmap !== bitmap && !normalizedBitmap.isRecycled) {
                normalizedBitmap.recycle()
            }
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun normalizeBitmapOrientation(inputFile: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(inputFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            if (orientation == ExifInterface.ORIENTATION_UNDEFINED ||
                orientation == ExifInterface.ORIENTATION_NORMAL
            ) {
                return bitmap
            }

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
                else -> return bitmap
            }

            val normalized = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            Log.d(
                TAG,
                "Applied EXIF orientation $orientation " +
                    "(rotation=${ImageOrientationUtils.rotationDegrees(orientation)}, " +
                    "swapsDimensions=${ImageOrientationUtils.swapsWidthAndHeight(orientation)})",
            )
            normalized
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read EXIF orientation, using original bitmap: ${e.message}")
            bitmap
        }
    }

}
