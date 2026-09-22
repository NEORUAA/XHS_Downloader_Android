package com.neoruaa.xhsdn.data.tasks

import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.neoruaa.xhsdn.data.settings.DownloadJson

@Serializable
data class DownloadRecord(
    val taskId: Long,
    val noteId: String,
    val author: String,
    val title: String,
    val mediaId: String,
    val state: String,
    val createdAt: Long,
)

/** Export only logical records; credentials, signed URLs and local paths are excluded. */
suspend fun exportRecords(dao: DownloadSessionDao, output: OutputStream, json: Boolean) {
    output.bufferedWriter().use { writer ->
        if (json) writer.append("{\"version\":1,\"records\":[")
        else writer.append("task_id,note_id,author,title,media_id,state,created_at\r\n")
        var offset = 0
        var first = true
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = dao.exportPage(100, offset)
            if (rows.isEmpty()) break
            for (row in rows) {
                if (json) {
                    if (!first) writer.append(',')
                    writer.append(DownloadJson.encodeToString(row))
                } else writer.append(listOf(row.taskId.toString(), row.noteId, row.author, row.title, row.mediaId, row.state, row.createdAt.toString())
                    .joinToString(",", postfix = "\r\n", transform = ::csvCell))
                first = false
            }
            offset += rows.size
        }
        if (json) writer.append("]}")
    }
}

internal fun csvCell(value: String): String {
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}
