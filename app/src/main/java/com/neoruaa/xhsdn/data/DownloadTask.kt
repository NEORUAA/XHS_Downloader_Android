package com.neoruaa.xhsdn.data

import android.content.Context
import android.content.SharedPreferences
import com.neoruaa.xhsdn.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 下载任务状态
 */
enum class TaskStatus {
    QUEUED,      // 排队中
    DOWNLOADING, // 下载中
    COMPLETED,   // 下载完成
    FAILED,      // 下载失败
    WAITING_FOR_USER // 等待用户操作 (如视频选择)
}

/**
 * 笔记类型
 */
enum class NoteType {
    IMAGE,  // 图文笔记
    VIDEO,  // 视频笔记
    UNKNOWN // 未知
}

/**
 * 下载任务数据类
 */
data class DownloadTask(
    val id: Long,
    val noteUrl: String,           // 笔记链接
    val noteTitle: String?,        // 笔记标题
    val noteType: NoteType,        // 笔记类型
    val totalFiles: Int,           // 总文件数
    val completedFiles: Int = 0,   // 已完成文件数
    val failedFiles: Int = 0,      // 失败文件数
    val currentFileProgress: Float = 0f, // 当前文件下载进度 (0.0 to 1.0)
    val status: TaskStatus,        // 任务状态
    val createdAt: Long,           // 创建时间
    val completedAt: Long? = null, // 完成时间
    val errorMessage: String? = null, // 错误信息
    val filePaths: List<String> = emptyList(), // 下载的文件路径列表
    val noteContent: String? = null // 笔记内容
) {
    val progress: Float
        get() = if (totalFiles > 0) {
            val calculatedProgress = (completedFiles + currentFileProgress) / totalFiles.toFloat()
            // Ensure progress is between 0.0 and 1.0
            calculatedProgress.coerceIn(0f, 1f)
        } else 0f
    
    val isActive: Boolean
        get() = status == TaskStatus.QUEUED || status == TaskStatus.DOWNLOADING || status == TaskStatus.WAITING_FOR_USER
    
    val isCompleted: Boolean
        get() = status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("noteUrl", noteUrl)
            put("noteTitle", noteTitle ?: "")
            put("noteType", noteType.name)
            put("totalFiles", totalFiles)
            put("completedFiles", completedFiles)
            put("failedFiles", failedFiles)
            put("currentFileProgress", currentFileProgress)
            put("status", status.name)
            put("createdAt", createdAt)
            put("completedAt", completedAt ?: 0L)
            put("errorMessage", errorMessage ?: "")
            put("filePaths", JSONArray(filePaths))
            put("noteContent", noteContent ?: "")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DownloadTask {
            return DownloadTask(
                id = json.getLong("id"),
                noteUrl = json.getString("noteUrl"),
                noteTitle = json.getString("noteTitle").takeIf { it.isNotEmpty() },
                noteType = try { NoteType.valueOf(json.getString("noteType")) } catch (e: Exception) { NoteType.UNKNOWN },
                totalFiles = json.getInt("totalFiles"),
                completedFiles = json.optInt("completedFiles", 0),
                failedFiles = json.optInt("failedFiles", 0),
                currentFileProgress = json.optDouble("currentFileProgress", 0.0).toFloat(),
                status = try { TaskStatus.valueOf(json.getString("status")) } catch (e: Exception) { TaskStatus.COMPLETED },
                createdAt = json.getLong("createdAt"),
                completedAt = json.optLong("completedAt", 0L).takeIf { it > 0 },
                errorMessage = json.optString("errorMessage").takeIf { it.isNotEmpty() },
                filePaths = json.optJSONArray("filePaths")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                noteContent = json.optString("noteContent").takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * 任务管理器 - SharedPreferences 持久化版本
 */
object TaskManager {
    private const val PREFS_NAME = "task_history"
    private const val KEY_TASKS = "tasks"
    private const val KEY_NEXT_ID = "next_id"
    
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var nextId = 1L
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    
    /**
     * 初始化 TaskManager（在 Application 或 Activity 的 onCreate 中调用）
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadTasks()
        }
    }
    
    private fun loadTasks() {
        prefs?.let { p ->
            nextId = p.getLong(KEY_NEXT_ID, 1L)
            val tasksJson = p.getString(KEY_TASKS, "[]") ?: "[]"
            try {
                val jsonArray = JSONArray(tasksJson)
                val tasks = mutableListOf<DownloadTask>()
                for (i in 0 until jsonArray.length()) {
                    try {
                        tasks.add(DownloadTask.fromJson(jsonArray.getJSONObject(i)))
                    } catch (e: Exception) {
                        // Skip invalid task
                    }
                }
                _tasks.value = tasks
            } catch (e: Exception) {
                _tasks.value = emptyList()
            }
        }
    }
    
    private fun saveTasks() {
        prefs?.edit()?.apply {
            val jsonArray = JSONArray()
            _tasks.value.forEach { task ->
                jsonArray.put(task.toJson())
            }
            putString(KEY_TASKS, jsonArray.toString())
            putLong(KEY_NEXT_ID, nextId)
            apply()
        }
    }
    
    /**
     * 获取所有任务（按创建时间降序）
     */
    fun getAllTasks(): Flow<List<DownloadTask>> = _tasks.map { 
        it.sortedByDescending { task -> task.createdAt }
    }
    
    /**
     * 根据 ID 获取任务
     */
    fun getTaskById(taskId: Long): DownloadTask? {
        return _tasks.value.find { it.id == taskId }
    }
    
    /**
     * 获取进行中的任务
     */
    fun getActiveTasks(): Flow<List<DownloadTask>> = _tasks.map {
        it.filter { task -> task.isActive }.sortedByDescending { task -> task.createdAt }
    }
    
    /**
     * 获取已完成的任务
     */
    fun getCompletedTasks(): Flow<List<DownloadTask>> = _tasks.map {
        it.filter { task -> task.isCompleted }.sortedByDescending { task -> task.createdAt }
    }
    
    /**
     * 创建新任务
     */
    fun createTask(
        noteUrl: String,
        noteTitle: String?,
        noteType: NoteType,
        totalFiles: Int,
        noteContent: String? = null
    ): Long {
        val taskId = nextId++
        val task = DownloadTask(
            id = taskId,
            noteUrl = noteUrl,
            noteTitle = noteTitle,
            noteType = noteType,
            totalFiles = totalFiles,
            status = TaskStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
            noteContent = noteContent
        )
        _tasks.value = _tasks.value + task
        saveTasks()
        return taskId
    }
    
    /**
     * 开始下载任务
     */
    fun startTask(taskId: Long) {
        updateTask(taskId) { it.copy(status = TaskStatus.DOWNLOADING) }
    }
    
    /**
     * 更新任务进度
     */
    fun updateProgress(taskId: Long, completedFiles: Int, failedFiles: Int, currentFileProgress: Float = 0f) {
        updateTask(taskId) { task ->
            val totalCompleted = completedFiles + failedFiles
            val newStatus = when {
                totalCompleted >= task.totalFiles -> {
                    if (failedFiles > 0) TaskStatus.FAILED else TaskStatus.COMPLETED
                }
                else -> TaskStatus.DOWNLOADING
            }

            // Calculate the new progress to compare with the current progress
            val newProgress = if (task.totalFiles > 0) {
                (completedFiles + currentFileProgress) / task.totalFiles.toFloat()
            } else {
                0f
            }

            val currentProgress = if (task.totalFiles > 0) {
                (task.completedFiles + task.currentFileProgress) / task.totalFiles.toFloat()
            } else {
                0f
            }

            // Only update if the new progress is greater than or equal to the current progress to prevent regression
            val shouldUpdate = newProgress >= currentProgress

            if (shouldUpdate) {
                task.copy(
                    status = newStatus,
                    completedFiles = completedFiles,
                    failedFiles = failedFiles,
                    currentFileProgress = currentFileProgress,
                    completedAt = if (newStatus in listOf(TaskStatus.COMPLETED, TaskStatus.FAILED))
                        System.currentTimeMillis()
                    else null,
                    errorMessage = if (failedFiles > 0) {
                        appContext?.getString(
                            R.string.main_download_partial_count,
                            failedFiles
                        )
                    } else {
                        null
                    }
                )
            } else {
                // Return the task unchanged to prevent progress regression
                task
            }
        }
    }

    /**
     * 添加文件路径到任务
     */
    fun addFilePath(taskId: Long, path: String) {
        updateTask(taskId) { task ->
            task.copy(filePaths = task.filePaths + path)
        }
    }
    
    /**
     * 标记任务完成
     */
    fun completeTask(taskId: Long, success: Boolean, errorMessage: String? = null) {
        updateTask(taskId) { task ->
            task.copy(
                status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        }
    }
    
    /**
     * 删除任务
     */
    fun deleteTask(taskId: Long) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
        saveTasks()
    }
    
    /**
     * 清空所有任务
     */
    fun clearAllTasks() {
        _tasks.value = emptyList()
        saveTasks()
    }
    
    /**
     * 获取当前进行中的任务
     */
    fun getCurrentActiveTask(): DownloadTask? {
        return _tasks.value.firstOrNull { it.status == TaskStatus.DOWNLOADING }
    }

    /**
     * 检查是否存在最近的相同任务 (防止重复下载)
     * @param url 笔记链接
     * @param durationMillis 时间阈值 (默认 1 小时)，在此时间内已创建的任务如果在进行中或已完成，则视为存在
     */
    fun hasRecentTask(url: String, durationMillis: Long = 3600_000): Boolean {
        val threshold = System.currentTimeMillis() - durationMillis
        return _tasks.value.any { task ->
            // URL 相同 且 (任务活跃 或 是最近创建的)
            task.noteUrl == url && (task.isActive || task.createdAt > threshold)
        }
    }
    
    /**
     * Update task type (e.g., when video is detected)
     */
    fun updateTaskType(taskId: Long, noteType: NoteType) {
        updateTask(taskId) { it.copy(noteType = noteType) }
    }

    /**
     * 重置任务以供重试（清空进度、文件、错误信息）
     */
    fun resetTask(taskId: Long) {
        updateTask(taskId) { task ->
            task.copy(
                status = TaskStatus.DOWNLOADING,
                completedFiles = 0,
                failedFiles = 0,
                filePaths = emptyList(),
                errorMessage = null,
                completedAt = null
            )
        }
    }

    /**
     * 更新任务状态和错误信息
     */
    fun updateTaskStatus(taskId: Long, status: TaskStatus, errorMessage: String? = null) {
        updateTask(taskId) { it.copy(status = status, errorMessage = errorMessage) }
    }

    /**
     * 通用任务更新函数
     */
    fun updateTask(taskId: Long, update: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) update(task) else task
        }
        saveTasks()
    }
}
