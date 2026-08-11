package com.neoruaa.xhsdn.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageDestinationTest {
    @Test
    fun snapshotsReplacementAndCoexistPolicies() {
        assertEquals(
            StorageDestination.CustomTree(
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3APictures",
                existingFilePolicy = ExistingFilePolicy.REPLACE,
            ),
            resolveStorageDestination(
                customTreeUri = " content://com.android.externalstorage.documents/tree/primary%3APictures ",
                checkExistingFilesBeforeSave = true,
            ),
        )
        assertEquals(
            ExistingFilePolicy.COEXIST,
            (resolveStorageDestination(
                customTreeUri = "content://com.android.externalstorage.documents/tree/primary%3APictures",
                checkExistingFilesBeforeSave = false,
            ) as StorageDestination.CustomTree).existingFilePolicy,
        )
        assertEquals(
            StorageDestination.DefaultMediaStore,
            resolveStorageDestination(customTreeUri = " ", checkExistingFilesBeforeSave = true),
        )
    }
}
