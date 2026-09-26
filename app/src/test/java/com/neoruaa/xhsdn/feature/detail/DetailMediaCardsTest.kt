package com.neoruaa.xhsdn.feature.detail

import com.neoruaa.xhsdn.core.model.*
import com.neoruaa.xhsdn.data.*
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import com.neoruaa.xhsdn.data.tasks.*
import com.neoruaa.xhsdn.domain.download.MediaTransferProgress
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DetailMediaCardsTest {
    private val image = ResolvedMedia.Image("https://cdn.example/image", id = "image", width = 800, height = 1200)
    private val video = ResolvedMedia.Video("https://cdn.example/video", id = "video")
    private val task = DownloadTask(1, "https://www.xiaohongshu.com/explore/n", "Note", NoteType.IMAGE, 2,
        status = TaskStatus.DOWNLOADING, createdAt = 1)
    private fun session(items: List<ResolvedMedia>, options: DownloadOptions = DownloadOptions(), selected: Set<String>? = null) =
        DownloadSessionEntity(1, DownloadJson.encodeToString(AppSettings(downloadOptions = options)),
            resolvedJson = DownloadJson.encodeToString(ResolvedNote("", null, null, null, null, null, items = items)),
            selectedJson = selected?.let { DownloadJson.encodeToString(it) })

    @Test fun pendingCardsRespectSelectionAndKeepIndependentCheckpoints() {
        val cards = buildDetailMediaCards(task, session(listOf(image, video)), listOf(
            DownloadResourceEntity(1, "image:transfer", "", "TRANSFERRING", bytesDownloaded = 25, totalBytes = 100),
            DownloadResourceEntity(1, "video:transfer", "", "TRANSFERRING", bytesDownloaded = 50, totalBytes = 1000)))
        assertEquals(2, cards.size)
        assertEquals(0.25f, cards[0].checkpoint.getValue("image").fraction!!, 0.001f)
        assertEquals(0.05f, cards[1].checkpoint.getValue("video").fraction!!, 0.001f)
        assertEquals(listOf("video:main"), buildDetailMediaCards(task, session(listOf(image, video), selected = setOf("video")), emptyList()).map { it.key })
    }

    @Test fun savedOutputsReplaceTheirPendingSlotAndDeletedFilesStayDeleted() {
        val ref = StoredMediaRef("content://test/image", "image.jpg", "image/jpeg")
        val records = listOf(DownloadResourceEntity(1, "image", "", "COMPLETED", DownloadJson.encodeToString(listOf(ref))),
            DownloadResourceEntity(1, "image:output:main", "", "COMPLETED", DownloadJson.encodeToString(listOf(ref))))
        val before = buildDetailMediaCards(task, session(listOf(image)), emptyList()).single()
        val after = buildDetailMediaCards(task.copy(mediaRefs = listOf(ref)), session(listOf(image)), records).single()
        assertEquals(before.key, after.key)
        assertNotNull(after.stored)
        assertTrue(buildDetailMediaCards(task, session(listOf(image)), records).isEmpty())
    }

    @Test fun retryingAMissingOutputShowsItsDownloadCardAgain() {
        val deleted = StoredMediaRef("content://test/deleted", "deleted.jpg", "image/jpeg")
        val records = listOf(DownloadResourceEntity(1, "image", "", "DOWNLOADING"),
            DownloadResourceEntity(1, "image:output:main", "", "COMPLETED", DownloadJson.encodeToString(listOf(deleted))))
        val card = buildDetailMediaCards(task, session(listOf(image)), records).single()
        assertNull(card.stored)
        assertEquals(listOf("image"), card.transferIds)
    }

    @Test fun livePhotoComponentsHaveSeparateProgressWhenSavedSeparately() {
        val live = ResolvedMedia.LivePhoto(image, video)
        val separate = buildDetailMediaCards(task, session(listOf(live), DownloadOptions(livePhotoMode = LivePhotoMode.SEPARATE)), emptyList())
        assertEquals(listOf(listOf("image"), listOf("video")), separate.map { it.transferIds })
        val merged = buildDetailMediaCards(task, session(listOf(live)), emptyList()).single()
        assertTrue(merged.live)
        assertEquals(listOf("image", "video"), merged.transferIds)
    }

    @Test fun partialLiveFallbackKeepsSavedStillAndFailedMotionWithoutDuplicatingFiles() {
        val ref = StoredMediaRef("content://test/still", "still.jpg", "image/jpeg")
        val records = listOf(DownloadResourceEntity(1, "image", "", "FAILED"),
            DownloadResourceEntity(1, "image:output:main", "", "COMPLETED", DownloadJson.encodeToString(listOf(ref))))
        val cards = buildDetailMediaCards(task.copy(status = TaskStatus.PARTIAL, mediaRefs = listOf(ref)), session(listOf(ResolvedMedia.LivePhoto(image, video))), records)
        assertEquals(2, cards.size)
        assertNotNull(cards[0].stored)
        assertNull(cards[1].stored)
        assertTrue(cards[1].resourceFailed)
    }

    @Test fun pendingVideoUsesTheSelectedSourceDimensions() {
        val source = video.copy(candidates = listOf(MediaCandidate(video.sourceUrl, width = 1920, height = 1080)))
        val card = buildDetailMediaCards(task, session(listOf(source)), emptyList()).single()
        assertEquals(1920, card.width)
        assertEquals(1080, card.height)
    }

    @Test fun unknownComponentLengthDoesNotPretendToBeComplete() {
        val partial = MediaTransferProgress.combine(listOf(MediaTransferProgress(100, 100, complete = true), MediaTransferProgress()))
        assertEquals(100, partial.downloaded)
        assertNull(partial.fraction)
        assertFalse(partial.complete)
        val known = MediaTransferProgress.combine(listOf(MediaTransferProgress(100, 100, complete = true), MediaTransferProgress(50, 100)))
        assertEquals(0.75f, known.fraction!!, 0.001f)
    }
}
