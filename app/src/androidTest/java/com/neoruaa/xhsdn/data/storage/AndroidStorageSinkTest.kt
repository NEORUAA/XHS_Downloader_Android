package com.neoruaa.xhsdn.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.neoruaa.xhsdn.utils.decodeSampledBitmap
import com.neoruaa.xhsdn.utils.deleteStoredMedia
import com.neoruaa.xhsdn.utils.storedMediaExists
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AndroidStorageSinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
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

}
