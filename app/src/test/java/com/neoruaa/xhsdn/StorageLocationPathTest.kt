package com.neoruaa.xhsdn

import com.neoruaa.xhsdn.data.settings.formatStorageDocumentPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageLocationPathTest {
    @Test
    fun formatsPrimaryStorageTreeAsFullPath() {
        assertEquals(
            "/storage/emulated/0/Pictures/xhsdn",
            formatStorageDocumentPath("primary:Pictures/xhsdn"),
        )
    }

    @Test
    fun formatsSecondaryStorageTreeAsFullPath() {
        assertEquals(
            "/storage/1234-5678/DCIM/xhsdn",
            formatStorageDocumentPath("1234-5678:DCIM/xhsdn"),
        )
    }

    @Test
    fun formatsDocumentsAndRawRoots() {
        assertEquals(
            "/storage/emulated/0/Documents/Downloads",
            formatStorageDocumentPath("home:Downloads"),
        )
        assertEquals(
            "/storage/emulated/0/Pictures/xhsdn",
            formatStorageDocumentPath("raw:/storage/emulated/0/Pictures/xhsdn"),
        )
        assertNull(formatStorageDocumentPath("invalid"))
    }
}
