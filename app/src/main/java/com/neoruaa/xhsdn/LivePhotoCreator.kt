package com.neoruaa.xhsdn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** Creates Motion Photo/Live Photo files by prepending XMP metadata to a JPEG. */
object LivePhotoCreator {
    private const val TAG = "LivePhotoCreator"

    /**
     * Creates a live photo by embedding video into image with XMP metadata.
     */
    @JvmStatic
    fun createLivePhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        context: Context?,
    ): Boolean {
        val jpegFile = File(
            imageFile.parentFile,
            imageFile.name.replace(Regex("\\.[^.]+$"), "") + "_converted.jpg",
        )
        try {
            Log.d(
                TAG,
                "Creating live photo from image: ${imageFile.absolutePath} " +
                    "(size: ${imageFile.length()} bytes) and video: ${videoFile.absolutePath} " +
                    "(size: ${videoFile.length()} bytes) -> output: ${outputFile.absolutePath}",
            )

            Log.d(TAG, "Converting image to JPEG: ${jpegFile.absolutePath}")
            if (!convertToJpeg(imageFile, jpegFile)) {
                Log.e(TAG, "Failed to convert image to JPEG")
                return false
            }
            Log.d(TAG, "Successfully converted to JPEG: ${jpegFile.absolutePath} (size: ${jpegFile.length()} bytes)")

            val videoSize = videoFile.length()
            val xmpData = generateXmpMetadata(videoSize.toInt(), videoSize.toInt()).toByteArray(Charsets.UTF_8)
            val xmpSegment = createXmpApp1Segment(xmpData)
            val result = createLivePhotoStreaming(jpegFile, videoFile, outputFile, xmpSegment)

            if (result && context != null) {
                triggerMediaStoreScan(context, outputFile)
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error creating live photo: ${e.message}", e)
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Failed to delete invalid output: ${outputFile.absolutePath}")
            }
            return false
        } finally {
            if (jpegFile.exists() && !jpegFile.delete()) {
                Log.w(TAG, "Failed to delete temporary JPEG: ${jpegFile.absolutePath}")
            }
        }
    }

    private fun triggerMediaStoreScan(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
        ) { path, uri -> Log.d(TAG, "Scanned: $path -> $uri") }
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

    @Suppress("unused")
    private fun isWebPFormat(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                if (input.read(header) < header.size) {
                    false
                } else {
                    header[0] == 'R'.code.toByte() &&
                        header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() &&
                        header[3] == 'F'.code.toByte() &&
                        header[8] == 'W'.code.toByte() &&
                        header[9] == 'E'.code.toByte() &&
                        header[10] == 'B'.code.toByte() &&
                        header[11] == 'P'.code.toByte()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WebP format: ${e.message}")
            false
        }
    }

    @Suppress("unused")
    private fun convertWebPToJpeg(webpFile: File, jpegFile: File): Boolean {
        var bitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeFile(webpFile.absolutePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode WebP image")
                false
            } else {
                FileOutputStream(jpegFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting WebP to JPEG: ${e.message}")
            false
        } finally {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun generateXmpMetadata(videoSize: Int, videoLengthForOffset: Int): String =
        String.format(
            Locale.ROOT,
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\"" +
                "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
                "    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"" +
                "    xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\"" +
                "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
                "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
                "  GCamera:MotionPhoto=\"1\"" +
                "  GCamera:MotionPhotoVersion=\"1\"" +
                "  GCamera:MotionPhotoPresentationTimestampUs=\"0\"" +
                "  OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"" +
                "  OpCamera:MotionPhotoOwner=\"xhs\"" +
                "  OpCamera:OLivePhotoVersion=\"2\"" +
                "  OpCamera:VideoLength=\"%d\"" +
                "  GCamera:MicroVideoVersion=\"1\"" +
                "  GCamera:MicroVideo=\"1\"" +
                "  GCamera:MicroVideoOffset=\"%d\"" +
                "  GCamera:MicroVideoPresentationTimestampUs=\"0\"" +
                "  MiCamera:XMPMeta=\"&lt;?xml version='1.0' encoding='UTF-8' standalone='yes' ?&gt;\">" +
                "  <Container:Directory>" +
                "    <rdf:Seq>" +
                "      <rdf:li rdf:parseType=\"Resource\">" +
                "        <Container:Item" +
                "          Item:Mime=\"image/jpeg\"" +
                "          Item:Semantic=\"Primary\"" +
                "          Item:Length=\"0\"" +
                "          Item:Padding=\"0\"/>" +
                "      </rdf:li>" +
                "      <rdf:li rdf:parseType=\"Resource\">" +
                "        <Container:Item" +
                "          Item:Mime=\"video/mp4\"" +
                "          Item:Semantic=\"MotionPhoto\"" +
                "          Item:Length=\"%d\"/>" +
                "      </rdf:li>" +
                "    </rdf:Seq>" +
                "  </Container:Directory>" +
                "</rdf:Description>" +
                "</rdf:RDF>" +
                "</x:xmpmeta>",
            videoSize,
            videoSize,
            videoSize,
        )

    private fun createXmpApp1Segment(xmpData: ByteArray): ByteArray {
        val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
        val segmentLength = xmpHeader.size + xmpData.size + 2
        return ByteArrayOutputStream().apply {
            write(0xFF)
            write(0xE1)
            write((segmentLength shr 8) and 0xFF)
            write(segmentLength and 0xFF)
            write(xmpHeader)
            write(xmpData)
        }.toByteArray()
    }

    private fun isLivePhotoValid(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(10)
                val bytesRead = input.read(header)
                if (bytesRead < 2 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) {
                    Log.d(TAG, "File does not have valid JPEG SOI marker")
                    return false
                }
            }

            val buffer = ByteArray(16 * 1024)
            var totalRead = 0
            FileInputStream(file).use { input ->
                while (totalRead < buffer.size) {
                    val count = input.read(buffer, totalRead, buffer.size - totalRead)
                    if (count == -1) break
                    totalRead += count
                }
            }
            val content = String(buffer, 0, totalRead, Charsets.UTF_8)
            val hasXmpMeta = content.contains("xmpmeta")
            val hasMotionPhoto = content.contains("MotionPhoto")
            val hasMicroVideo = content.contains("MicroVideo")
            Log.d(
                TAG,
                "XMP validation - xmpmeta: $hasXmpMeta, MotionPhoto: $hasMotionPhoto, " +
                    "MicroVideo: $hasMicroVideo",
            )
            if (!hasXmpMeta || !hasMotionPhoto) return false

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.d(TAG, "Image has invalid dimensions: ${options.outWidth}x${options.outHeight}")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating live photo: ${e.message}")
            false
        }
    }

    @Suppress("unused")
    private fun readFileToBytes(file: File): ByteArray =
        FileInputStream(file).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

    private fun createLivePhotoStreaming(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        xmpSegment: ByteArray,
    ): Boolean {
        return try {
            FileInputStream(imageFile).use { imageStream ->
                FileInputStream(videoFile).use { videoStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val header = ByteArray(2)
                        if (imageStream.read(header) != 2) {
                            Log.e(TAG, "Could not read image header")
                            return false
                        }
                        outputStream.write(header)
                        outputStream.write(xmpSegment)

                        val buffer = ByteArray(8192)
                        val totalImageBytes = (imageFile.length() - 2).coerceAtLeast(0).toInt()
                        var copiedImageBytes = 0
                        while (copiedImageBytes < totalImageBytes) {
                            val bytesToRead = minOf(buffer.size, totalImageBytes - copiedImageBytes)
                            val bytesRead = imageStream.read(buffer, 0, bytesToRead)
                            if (bytesRead == -1) break
                            outputStream.write(buffer, 0, bytesRead)
                            copiedImageBytes += bytesRead
                        }

                        var copiedVideoBytes = 0L
                        while (true) {
                            val bytesRead = videoStream.read(buffer)
                            if (bytesRead == -1) break
                            outputStream.write(buffer, 0, bytesRead)
                            copiedVideoBytes += bytesRead
                        }
                        outputStream.flush()
                        Log.d(
                            TAG,
                            "Successfully created live photo with streaming approach. " +
                                "Image bytes copied: $copiedImageBytes, Video bytes copied: $copiedVideoBytes, " +
                                "Total file size: ${outputFile.length()}",
                        )
                    }
                }
            }

            if (!isLivePhotoValid(outputFile)) {
                Log.e(TAG, "Created live photo is not valid - failed validation check")
                if (outputFile.exists()) outputFile.delete()
                false
            } else {
                Log.d(TAG, "Live photo validation passed successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in streaming live photo creation: ${e.message}", e)
            if (outputFile.exists()) outputFile.delete()
            false
        }
    }
}
