package com.neoruaa.xhsdn.data.tasks

import android.content.Context
import android.util.Log
import com.neoruaa.xhsdn.data.DownloadTask
import com.neoruaa.xhsdn.data.NoteType
import com.neoruaa.xhsdn.data.TaskStatus
import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Compatibility facade for existing screens and callbacks.
 *
 * New code should depend on [TaskRepository]. The facade keeps the old synchronous API
 * while writing through the Room repository asynchronously, so download callbacks do not
 * block the main thread on database I/O.
 */
object TaskManager {
    private const val TAG = "TaskManager"
    private const val LEGACY_PREFS_NAME = "task_history"
    private const val KEY_NEXT_ID = "next_id"

    private val lock = Any()
    private val tasksState = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val dirtyTasks = LinkedHashMap<Long, DownloadTask>()
    private var repository: TaskRepository? = null
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistenceScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private var initialization: Deferred<Unit>? = null
    private var observeJob: Job? = null
    private var nextId: Long = 1L

    /** Initializes the facade from the application's dependency container. */
    fun init(context: Context) {
        val appContext = context.applicationContext
        val container = (appContext as? com.neoruaa.xhsdn.XHSApplication)
            ?.appContainer
            ?: com.neoruaa.xhsdn.app.AppContainer(appContext).also { it.startInitialization() }
        attach(appContext, container.taskRepository, container.scope, container.initialization)
    }

    internal fun attach(
        context: Context,
        taskRepository: TaskRepository,
        taskScope: CoroutineScope,
        initialization: Deferred<Unit>? = null
    ) {
        synchronized(lock) {
            if (repository === taskRepository && observeJob?.isActive == true) return
            repository = taskRepository
            scope = taskScope
            this.initialization = initialization
            persistenceScope = CoroutineScope(
                taskScope.coroutineContext + Dispatchers.IO.limitedParallelism(1)
            )
            nextId = max(nextId, readLegacyNextId(context))
            observeJob?.cancel()
            observeJob = scope.launch {
                try {
                    taskRepository.observeTasks().collect { persisted ->
                        synchronized(lock) {
                            val persistedById = persisted.associateBy { it.id }
                            val completedDirtyIds = dirtyTasks
                                .filter { (id, task) -> persistedById[id] == task }
                                .keys
                            completedDirtyIds.forEach(dirtyTasks::remove)
                            val merged = (dirtyTasks.values + persisted)
                                .distinctBy { it.id }
                                .sortedWith(compareByDescending<DownloadTask> { it.createdAt }.thenByDescending { it.id })
                            tasksState.value = merged
                            val maxId = merged.maxOfOrNull { it.id } ?: 0L
                            nextId = max(nextId, maxId + 1L)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Task observation failed", error)
                }
            }
        }
    }

    fun getAllTasks(): Flow<List<DownloadTask>> = tasksState.asStateFlow()

    fun getTaskById(taskId: Long): DownloadTask? = synchronized(lock) {
        tasksState.value.firstOrNull { it.id == taskId }
    }

    fun getActiveTasks(): Flow<List<DownloadTask>> = tasksState.map { tasks ->
        tasks.filter(DownloadTask::isActive)
    }

    fun getCompletedTasks(): Flow<List<DownloadTask>> = tasksState.map { tasks ->
        tasks.filter(DownloadTask::isCompleted)
    }

    fun createTask(
        noteUrl: String,
        noteTitle: String?,
        noteType: NoteType,
        totalFiles: Int,
        noteContent: String? = null
    ): Long {
        val task = synchronized(lock) {
            val taskId = nextId++
            DownloadTask(
                id = taskId,
                noteUrl = noteUrl,
                noteTitle = noteTitle,
                noteType = noteType,
                totalFiles = totalFiles,
                status = TaskStatus.QUEUED,
                createdAt = System.currentTimeMillis(),
                noteContent = noteContent
            ).also { publishDirty(it) }
        }
        persist { it.insertTask(task) }
        return task.id
    }

    fun startTask(taskId: Long) = updateTask(taskId) { it.copy(status = TaskStatus.DOWNLOADING) }

    fun updateProgress(
        taskId: Long,
        completedFiles: Int,
        failedFiles: Int,
        currentFileProgress: Float = 0f
    ) {
        val current = synchronized(lock) { tasksState.value.firstOrNull { it.id == taskId } } ?: return
        val completed = completedFiles.coerceAtLeast(0)
        val failed = failedFiles.coerceAtLeast(0)
        val fileProgress = currentFileProgress.coerceIn(0f, 1f)
        val newProgress = if (current.totalFiles > 0) {
            (completed + fileProgress) / current.totalFiles.toFloat()
        } else {
            0f
        }
        if (newProgress < current.progress) return
        val finished = completed + failed
        val status = when {
            finished >= current.totalFiles && failed > 0 -> TaskStatus.FAILED
            finished >= current.totalFiles -> TaskStatus.COMPLETED
            else -> TaskStatus.DOWNLOADING
        }
        val updated = current.copy(
            completedFiles = completed,
            failedFiles = failed,
            currentFileProgress = fileProgress,
            status = status,
            completedAt = if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
                current.completedAt ?: System.currentTimeMillis()
            } else {
                null
            },
            errorMessage = if (failed > 0) current.errorMessage else null
        )
        synchronized(lock) { publishDirty(updated) }
        persist { it.updateProgress(taskId, completed, failed, fileProgress) }
    }

    fun addMediaRef(taskId: Long, media: StoredMediaRef) {
        updateTask(taskId) { task ->
            if (task.mediaRefs.any { it.path == media.path }) task
            else task.copy(mediaRefs = task.mediaRefs + media)
        }
    }

    fun removeMediaRef(taskId: Long, location: String) {
        updateTask(taskId) { task ->
            task.copy(mediaRefs = task.mediaRefs.filterNot { it.path == location })
        }
    }

    fun completeTask(taskId: Long, success: Boolean, errorMessage: String? = null) {
        updateTask(taskId) {
            it.copy(
                status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        }
    }

    fun deleteTask(taskId: Long) {
        synchronized(lock) {
            dirtyTasks.remove(taskId)
            tasksState.value = tasksState.value.filterNot { it.id == taskId }
        }
        persist { it.deleteTask(taskId) }
    }

    fun clearAllTasks() {
        synchronized(lock) {
            dirtyTasks.clear()
            tasksState.value = emptyList()
        }
        persist { it.clearAllTasks() }
    }

    fun getCurrentActiveTask(): DownloadTask? = synchronized(lock) {
        tasksState.value.firstOrNull { it.status == TaskStatus.DOWNLOADING }
    }

    fun hasRecentTask(url: String, durationMillis: Long = 3600_000): Boolean {
        val threshold = System.currentTimeMillis() - durationMillis
        return synchronized(lock) {
            tasksState.value.any { task ->
                task.noteUrl == url && (task.isActive || task.createdAt > threshold)
            }
        }
    }

    fun updateTaskType(taskId: Long, noteType: NoteType) = updateTask(taskId) { it.copy(noteType = noteType) }

    fun resetTask(taskId: Long) = updateTask(taskId) {
        it.copy(
            status = TaskStatus.DOWNLOADING,
            completedFiles = 0,
            failedFiles = 0,
            currentFileProgress = 0f,
            mediaRefs = emptyList(),
            errorMessage = null,
            completedAt = null
        )
    }

    fun updateTaskStatus(taskId: Long, status: TaskStatus, errorMessage: String? = null) =
        updateTask(taskId) { it.copy(status = status, errorMessage = errorMessage) }

    fun updateTask(taskId: Long, update: (DownloadTask) -> DownloadTask) {
        val current = synchronized(lock) { tasksState.value.firstOrNull { it.id == taskId } } ?: return
        val updated = update(current)
        if (updated.id != taskId) return
        synchronized(lock) { publishDirty(updated) }
        persist { it.updateTask(taskId, update) }
    }

    private fun publishDirty(task: DownloadTask) {
        dirtyTasks[task.id] = task
        tasksState.value = (tasksState.value.filterNot { it.id == task.id } + task)
            .sortedWith(compareByDescending<DownloadTask> { it.createdAt }.thenByDescending { it.id })
    }

    private fun persist(operation: suspend (TaskRepository) -> Unit) {
        val taskRepository = synchronized(lock) { repository } ?: return
        persistenceScope.launch {
            try {
                initialization?.await()
                operation(taskRepository)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Task persistence operation failed", error)
            }
        }
    }

    private fun readLegacyNextId(context: Context): Long = context
        .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_NEXT_ID, 1L)
        .coerceAtLeast(1L)
}
