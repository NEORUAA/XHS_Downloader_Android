package com.neoruaa.xhsdn.domain.download

import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.storage.StorageDestination
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import kotlinx.coroutines.flow.Flow

data class DownloadRequest(
    val taskId: Long,
    val rawInput: String,
    val selectedUrls: Set<String> = emptySet(),
    val requireSelection: Boolean = false,
    /** Immutable destination snapshot for this download session. */
    val storageDestination: StorageDestination = StorageDestination.DefaultMediaStore,
)

interface DownloadCoordinator {
    fun download(request: DownloadRequest): Flow<DownloadEvent>
}

sealed interface DownloadFailure {
    data object InvalidInput : DownloadFailure
    data object NoMedia : DownloadFailure
    data object RequiresWebView : DownloadFailure
    data object Cancelled : DownloadFailure
    data class Network(val cause: Throwable? = null) : DownloadFailure
    data class Parsing(val cause: Throwable? = null) : DownloadFailure
    data class Storage(val cause: Throwable? = null) : DownloadFailure
    data class Exhausted(val attempts: Int) : DownloadFailure
}

sealed interface DownloadEvent {
    data object Resolving : DownloadEvent
    data class Resolved(val note: ResolvedNote) : DownloadEvent
    data class WaitingForSelection(val note: ResolvedNote) : DownloadEvent
    data class Progress(
        val completedFiles: Int,
        val totalFiles: Int,
        val currentFileFraction: Float
    ) : DownloadEvent
    data class Saved(val ref: StoredMediaRef) : DownloadEvent {
        /** Source compatibility for old callers that only persisted a path string. */
        constructor(path: String) : this(StoredMediaRef.fromLegacyPath(path))

        val path: String
            get() = ref.path
    }
    data object Completed : DownloadEvent
    data object Cancelled : DownloadEvent
    data class Failed(val failure: DownloadFailure) : DownloadEvent
}
