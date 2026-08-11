package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.storage.ExistingFilePolicy
import com.neoruaa.xhsdn.data.xhs.XhsContentRepository
import com.neoruaa.xhsdn.data.storage.StorageDestination
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryDownloadCoordinatorTest {
    private val note = ResolvedNote(
        canonicalUrl = "https://www.xiaohongshu.com/explore/note",
        title = "Title",
        description = "Description",
        authorName = null,
        authorId = null,
        publishTime = null,
        images = listOf(ResolvedMedia.Image("https://cdn/image.jpg")),
        videos = listOf(ResolvedMedia.Video("https://cdn/video.mp4")),
        livePhotos = emptyList()
    )

    @Test
    fun emitsWaitingForSelectionWithoutSaving() = runTest {
        var saveCount = 0
        val coordinator = coordinator { _, _, _ ->
            saveCount++
            "unused"
        }

        val events = coordinator.download(
            DownloadRequest(taskId = 7L, rawInput = note.canonicalUrl, requireSelection = true)
        ).toList()

        assertEquals(0, saveCount)
        assertEquals(
            listOf(
                DownloadEvent.Resolving,
                DownloadEvent.Resolved(note),
                DownloadEvent.WaitingForSelection(note)
            ),
            events
        )
    }

    @Test
    fun savesOnlySelectedMediaAndKeepsTaskId() = runTest {
        val saved = mutableListOf<Triple<Long, Int, ResolvedMedia>>()
        val coordinator = coordinator { taskId, index, media ->
            saved += Triple(taskId, index, media)
            "/saved/$index"
        }

        val events = coordinator.download(
            DownloadRequest(
                taskId = 42L,
                rawInput = note.canonicalUrl,
                selectedUrls = setOf(note.videos.single().sourceUrl)
            )
        ).toList()

        assertEquals(listOf(Triple(42L, 0, note.videos.single())), saved)
        assertTrue(events.contains(DownloadEvent.Saved("/saved/0")))
        assertEquals(DownloadEvent.Completed, events.last())
    }

    @Test
    fun mapsRepositoryFailureToTypedEvent() = runTest {
        val repository = object : XhsContentRepository {
            override suspend fun resolve(rawInput: String): ResolvedNote {
                throw com.neoruaa.xhsdn.data.xhs.XhsResolveException(DownloadFailure.RequiresWebView)
            }
        }
        val coordinator = RepositoryDownloadCoordinator(repository) { _, _, _ -> "unused" }

        val events = coordinator.download(DownloadRequest(1L, "invalid")).toList()

        assertEquals(DownloadEvent.Resolving, events.first())
        assertEquals(DownloadEvent.Failed(DownloadFailure.RequiresWebView), events.last())
    }

    @Test
    fun passesTheRequestDestinationToTheStorageSink() = runTest {
        val expectedDestination = StorageDestination.CustomTree(
            treeUri = "content://local/tree/folder",
            existingFilePolicy = ExistingFilePolicy.REPLACE,
        )
        var actualDestination: StorageDestination? = null
        val expectedRef = StoredMediaRef(
            uri = "content://local/document/image.jpg",
            displayName = "image.jpg",
            mimeType = "image/jpeg",
        )
        val sink = object : ResolvedMediaSink {
            override suspend fun save(taskId: Long, mediaIndex: Int, media: ResolvedMedia): String =
                error("The typed storage path should be used")

            override suspend fun saveStored(
                taskId: Long,
                mediaIndex: Int,
                media: ResolvedMedia,
                destination: StorageDestination,
            ): StoredMediaRef {
                actualDestination = destination
                return expectedRef
            }
        }

        val events = coordinator(sink).download(
            DownloadRequest(
                taskId = 9L,
                rawInput = note.canonicalUrl,
                selectedUrls = setOf(note.images.single().sourceUrl),
                storageDestination = expectedDestination,
            )
        ).toList()

        assertEquals(expectedDestination, actualDestination)
        assertTrue(events.contains(DownloadEvent.Saved(expectedRef)))
    }

    private fun coordinator(sink: ResolvedMediaSink): RepositoryDownloadCoordinator =
        RepositoryDownloadCoordinator(
            contentRepository = object : XhsContentRepository {
                override suspend fun resolve(rawInput: String): ResolvedNote = note
            },
            mediaSink = sink
        )
}
