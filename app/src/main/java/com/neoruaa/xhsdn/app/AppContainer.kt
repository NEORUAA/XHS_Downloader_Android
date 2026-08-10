package com.neoruaa.xhsdn.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.neoruaa.xhsdn.data.settings.DataStoreSettingsRepository
import com.neoruaa.xhsdn.data.settings.SettingsRepository
import com.neoruaa.xhsdn.data.media.AndroidResolvedMediaSink
import com.neoruaa.xhsdn.data.xhs.DefaultXhsContentRepository
import com.neoruaa.xhsdn.data.xhs.OkHttpXhsPageSource
import com.neoruaa.xhsdn.data.xhs.XhsContentRepository
import com.neoruaa.xhsdn.data.tasks.LegacyTaskHistoryImporter
import com.neoruaa.xhsdn.data.tasks.RoomTaskRepository
import com.neoruaa.xhsdn.data.tasks.TaskDatabase
import com.neoruaa.xhsdn.data.tasks.TaskDatabaseConstants
import com.neoruaa.xhsdn.data.tasks.TaskRepository
import com.neoruaa.xhsdn.data.tasks.TASK_DATABASE_MIGRATION_1_2
import com.neoruaa.xhsdn.domain.download.DownloadCoordinator
import com.neoruaa.xhsdn.domain.download.RepositoryDownloadCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped dependencies. This is deliberately a small manual container: the
 * app has one Gradle module and does not need a generated dependency graph yet.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val taskDatabase: TaskDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            TaskDatabase::class.java,
            TaskDatabaseConstants.DATABASE_NAME
        )
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(TASK_DATABASE_MIGRATION_1_2)
            .build()
    }

    val taskRepository: TaskRepository by lazy {
        RoomTaskRepository(taskDatabase)
    }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(appContext, scope)
    }

    val xhsContentRepository: XhsContentRepository by lazy {
        val pageSource = OkHttpXhsPageSource(com.neoruaa.xhsdn.FileDownloader.getSharedHttpClient())
        DefaultXhsContentRepository(
            fetchHtml = pageSource::fetchHtml,
            resolveShortUrl = pageSource::resolveShortUrl
        )
    }

    val downloadCoordinator: DownloadCoordinator by lazy {
        RepositoryDownloadCoordinator(
            contentRepository = xhsContentRepository,
            mediaSink = AndroidResolvedMediaSink(appContext)
        )
    }

    private val initializationStarted = AtomicBoolean(false)
    private val initializationCompletion = CompletableDeferred<Unit>()
    val initialization: Deferred<Unit>
        get() = initializationCompletion
    @Volatile
    private var initializationJob: Job? = null

    /** Starts legacy data import once; failures are retried by the next process. */
    fun startInitialization() {
        if (!initializationStarted.compareAndSet(false, true)) return
        initializationJob = scope.launch {
            try {
                LegacyTaskHistoryImporter(appContext, taskDatabase).importIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Keep the marker unset so a later launch retries the complete transaction.
                android.util.Log.e("AppContainer", "Legacy task history import failed", error)
            } finally {
                initializationCompletion.complete(Unit)
            }
        }
    }

    suspend fun awaitInitialization() {
        initializationJob?.join()
    }
}
