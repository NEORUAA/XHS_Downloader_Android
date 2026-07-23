package com.neoruaa.xhsdn.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.neoruaa.xhsdn.DownloadCallback
import com.neoruaa.xhsdn.MainActivity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.XHSDownloader
import com.neoruaa.xhsdn.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import java.util.Locale

object BackgroundDownloadManager {
    private const val TAG = "BackgroundDownload"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val activeUrls = ConcurrentHashMap.newKeySet<String>()
    // Track current file progress for each task
    private val taskCurrentFileProgress = ConcurrentHashMap<Long, Float>()
    private const val CHANNEL_ID = "xhs_download_channel_v2"
    private const val BASE_NOTIFICATION_ID = 1000

    private fun isTerminalDownloadError(status: String): Boolean {
        val normalized = status.lowercase(Locale.ROOT)
        return normalized.contains("failed to download after") ||
            normalized.contains("exception downloading") ||
            normalized.contains("download failed") ||
            normalized.contains("io error downloading file") ||
            normalized.contains("security exception while downloading file") ||
            normalized.contains("non-media response received") ||
            normalized.contains("both image and video failed to download separately")
    }

    fun startDownload(context: Context, url: String, title: String? = null) {
        Log.d(TAG, "startDownload: $url, title: $title")
        val appContext = context.applicationContext
        
        // Ensure TaskManager is initialized
        TaskManager.init(appContext)

        // 背景下载允许在任何情况下尝试，去重逻辑由 activeUrls 承担
        Log.d(TAG, "startDownload attempt for: $url")
        
        // Check for duplicates - atomically add to set
        if (!activeUrls.add(url)) {
            Log.d(TAG, "startDownload: Task already active, skipping duplicate. URL: $url")
            NotificationHelper.showDiagnosticNotification(appContext, appContext.getString(R.string.task_ignored_downloading), appContext.getString(R.string.task_ignored_downloading))
            return
        }
        
        // Also check if TaskManager has it recently (redundant but safe)
        if (TaskManager.hasRecentTask(url)) {
            Log.d(TAG, "startDownload: Task matches recent task in DB, skipping. URL: $url")
            activeUrls.remove(url)
            NotificationHelper.showDiagnosticNotification(appContext, appContext.getString(R.string.task_ignored_recently_downloaded), appContext.getString(R.string.task_ignored_recently_downloaded))
            return
        }

        // Check for storage permissions
        if (!hasStoragePermission(appContext)) {
            Log.e(TAG, "startDownload: Missing storage permissions!")
            activeUrls.remove(url)
            NotificationHelper.showDownloadNotification(
                appContext,
                url.hashCode(),
                appContext.getString(R.string.auto_download_unavailable),
                appContext.getString(R.string.storage_permission_required),
                false
            )
            return
        }

        scope.launch {
            val prepId = url.hashCode()
            var taskId: Long = -1 // Initialize with invalid ID
            try {
                NotificationHelper.showDownloadNotification(appContext, prepId, appContext.getString(R.string.preparing_download), url, true, showProgress = false)

                // 1. Get info
                // We run this inside runCatching because getMediaCount might throw or do network ops
                val mediaCount = runCatching { XHSDownloader(appContext).getMediaCount(url) }.getOrElse { 0 }
                // Default to IMAGE, update later if video detected
                val noteType = NoteType.IMAGE
                
                // 2. Create Task
                taskId = TaskManager.createTask(url, title, noteType, if (mediaCount > 0) mediaCount else 1)
                
                // Store job for cancellation
                activeJobs[taskId] = coroutineContext[Job]!!
                
                TaskManager.startTask(taskId)
                
                // 取消准备阶段的通知，替换为带 ID 的正式任务通知
                NotificationHelper.cancelNotification(appContext, prepId)
                NotificationHelper.showDownloadNotification(appContext, taskId.toInt(), appContext.getString(R.string.preparing_download), appContext.getString(R.string.downloading_files_count, mediaCount), true)

                val completedFiles = java.util.concurrent.atomic.AtomicInteger(0)
                val failedFiles = java.util.concurrent.atomic.AtomicInteger(0)

                // 3. Setup Downloader
                val downloader = XHSDownloader(appContext, object : DownloadCallback {
                    override fun onFileDownloaded(filePath: String) {
                         val completed = completedFiles.incrementAndGet()
                         // Reset current file progress when file completes
                         taskCurrentFileProgress[taskId] = 0f
                         TaskManager.updateProgress(taskId, completed, failedFiles.get(), 0f)
                         TaskManager.addFilePath(taskId, filePath)
                         // Update notification if needed
                    }

                    override fun onDownloadError(status: String, originalUrl: String) {
                        if (isTerminalDownloadError(status)) {
                            val failed = failedFiles.incrementAndGet()
                            taskCurrentFileProgress[taskId] = 0f
                            TaskManager.updateProgress(taskId, completedFiles.get(), failed, 0f)
                        }
                        Log.w(TAG, "Download error for task $taskId: $status ($originalUrl)")
                    }

                    override fun onDownloadProgress(status: String) {}
                    override fun onDownloadProgressUpdate(downloaded: Long, total: Long) {
                        // Calculate individual file progress (0.0 to 1.0)
                        val currentFileProgress = if (total > 0 && downloaded >= 0) {
                            if (downloaded <= total) {
                                // Normal case: downloaded is less than or equal to total
                                downloaded.toFloat() / total.toFloat()
                            } else {
                                // Edge case: downloaded exceeds total (could happen with dynamic content)
                                // Cap at 1.0 to prevent progress > 100%
                                1.0f
                            }
                        } else {
                            0f
                        }

                        // Store the current file progress for this task
                        taskCurrentFileProgress[taskId] = currentFileProgress

                        // Update task progress with current file progress
                        // Use cached completed/failed counts to avoid database query
                        TaskManager.updateProgress(taskId, completedFiles.get(), failedFiles.get(), currentFileProgress)
                    }
                    override fun onVideoDetected() {
                         // Update task type to VIDEO as we found real video content
                         TaskManager.updateTaskType(taskId, NoteType.VIDEO)
                    }
                })
                
                downloader.setShouldStopOnVideo(false)
                
                // 4. Download
                val success = downloader.downloadContent(url)
                
                // 5. Complete
                if (success) {
                    val completed = completedFiles.get()
                    val failed = failedFiles.get()
                    
                    if (completed > 0 && failed == 0) {
                        TaskManager.completeTask(taskId, true)
                        NotificationHelper.showDownloadNotification(appContext, taskId.toInt(), appContext.getString(R.string.download_completed_notification_title), appContext.getString(R.string.download_completed_files_count, completed), false)
                    } else if (completed > 0) {
                        val partialMessage = appContext.getString(R.string.download_completed_files_count, completed) + " " +
                            appContext.getString(R.string.failed_files_format, failed)
                        TaskManager.completeTask(
                            taskId,
                            false,
                            appContext.getString(R.string.main_download_partial_count, failed)
                        )
                        NotificationHelper.showDownloadNotification(appContext, taskId.toInt(), appContext.getString(R.string.download_failed_notification_title), partialMessage, false)
                    } else {
                        TaskManager.completeTask(taskId, false, appContext.getString(R.string.download_failed_no_files))
                        NotificationHelper.showDownloadNotification(appContext, taskId.toInt(), appContext.getString(R.string.download_failed_notification_title), appContext.getString(R.string.download_failed_no_files), false)
                    }
                } else {
                    TaskManager.completeTask(
                        taskId,
                        false,
                        appContext.getString(
                            R.string.download_error_message,
                            appContext.getString(R.string.main_download_process_error)
                        )
                    )
                    NotificationHelper.showDownloadNotification(appContext, taskId.toInt(), appContext.getString(R.string.download_error_notification_title), appContext.getString(R.string.download_failed_check_network), false)
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.d(TAG, "Download cancelled for task $taskId")
                    // If task was created, mark it as failed due to cancellation
                    if (taskId != -1L) {
                        TaskManager.completeTask(taskId, false, appContext.getString(R.string.download_cancelled_by_user))
                    }
                    NotificationHelper.showDownloadNotification(appContext, if (taskId != -1L) taskId.toInt() else prepId, appContext.getString(R.string.download_cancelled_notification_title), appContext.getString(R.string.user_manually_stopped), false)
                } else {
                    Log.e(TAG, "Download error for task $taskId", e)
                    // If task was created, fail it
                    if (taskId != -1L) {
                        TaskManager.completeTask(
                            taskId,
                            false,
                            e.message ?: appContext.getString(
                                R.string.download_error_message,
                                appContext.getString(R.string.common_unknown_error)
                            )
                        )
                    }
                    NotificationHelper.showDownloadNotification(
                        appContext,
                        if (taskId != -1L) taskId.toInt() else prepId,
                        appContext.getString(R.string.download_error_notification_title),
                        e.message ?: appContext.getString(
                            R.string.download_error_message,
                            appContext.getString(R.string.common_unknown_error)
                        ),
                        false
                    )
                }
            } finally {
               // Remove job from activeJobs map regardless of success or failure
                if (taskId != -1L) {
                    activeJobs.remove(taskId)
                    // Clean up current file progress tracking
                    taskCurrentFileProgress.remove(taskId)
                }
                activeUrls.remove(url)
            }
        }
    }


    fun stopTask(context: Context, taskId: Long) {
        val job = activeJobs.remove(taskId)
        if (job != null) {
            job.cancel()
            TaskManager.completeTask(
                taskId,
                false,
                context.applicationContext.getString(R.string.user_manually_stopped)
            )
            // Clean up current file progress tracking
            taskCurrentFileProgress.remove(taskId)
            Log.d(TAG, "Task $taskId stopped by user")
        }
    }

    private fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}
