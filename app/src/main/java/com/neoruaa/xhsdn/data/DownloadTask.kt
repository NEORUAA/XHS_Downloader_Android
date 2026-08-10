package com.neoruaa.xhsdn.data

import com.neoruaa.xhsdn.data.storage.StoredMediaRef
import java.io.File
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
    val mediaRefs: List<StoredMediaRef> = emptyList(), // Persisted media locations
    val noteContent: String? = null // 笔记内容
) {
    /** Compatibility view for legacy callers while media locations migrate to stable URIs. */
    val filePaths: List<String>
        get() = mediaRefs.map(StoredMediaRef::path)

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
            put("mediaRefs", JSONArray(mediaRefs.map { media ->
                JSONObject().apply {
                    put("uri", media.uri)
                    put("displayName", media.displayName)
                    put("mimeType", media.mimeType)
                    put("sizeBytes", media.sizeBytes)
                    put("legacyPath", media.legacyPath ?: JSONObject.NULL)
                }
            }))
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
                mediaRefs = parseMediaRefs(json),
                noteContent = json.optString("noteContent").takeIf { it.isNotEmpty() }
            )
        }

        private fun parseMediaRefs(json: JSONObject): List<StoredMediaRef> {
            val mediaRefs = json.optJSONArray("mediaRefs")
            if (mediaRefs != null && mediaRefs.length() > 0) {
                return (0 until mediaRefs.length()).mapNotNull { index ->
                    val item = mediaRefs.optJSONObject(index) ?: return@mapNotNull null
                    val uri = item.optString("uri").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val legacyPath = item.optString("legacyPath")
                        .takeIf { it.isNotBlank() && it != "null" }
                    StoredMediaRef(
                        uri = uri,
                        displayName = item.optString("displayName")
                            .ifBlank { legacyPath?.let { File(it).name }.orEmpty() },
                        mimeType = item.optString("mimeType")
                            .ifBlank { inferMimeType(legacyPath ?: uri) },
                        sizeBytes = item.optLong("sizeBytes", 0L),
                        legacyPath = legacyPath
                    )
                }
            }

            return json.optJSONArray("filePaths")?.let { array ->
                (0 until array.length()).map { index -> legacyMediaRef(array.getString(index)) }
            } ?: emptyList()
        }

        fun legacyMediaRef(path: String): StoredMediaRef {
            val file = File(path)
            return StoredMediaRef(
                uri = file.toURI().toString(),
                displayName = file.name,
                mimeType = inferMimeType(path),
                sizeBytes = file.takeIf(File::exists)?.length() ?: 0L,
                legacyPath = path
            )
        }

        private fun inferMimeType(location: String): String = when (
            location.substringBefore('?').substringAfterLast('.', "").lowercase()
        ) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }
}
