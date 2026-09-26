package com.neoruaa.xhsdn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoruaa.xhsdn.data.settings.LivePhotoFormat
import com.neoruaa.xhsdn.data.storage.AndroidStorageSink
import com.neoruaa.xhsdn.data.storage.StorageDestination
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivePhotoCreatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun everySingleFileFormatDecodesAndPreservesAvSamplesAfterMediaStoreSave() = fixture { image, video, directory ->
        val expected = samples(video, 0, video.length())
        assertEquals(setOf("video/avc", "audio/mp4a-latm"), expected.keys)
        for (format in LivePhotoFormat.entries.filter { it !in listOf(LivePhotoFormat.AUTO, LivePhotoFormat.VIVO_LEGACY) }) {
            val output = File(directory, "${format.name}_MP.jpg")
            assertTrue(format.name, LivePhotoCreator.createLivePhoto(image, video, output, null, format))
            val bytes = output.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            if (format == LivePhotoFormat.HUAWEI) {
                assertEquals("0:12", String(bytes.copyOfRange(bytes.size - 40, bytes.size - 20)).trim())
            }
            val offset = if (format == LivePhotoFormat.HUAWEI) output.length() - video.length() - 60 else {
                val length = Regex("Item:Semantic=\"MotionPhoto\"\\s+Item:Length=\"(\\d+)\"").find(text)!!.groupValues[1].toLong()
                output.length() - length
            }
            assertEquals(format.name, expected, samples(output, offset, video.length()))
            assertArrayEquals(video.readBytes(), bytes.copyOfRange(offset.toInt(), offset.toInt() + video.length().toInt()))
            val bitmap = BitmapFactory.decodeFile(output.absolutePath)
            assertNotNull(bitmap)
            assertEquals(120, bitmap.width)
            assertEquals(160, bitmap.height)
            bitmap.recycle()
            val exif = ExifInterface(output.absolutePath)
            if (format == LivePhotoFormat.OPLUS) assertEquals("Oplus_8388608", exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
            if (format == LivePhotoFormat.VIVO) assertTrue(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)!!.contains("multi-frame: 1;"))
            val retriever = MediaMetadataRetriever()
            try {
                FileInputStream(output).use { retriever.setDataSource(it.fd, offset, video.length()) }
                val frame = retriever.getFrameAtTime(0)
                assertNotNull(format.name, frame)
                frame?.recycle()
            } finally { retriever.release() }
            runBlocking {
                val sink = AndroidStorageSink(context)
                val stored = sink.storeArchived(StorageDestination.DefaultMediaStore, "live_test_${System.nanoTime()}_${output.name}", "image/jpeg", output.length(), emptyList()) { stream ->
                    output.inputStream().use { it.copyTo(stream) }
                }
                try {
                    val uri = android.net.Uri.parse(stored.uri)
                    assertEquals("image/jpeg", context.contentResolver.getType(uri))
                    context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
                    if (format == LivePhotoFormat.STANDARD) {
                        val duplicate = sink.storeArchived(StorageDestination.DefaultMediaStore, stored.displayName,
                            "image/jpeg", output.length(), emptyList()) { stream -> output.inputStream().use { it.copyTo(stream) } }
                        try {
                            assertNotEquals(stored.displayName, duplicate.displayName)
                            assertTrue(duplicate.displayName.endsWith("MP.jpg"))
                        } finally { context.contentResolver.delete(duplicate.androidUri, null, null) }
                    }
                } finally {
                    context.contentResolver.delete(android.net.Uri.parse(stored.uri), null, null)
                }
            }
        }
    }

    @Test fun vivoPairHasMatchingIdsAndPreservesSamples() = fixture { image, video, directory ->
        val outputImage = File(directory, "pair.jpg")
        val outputVideo = File(directory, "pair.mp4")
        val id = "0123456789abcdef0123456789ab"
        assertTrue(LivePhotoCreator.createVivoPair(image, video, outputImage, outputVideo, id))
        assertTrue(String(outputImage.readBytes()).contains(id))
        assertTrue(String(outputVideo.readBytes()).contains(id))
        assertEquals(samples(video, 0, video.length()), samples(outputVideo, 0, outputVideo.length()))
        assertEquals("video/mp4", MotionPhotoContainer.videoMime(outputVideo))
        val refs = mutableListOf<com.neoruaa.xhsdn.data.storage.StoredMediaRef>()
        try {
            val stem = "vivo_pair_test_${System.nanoTime()}"
            val paths = listOf(outputImage to "image/jpeg", outputVideo to "video/mp4").map { (file, mime) ->
                val ref = AndroidStorageSink(context).storeArchived(StorageDestination.DefaultMediaStore,
                    "$stem.${file.extension}", mime, file.length(), emptyList(), livePhotoPair = true) { stream ->
                    file.inputStream().use { it.copyTo(stream) }
                }
                refs += ref
                context.contentResolver.query(ref.androidUri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
            }
            assertEquals(listOf("DCIM/xhsdn/", "DCIM/xhsdn/"), paths)
            assertEquals(refs[0].displayName.substringBeforeLast('.'), refs[1].displayName.substringBeforeLast('.'))
        } finally { refs.forEach { context.contentResolver.delete(it.androidUri, null, null) } }
    }

    @Test fun mp4OnlyProfilesRejectMovInsteadOfClaimingUnsupportedCompatibility() = fixture { image, video, directory ->
        val mov = File(directory, "clip.mov").apply {
            val data = video.readBytes()
            "qt  ".toByteArray().copyInto(data, 8)
            writeBytes(data)
        }
        assertEquals("video/quicktime", MotionPhotoContainer.videoMime(mov))
        for (format in listOf(LivePhotoFormat.OPLUS, LivePhotoFormat.HUAWEI)) {
            val output = File(directory, "${format.name}_MP.jpg")
            assertFalse(LivePhotoCreator.createLivePhoto(image, mov, output, null, format))
            assertFalse(output.exists())
        }
    }

    @Test fun invalidVideoAndCancellationDoNotPublishOrRemoveExistingOutput() = fixture { image, video, directory ->
        val output = File(directory, "existing.jpg").apply { writeText("keep") }
        val invalid = File(directory, "invalid.mp4").apply { writeText("not a video") }
        assertFalse(LivePhotoCreator.createLivePhoto(image, invalid, output, null, LivePhotoFormat.STANDARD))
        assertEquals("keep", output.readText())
        var checks = 0
        try {
            LivePhotoCreator.createLivePhoto(image, video, output, null, LivePhotoFormat.STANDARD) {
                if (++checks >= 5) throw kotlinx.coroutines.CancellationException("Test cancellation")
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("keep", output.readText())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("live_cover_") || it.name.startsWith("live_output_") })
    }

    private fun samples(file: File, offset: Long, length: Long): Map<String, String> {
        val extractor = MediaExtractor()
        return try {
            FileInputStream(file).use { extractor.setDataSource(it.fd, offset, length) }
            (0 until extractor.trackCount).associate { track ->
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteBuffer.allocate(256 * 1024)
                var count = 0
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    digest.update(buffer.array(), 0, size)
                    count++
                    extractor.advance()
                }
                assertTrue(count > 0)
                extractor.unselectTrack(track)
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!! to digest.digest().joinToString("") { "%02x".format(it) }
            }
        } finally { extractor.release() }
    }

    private fun fixture(block: (File, File, File) -> Unit) {
        val directory = File(context.cacheDir, "live_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            val image = File(directory, "rotated.jpg")
            val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            ExifInterface(image.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val video = File(directory, "source.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("live_photo_av.mp4").use { input ->
                video.outputStream().use { input.copyTo(it) }
            }
            block(image, video, directory)
        } finally { directory.deleteRecursively() }
    }
}
