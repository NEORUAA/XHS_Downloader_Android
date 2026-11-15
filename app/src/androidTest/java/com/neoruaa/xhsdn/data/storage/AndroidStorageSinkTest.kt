package com.neoruaa.xhsdn.data.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import com.neoruaa.xhsdn.utils.deleteStoredMedia
import com.neoruaa.xhsdn.utils.storedMediaExists
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidStorageSinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun mediaStoreResultCanBeReadPreviewedAndDeletedByUri() {
        val sink = AndroidStorageSink(context)
        var stored: StoredMediaRef? = null
        try {
            val ref = sink.store(
                destination = StorageDestination.DefaultMediaStore,
                displayName = "storage_sink_test_${System.nanoTime()}.png",
                mimeType = "image/png",
                writer = StorageStreamWriter { output ->
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.RED)
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    } finally {
                        bitmap.recycle()
                    }
                },
            )
            stored = ref

            assertTrue(ref.uri.startsWith("content://media/"))
            assertTrue(context.storedMediaExists(ref))
            assertNotNull(context.decodeSampledBitmap(ref, 32, 32))
            assertTrue(context.deleteStoredMedia(ref))
            assertFalse(context.storedMediaExists(ref))
            stored = null
        } finally {
            stored?.let(context::deleteStoredMedia)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    fun legacyResultUsesPublicPathAndCanBeReadPreviewedAndDeleted() {
        assertEquals("Public storage validation requires WRITE_EXTERNAL_STORAGE",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertEquals("Public storage validation requires READ_EXTERNAL_STORAGE",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        val sink = AndroidStorageSink(context)
        var stored: StoredMediaRef? = null
        try {
            val ref = sink.store(
                destination = StorageDestination.DefaultMediaStore,
                displayName = "legacy_storage_sink_test_${System.nanoTime()}.png",
                mimeType = "image/png",
                sizeBytes = bytes.size.toLong(),
                writer = StorageStreamWriter { it.write(bytes) },
            )
            stored = ref
            val file = File(checkNotNull(ref.legacyPath))
            val publicDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "xhsdn")
            assertEquals(publicDirectory.canonicalFile, file.canonicalFile.parentFile)
            assertEquals(bytes.size.toLong(), ref.sizeBytes)
            assertTrue(context.storedMediaExists(ref))
            sink.open(ref)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            val preview = context.decodeSampledBitmap(ref, 32, 32)
            assertNotNull(preview)
            preview?.recycle()
            assertTrue(context.deleteStoredMedia(ref))
            assertFalse(context.storedMediaExists(ref))
            assertFalse(file.exists())
            stored = null
        } finally {
            stored?.let(context::deleteStoredMedia)
        }
    }

}
