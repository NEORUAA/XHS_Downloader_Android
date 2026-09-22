package com.neoruaa.xhsdn.domain.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.room3.withWriteTransaction
import com.neoruaa.xhsdn.LivePhotoCreator
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.app.AppContainer
import com.neoruaa.xhsdn.core.model.*
import com.neoruaa.xhsdn.data.*
import com.neoruaa.xhsdn.data.network.*
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.storage.*
import com.neoruaa.xhsdn.data.tasks.*
import com.neoruaa.xhsdn.data.xhs.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Application-owned durable queue. UI lifetimes never own a network call. */
class DownloadQueue(private val context: Context, private val container: AppContainer) {
    private val tasks get() = container.taskRepository
    private val sessions get() = container.taskDatabase.downloadSessionDao()
    private val transfer = ResumableTransfer(File(context.filesDir, "transfers"))
    private val storage = AndroidStorageSink(context)
    private val drainLock = Mutex()
    private val controlLock = Mutex()
    private var activeJob: Job? = null
    private val _activeTask = MutableStateFlow<Long?>(null)
    val activeTask: StateFlow<Long?> = _activeTask.asStateFlow()
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    suspend fun enqueue(input: String, selection: Boolean = false, infoOnly: Boolean = false): List<Long> {
        container.initialization.await()
        container.settingsRepository.awaitReady()
        val urls = XhsUrlParser.extractLinks(input).distinct()
        if (urls.isEmpty()) throw XhsResolveException(DownloadFailure.InvalidInput)
        val snapshot = DownloadJson.encodeToString(container.settingsRepository.currentSettings)
        val ids = urls.map { url ->
            container.taskDatabase.withWriteTransaction {
                val id = tasks.createTask(url, null, NoteType.UNKNOWN, 0)
                sessions.saveSession(DownloadSessionEntity(id, snapshot, requireSelection = selection, infoOnly = infoOnly))
                id
            }
        }
        wake()
        return ids
    }

    fun wake() {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    suspend fun drain() = drainLock.withLock {
        try {
            coroutineScope {
                container.initialization.await()
                while (currentCoroutineContext().isActive) {
                    val job = controlLock.withLock pick@ {
                        val id = sessions.nextQueued() ?: return@pick null
                        _activeTask.value = id
                        launch { execute(id) }.also { activeJob = it }
                    } ?: break
                    job.join()
                    controlLock.withLock { if (activeJob === job) { activeJob = null; _activeTask.value = null } }
                }
            }
        } finally {
            withContext(NonCancellable) {
                controlLock.withLock {
                    activeJob?.cancelAndJoin()
                    activeJob = null
                    _activeTask.value = null
                }
            }
        }
    }

    suspend fun pause(id: Long) = controlLock.withLock {
        if (_activeTask.value == id) activeJob?.cancelAndJoin()
        tasks.updateTaskStatus(id, TaskStatus.PAUSED)
    }

    suspend fun pauseAll() = controlLock.withLock {
        activeJob?.cancelAndJoin()
        sessions.recoverInterrupted()
    }

    suspend fun cancel(id: Long) = controlLock.withLock {
        if (_activeTask.value == id) activeJob?.cancelAndJoin()
        tasks.updateTaskStatus(id, TaskStatus.CANCELLED)
        transfer.clear(id)
    }

    suspend fun delete(id: Long) { cancel(id); tasks.deleteTask(id) }
    suspend fun clearFinishedHistory() {
        sessions.finishedIds().forEach { transfer.clear(it) }
        tasks.clearFinishedTasks()
    }

    suspend fun resume(id: Long, refresh: Boolean = false) {
        container.initialization.await()
        container.settingsRepository.awaitReady()
        controlLock.withLock {
            if (_activeTask.value == id) activeJob?.cancelAndJoin()
            if (tasks.getTaskById(id) == null) return
            val session = sessions.session(id) ?: DownloadSessionEntity(id,
                DownloadJson.encodeToString(container.settingsRepository.currentSettings)).also { sessions.saveSession(it) }
            if (refresh) sessions.saveSession(session.copy(resolvedJson = null))
            tasks.updateTaskStatus(id, TaskStatus.QUEUED)
        }
        wake()
    }

    suspend fun select(id: Long, selected: Set<String>) {
        if (selected.isEmpty()) return
        val session = sessions.session(id) ?: return
        sessions.saveSession(session.copy(selectedJson = DownloadJson.encodeToString(selected)))
        resume(id)
    }

    suspend fun resolved(id: Long): ResolvedNote? = sessions.session(id)?.resolvedJson?.let {
        DownloadJson.decodeFromString<ResolvedNote>(it)
    }

    suspend fun settings(id: Long): AppSettings? = sessions.session(id)?.settingsJson?.let {
        DownloadJson.decodeFromString<AppSettings>(it)
    }

    suspend fun acceptResolved(id: Long?, note: ResolvedNote): Long {
        container.initialization.await()
        container.settingsRepository.awaitReady()
        val snapshot = container.settingsRepository.currentSettings
        val taskId = id ?: container.taskDatabase.withWriteTransaction {
            val created = tasks.createTask(note.canonicalUrl, note.title, NoteType.UNKNOWN, note.mediaCount)
            sessions.saveSession(DownloadSessionEntity(created, DownloadJson.encodeToString(snapshot), requireSelection = snapshot.selectiveDownload))
            created
        }
        controlLock.withLock {
            if (_activeTask.value == taskId) activeJob?.cancelAndJoin()
            val session = sessions.session(taskId) ?: DownloadSessionEntity(taskId, DownloadJson.encodeToString(snapshot))
            val expected = XhsUrlParser.extractPostId(tasks.getTaskById(taskId)?.noteUrl.orEmpty())
            require(expected == null || expected == note.noteId) { "The browser note does not match the task" }
            sessions.saveSession(session.copy(resolvedJson = DownloadJson.encodeToString(note), noteId = note.noteId, authorName = note.authorName.orEmpty()))
            tasks.updateTaskStatus(taskId, TaskStatus.QUEUED)
        }
        wake()
        return taskId
    }

    private suspend fun execute(id: Long) {
        try {
            var session = sessions.session(id) ?: run { tasks.updateTaskStatus(id, TaskStatus.PAUSED); return }
            val settings = DownloadJson.decodeFromString<AppSettings>(session.settingsJson)
            val options = settings.downloadOptions
            val task = tasks.getTaskById(id) ?: return
            tasks.updateTaskStatus(id, TaskStatus.RESOLVING)
            debug(context.getString(R.string.download_resolving))
            val note = session.resolvedJson?.let { DownloadJson.decodeFromString<ResolvedNote>(it) } ?: run {
                val source = OkHttpXhsPageSource(container.network.client(options, media = false))
                DefaultXhsContentRepository(source::fetchHtml, source::resolveShortUrl).resolve(task.noteUrl).also {
                    session = session.copy(resolvedJson = DownloadJson.encodeToString(it), noteId = it.noteId, authorName = it.authorName.orEmpty())
                    sessions.saveSession(session)
                }
            }
            note.authorId?.takeIf(String::isNotBlank)?.let { authorId ->
                val author = sessions.author(authorId)
                sessions.saveAuthor(NoteAuthorEntity(authorId, note.authorName.orEmpty(), author?.remark.orEmpty()))
            }
            var media = NoteOutput.eligible(note, options)
            tasks.updateTask(id) { it.copy(noteTitle = note.title, noteContent = note.description,
                noteType = if (note.type == "video") NoteType.VIDEO else NoteType.IMAGE, totalFiles = media.size) }
            if (session.requireSelection && session.selectedJson == null && !session.infoOnly) {
                tasks.updateTaskStatus(id, TaskStatus.WAITING_FOR_USER)
                return
            }
            session.selectedJson?.let { json ->
                val selected = DownloadJson.decodeFromString<Set<String>>(json)
                media = media.filter { it.id in selected }
            }
            if (session.infoOnly) media = emptyList()
            val formats = when (if (session.infoOnly && options.noteFormat == NoteFormat.NONE) NoteFormat.TXT else options.noteFormat) {
                NoteFormat.NONE -> emptyList()
                NoteFormat.TXT -> listOf(false)
                NoteFormat.MARKDOWN -> listOf(true)
                NoteFormat.BOTH -> listOf(false, true)
            }
            val total = media.size + formats.size
            if (total == 0) throw XhsResolveException(DownloadFailure.NoMedia)
            tasks.updateTask(id) { it.copy(totalFiles = total, status = TaskStatus.DOWNLOADING, failedFiles = 0, errorMessage = null) }
            val destination = resolveStorageDestination(settings.customStorageTreeUri, settings.checkExistingFilesBeforeSave)
            val remark = note.authorId?.let { sessions.author(it)?.remark }.orEmpty()
            val folders = NoteOutput.folders(note, options, remark)
            val client = container.network.client(options, media = true)
            val progressLock = Mutex()
            val fractions = java.util.concurrent.ConcurrentHashMap<String, Float>()
            var complete = 0
            var failed = 0
            var skipped = 0
            val warnings = mutableSetOf<String>()
            var lastPersist = 0L
            suspend fun publish(terminal: Boolean = false) = progressLock.withLock {
                val fraction = fractions.values.sum().coerceAtMost((total - complete - failed).coerceAtLeast(0).toFloat())
                _progress.update { it + (id to ((complete + fraction) / total).coerceIn(0f, 1f)) }
                val now = android.os.SystemClock.elapsedRealtime()
                if (terminal || now - lastPersist > 1000) {
                    lastPersist = now
                    tasks.updateTask(id) { it.copy(completedFiles = complete, failedFiles = failed, currentFileProgress = fraction) }
                }
            }
            suspend fun runResource(mediaId: String, action: suspend () -> Pair<List<StoredMediaRef>, List<String>>) {
                val key = NoteOutput.recordKey(note.noteId, mediaId, settings, folders)
                val existing = sessions.resources(id).firstOrNull { it.mediaId == mediaId && it.state in setOf("COMPLETED", "SKIPPED") }
                val previous = existing ?: if (options.skipExisting) sessions.completedResources(key).firstOrNull { refsExist(it.refsJson) } else null
                try {
                    val (refs, notes) = if (previous != null && refsExist(previous.refsJson)) {
                        DownloadJson.decodeFromString<List<StoredMediaRef>>(previous.refsJson) to listOfNotNull(previous.warning)
                    } else action()
                    currentCoroutineContext().ensureActive()
                    val didSkip = previous != null && existing == null
                    sessions.saveResource(DownloadResourceEntity(id, mediaId, key, if (didSkip) "SKIPPED" else "COMPLETED", DownloadJson.encodeToString(refs), warning = notes.joinToString("\n").ifBlank { null }))
                    refs.forEach { tasks.addMediaRef(id, it) }
                    progressLock.withLock { complete++; if (didSkip) skipped++; warnings.addAll(notes) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("DownloadQueue", "Resource failed: ${error.javaClass.simpleName}; ${(error as? TransferException)?.reason}; ${error.stackTrace.firstOrNull()}")
                    val message = errorMessage(error)
                    sessions.saveResource(DownloadResourceEntity(id, mediaId, key, "FAILED", warning = message))
                    progressLock.withLock { failed++; warnings.add(message) }
                } finally {
                    fractions.remove(mediaId)
                }
                publish(true)
            }
            val semaphore = Semaphore(4)
            coroutineScope {
                val ticker = launch { while (isActive) { delay(250); publish() } }
                val workers = media.mapIndexed { index, item -> launch {
                    semaphore.withPermit {
                        runResource(item.id) {
                            val refs = mutableListOf<StoredMediaRef>()
                            val notes = mutableListOf<String>()
                            suspend fun fetch(partId: String, urls: List<String>): TransferredMedia = transfer.fetch(client, id, partId, urls, options.maxRetries) { bytes, length ->
                                fractions[item.id] = if (length > 0) (bytes.toFloat() / length).coerceIn(0f, 0.98f) else 0f
                            }
                            suspend fun save(file: File, type: MediaFileType, suffix: String) {
                                currentCoroutineContext().ensureActive()
                                val pieceId = "${item.id}:output:$suffix"
                                val piece = sessions.resources(id).firstOrNull { it.mediaId == pieceId && it.state == "COMPLETED" && refsExist(it.refsJson) }
                                val ref = if (piece != null) DownloadJson.decodeFromString<List<StoredMediaRef>>(piece.refsJson).single() else {
                                    val filename = NoteOutput.fileName(note, settings, index, type.extension, task.createdAt)
                                    val name = if (suffix == "main") filename else filename.substringBeforeLast('.') + "_$suffix.${type.extension}"
                                    val saveContext = currentCoroutineContext()
                                    val stored = storage.storeArchived(destination, name, type.mimeType, file.length(), folders) { output ->
                                        file.inputStream().use { input ->
                                            val buffer = ByteArray(256 * 1024)
                                            while (true) {
                                                if (!saveContext.isActive) throw CancellationException("Save cancelled")
                                                val count = input.read(buffer); if (count < 0) break
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                    withContext(NonCancellable) {
                                        sessions.saveResource(DownloadResourceEntity(id, pieceId, "", "COMPLETED", DownloadJson.encodeToString(listOf(stored))))
                                        tasks.addMediaRef(id, stored)
                                    }
                                    stored
                                }
                                refs.add(ref)
                                if (options.writePublishTime && note.publishedAt != null && !storage.writeTimestamp(ref, note.publishedAt)) notes.add(context.getString(R.string.download_warning_time))
                            }
                            suspend fun image(image: ResolvedMedia.Image): TransferredMedia {
                                val result = fetch(image.id, NoteOutput.imageUrls(image, options.imageFormat))
                                val expected = when (options.imageFormat) { ImageFormat.AUTO -> null; ImageFormat.JPEG -> "jpg"; else -> options.imageFormat.name.lowercase() }
                                if (expected != null && result.type.extension != expected) notes.add(context.getString(R.string.download_warning_format))
                                return result
                            }
                            when (item) {
                                is ResolvedMedia.Image -> image(item).let { save(it.file, it.type, "main") }
                                is ResolvedMedia.Video -> fetch(item.id, NoteOutput.videoUrls(item, options.videoPreference)).let { save(it.file, it.type, "main") }
                                is ResolvedMedia.LivePhoto -> {
                                    var componentError: Exception? = null
                                    suspend fun part(block: suspend () -> TransferredMedia): TransferredMedia? = try { block() }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) { componentError = error; null }
                                    val still = if (options.imageDownload) part { image(item.image) } else null
                                    val motion = if (options.videoDownload && options.livePhotoMode != LivePhotoMode.STILL) part { fetch(item.video.id, NoteOutput.videoUrls(item.video, options.videoPreference)) } else null
                                    val output = File(context.cacheDir, "merged_${id}_${index}.jpg")
                                    try {
                                        if (still != null && motion != null && options.livePhotoMode == LivePhotoMode.MERGED && LivePhotoCreator.createLivePhoto(still.file, motion.file, output, null)) {
                                            save(output, MediaFileType("jpg", "image/jpeg"), "main")
                                        } else {
                                            if (still != null && motion != null && options.livePhotoMode == LivePhotoMode.MERGED) notes.add(context.getString(R.string.download_warning_live))
                                            still?.let { save(it.file, it.type, "main") }
                                            motion?.let { save(it.file, it.type, "motion") }
                                        }
                                    } finally { output.delete() }
                                    componentError?.let { throw it }
                                }
                            }
                            refs to notes
                        }
                    }
                } }
                try { workers.joinAll() } finally { ticker.cancelAndJoin() }
            }
            for (markdown in formats) runResource(if (markdown) "note:md" else "note:txt") {
                val extension = if (markdown) "md" else "txt"
                val bytes = NoteOutput.text(note, markdown).toByteArray()
                val ref = storage.storeArchived(destination, NoteOutput.fileName(note, settings, 0, extension, task.createdAt), "text/plain", bytes.size.toLong(), folders) { it.write(bytes) }
                listOf(ref) to emptyList()
            }
            val hasSavedFiles = tasks.getTaskById(id)?.mediaRefs?.isNotEmpty() == true
            val status = when { failed > 0 && (complete > 0 || hasSavedFiles) -> TaskStatus.PARTIAL; failed > 0 -> TaskStatus.FAILED; skipped == total -> TaskStatus.SKIPPED; else -> TaskStatus.COMPLETED }
            tasks.updateTask(id) { it.copy(status = status, completedFiles = complete, failedFiles = failed, currentFileProgress = 0f,
                completedAt = System.currentTimeMillis(), errorMessage = warnings.joinToString("\n").ifBlank { null }) }
            debug(context.getString(R.string.download_result_summary, complete, total, failed))
            if (failed == 0) transfer.clear(id)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { tasks.updateTaskStatus(id, TaskStatus.PAUSED) }
            throw cancelled
        } catch (error: Exception) {
            tasks.updateTaskStatus(id, TaskStatus.FAILED, errorMessage(error))
            debug(errorMessage(error))
        } finally { _progress.update { it - id } }
    }

    private fun debug(message: String) {
        // Notification permission must never change the outcome of a download.
        runCatching { com.neoruaa.xhsdn.utils.NotificationHelper.showOrUpdateDebugNotification(
            context, context.getString(R.string.download_queue_title), message, important = false,
        ) }
    }

    private fun refsExist(json: String): Boolean = runCatching {
        val refs = DownloadJson.decodeFromString<List<StoredMediaRef>>(json)
        refs.isNotEmpty() && refs.all { ref -> storage.open(ref)?.use { it.read() >= 0 } == true }
    }.getOrDefault(false)

    fun errorMessage(error: Throwable): String = context.getString(when (error) {
        is XhsResolveException -> when (error.failure) {
            DownloadFailure.InvalidInput -> R.string.download_error_invalid
            DownloadFailure.NoMedia -> R.string.download_error_empty
            is DownloadFailure.Network -> R.string.download_error_network
            else -> R.string.download_error_web
        }
        is StorageAccessException, is SecurityException -> R.string.download_error_storage
        is TransferException -> when (error.reason) {
            TransferException.Reason.ACCESS -> R.string.download_error_access
            TransferException.Reason.NON_MEDIA -> R.string.download_error_media
            TransferException.Reason.NO_SPACE -> R.string.download_error_storage
            else -> R.string.download_error_network
        }
        is IOException -> R.string.download_error_storage
        else -> R.string.download_error_network
    })
}
