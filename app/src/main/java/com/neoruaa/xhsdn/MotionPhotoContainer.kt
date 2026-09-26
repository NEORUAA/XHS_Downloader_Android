package com.neoruaa.xhsdn

import com.neoruaa.xhsdn.data.settings.LivePhotoFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary layout only; Android image decoding and media-track checks belong to the caller.
 * Protocol references and device-validation limits are recorded in docs/LIVE_PHOTO_COMPATIBILITY.md.
 */
internal object MotionPhotoContainer {
    data class Layout(val videoOffset: Long, val videoLength: Long, val totalLength: Long)

    fun write(
        jpeg: File, video: File, output: File, format: LivePhotoFormat,
        videoMime: String, videoFrameCount: Int, checkActive: () -> Unit = {},
    ): Layout {
        require(format != LivePhotoFormat.AUTO && format != LivePhotoFormat.VIVO_LEGACY)
        require(video.length() > 0)
        val prefix = if (format == LivePhotoFormat.SAMSUNG) samsungTag(0x0a30, "MotionPhoto_Data") else byteArrayOf()
        val suffix = when (format) {
            LivePhotoFormat.SAMSUNG -> samsungFooter(video.length(), prefix.size)
            LivePhotoFormat.HUAWEI -> huaweiTail(video.length(), videoFrameCount)
            else -> byteArrayOf()
        }
        val xmp = if (format == LivePhotoFormat.HUAWEI) byteArrayOf() else segment(0xe1,
            "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() +
                xmp(format, video.length() + suffix.size, prefix.size, videoMime).toByteArray())
        val mpfSize = if (format == LivePhotoFormat.OPLUS) mpf(0).size else 0
        val imageSize = jpeg.length() + xmp.size + mpfSize
        val layout = Layout(imageSize + prefix.size, video.length(), imageSize + prefix.size + video.length() + suffix.size)
        jpeg.inputStream().buffered().use { image ->
            output.outputStream().buffered().use { out ->
                if (image.read() != 0xff || image.read() != 0xd8) throw IOException("Missing JPEG SOI")
                out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                // Xiaomi retains the SOI -> XMP -> JPEG layout of the previously working writer.
                if (format != LivePhotoFormat.XIAOMI) copyLeadingMetadata(image, out)
                out.write(xmp)
                if (mpfSize > 0) out.write(mpf(imageSize))
                copy(image, out, checkActive)
                out.write(prefix)
                video.inputStream().buffered().use { copy(it, out, checkActive) }
                out.write(suffix)
            }
        }
        checkActive()
        RandomAccessFile(output, "r").use { file ->
            if (file.length() != layout.totalLength) throw IOException("Motion photo length mismatch")
            file.seek(imageSize - 2)
            if (file.readUnsignedShort() != 0xffd9) throw IOException("Missing JPEG EOI before video")
            file.seek(layout.videoOffset + 4)
            if (file.readInt() != 0x66747970) throw IOException("Missing video at declared offset")
        }
        return layout
    }

    private fun copyLeadingMetadata(input: InputStream, output: OutputStream) {
        while (true) {
            input.mark(4)
            if (input.read() != 0xff) throw IOException("Invalid JPEG marker")
            val marker = input.read()
            if (marker !in 0xe0..0xef) { input.reset(); return }
            val hi = input.read()
            val lo = input.read()
            val length = (hi shl 8) or lo
            if (hi < 0 || lo < 0 || length < 2) throw IOException("Invalid JPEG segment")
            output.write(byteArrayOf(0xff.toByte(), marker.toByte(), hi.toByte(), lo.toByte()))
            repeat(length - 2) {
                val value = input.read()
                if (value < 0) throw IOException("Truncated JPEG metadata")
                output.write(value)
            }
        }
    }

    fun copy(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    /** Reject truncated ISO-BMFF data and detect MOV without trusting its extension. */
    fun videoMime(file: File, checkActive: () -> Unit = {}): String {
        RandomAccessFile(file, "r").use { input ->
            var position = 0L
            var mime = "video/mp4"
            var hasMovie = false
            var hasData = false
            while (position < input.length()) {
                checkActive()
                if (input.length() - position < 8) throw IOException("Truncated video box")
                input.seek(position)
                var size = input.readInt().toLong() and 0xffffffffL
                val type = input.readInt()
                var headerSize = 8L
                if (size == 1L) {
                    if (input.length() - position < 16) throw IOException("Truncated extended video box")
                    size = input.readLong()
                    headerSize = 16
                } else if (size == 0L) size = input.length() - position
                if (size < headerSize || size > input.length() - position) throw IOException("Invalid video box size")
                if (position == 0L) {
                    if (type != 0x66747970 || size < headerSize + 8) throw IOException("Missing video file type")
                    if (input.readInt() == 0x71742020) mime = "video/quicktime"
                }
                if (type == 0x6d6f6f76) hasMovie = size > headerSize
                if (type == 0x6d646174) hasData = hasData || size > headerSize
                position += size
            }
            if (!hasMovie || !hasData) throw IOException("Video has no movie or media data")
            return mime
        }
    }

    fun xmp(format: LivePhotoFormat, videoLength: Long, padding: Int, mime: String): String {
        require(videoLength > 0 && padding >= 0)
        require(mime == "video/mp4" || mime == "video/quicktime")
        if (format == LivePhotoFormat.XIAOMI) {
            require(padding == 0)
            return xiaomiXmp(videoLength).replace("video/mp4", mime)
        }
        val vendor = when (format) {
            LivePhotoFormat.OPLUS -> """
                xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
                OpCamera:MotionPhotoPrimaryPresentationTimestampUs="0"
                OpCamera:MotionPhotoOwner="oplus" OpCamera:OLivePhotoVersion="2"
                OpCamera:MotionPhotoFeatureFlag="1" OpCamera:VideoLength="$videoLength"
            """.trimIndent()
            LivePhotoFormat.VIVO -> """
                xmlns:VCamera="http://ns.vivo.com/photos/1.0/camera/"
                VCamera:VMotionPhotoVersion="1" VCamera:VMotionPhotoSource="1" VCamera:VMediaKitVersion="1.0.0.9"
            """.trimIndent()
            else -> ""
        }
        // Retain the v1 offset for older Google readers. It is measured from EOF.
        val legacy = if (format == LivePhotoFormat.STANDARD) """
            GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="$videoLength" GCamera:MicroVideoPresentationTimestampUs="-1"
        """.trimIndent() else ""
        // Match vendor writers' default cover convention; only generic/Samsung use unknown time.
        val timestamp = if (format in setOf(LivePhotoFormat.OPLUS, LivePhotoFormat.VIVO)) 0 else -1
        val primaryExtent = if (format == LivePhotoFormat.VIVO) "" else "Item:Length=\"0\" Item:Padding=\"$padding\""
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
             xmlns:Container="http://ns.google.com/photos/1.0/container/"
             xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
             GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$timestamp"
             $vendor $legacy>
             <Container:Directory><rdf:Seq>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" $primaryExtent/></rdf:li>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="$mime" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li>
             </rdf:Seq></Container:Directory>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
    }

    // Preserve the complete legacy Xiaomi profile until vendor-gallery testing supports changing it.
    // In particular, do not replace its zero timestamps with the standard profile's unknown value.
    private fun xiaomiXmp(videoSize: Long): String =
        String.format(
            java.util.Locale.ROOT,
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

    private fun segment(marker: Int, data: ByteArray): ByteArray {
        require(data.size <= 65533)
        return bytes { writeByte(0xff); writeByte(marker); writeShort(data.size + 2); write(data) }
    }

    private fun mpf(imageSize: Long): ByteArray {
        require(imageSize in 0..0xffffffffL)
        return segment(0xe2, bytes {
            writeBytes("MPF\u0000MM"); writeShort(42); writeInt(8); writeShort(3)
            writeShort(0xb000); writeShort(7); writeInt(4); writeBytes("0100")
            writeShort(0xb001); writeShort(4); writeInt(1); writeInt(1)
            writeShort(0xb002); writeShort(7); writeInt(16); writeInt(50)
            writeInt(0)
            writeInt(0x00030000); writeInt(imageSize.toInt()); writeInt(0); writeInt(0)
        })
    }

    private fun samsungTag(id: Int, name: String): ByteArray = ByteBuffer.allocate(8 + name.length)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(id.toShort()).putInt(name.length)
        .put(name.toByteArray(Charsets.US_ASCII)).array()

    private fun samsungFooter(videoLength: Long, prefixSize: Int): ByteArray {
        val version = samsungTag(0x0a31, "MotionPhoto_Version") + "mpv3".toByteArray()
        val dataLength = prefixSize + videoLength
        require(dataLength + version.size <= 0xffffffffL)
        val index = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("SEFH".toByteArray()).putInt(107).putInt(2)
            .putShort(0).putShort(0x0a30).putInt((dataLength + version.size).toInt()).putInt(dataLength.toInt())
            .putShort(0).putShort(0x0a31).putInt(version.size).putInt(version.size)
            .putInt(36).put("SEFT".toByteArray()).array()
        return version + index
    }

    private fun huaweiTail(videoLength: Long, videoFrameCount: Int): ByteArray {
        require(videoLength in 1..Long.MAX_VALUE - 20 && videoFrameCount > 0)
        return ByteArray(60) { 0x20 }.apply {
            fun field(offset: Int, value: String, maxLength: Int) {
                val data = value.toByteArray(Charsets.US_ASCII)
                require(data.size <= maxLength)
                data.copyInto(this, offset)
            }
            field(0, "v6_f0", 6); field(20, "0:$videoFrameCount", 8); field(40, "LIVE_${videoLength + 20}", 15)
        }
    }

    fun vivoImageTail(id: String): ByteArray = vivoTail(id,
        "vivo{\"com.vivo.gallery.livephoto.source\":4,\"com.vivo.gallery.livePhoto.rotationOffset\":0," +
            "\"com.vivo.gallery.livePhoto.rotationCheck\":3,\"com.android.camera.livephoto\":\"$id\",\"version\":2200}")

    fun vivoVideoBox(id: String): ByteArray {
        val tail = vivoTail(id, "vivo{\"com.android.camera.livephoto\":\"$id\",\"version\":2016," +
            "\"com.vivo.gallery.livePhoto.newCoverTime\":0}")
        // The observed user type occupies the complete 16-byte UUID user-type field.
        return bytes { writeInt(24 + tail.size); writeBytes("uuidvivoMediaExtInfo"); write(tail) }
    }

    private fun vivoTail(id: String, json: String): ByteArray {
        require(id.matches(Regex("[0-9a-f]{28}")))
        val data = json.toByteArray(Charsets.UTF_8)
        return bytes {
            write(data); writeInt(data.size - 4); writeBytes("cameralbum!"); writeInt(id.length + 19)
            writeBytes(id); writeInt(-1)
            write(byteArrayOf(0x1b, 0x2a, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84.toByte(), 0x93.toByte(), 0xa2.toByte(), 0xb3.toByte()))
        }
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
}
