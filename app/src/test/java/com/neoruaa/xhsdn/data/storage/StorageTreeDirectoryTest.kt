package com.neoruaa.xhsdn.data.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageTreeDirectoryTest {
    private val primaryRoot = File("/mnt/primary")
    private val homeRoot = File("/mnt/primary/Documents")

    @Test
    fun resolvesPrimaryHomeAndSecondaryTrees() {
        assertEquals(
            File("/mnt/primary/Pictures/xhsdn"),
            resolve("primary:Pictures/xhsdn"),
        )
        assertEquals(
            File("/mnt/primary/Documents/Downloads"),
            resolve("home:Downloads"),
        )
        assertEquals(
            File("/mnt/1234-5678/DCIM/xhsdn"),
            resolve("1234-5678:DCIM/xhsdn"),
        )
    }

    @Test
    fun rejectsInvalidOrEscapingDocumentIds() {
        assertNull(resolve("invalid"))
        assertNull(resolve("primary:../outside"))
        assertNull(resolve("raw:/data/local/tmp"))
    }

    private fun resolve(documentId: String): File? = resolveStorageTreeDirectory(
        documentId = documentId,
        primaryRoot = primaryRoot,
        homeRoot = homeRoot,
        secondaryRoot = { File("/mnt/$it") },
    )
}
