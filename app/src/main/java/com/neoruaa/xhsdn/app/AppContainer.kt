package com.neoruaa.xhsdn.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.neoruaa.xhsdn.data.settings.DataStoreSettingsRepository
import com.neoruaa.xhsdn.data.settings.SettingsRepository
import com.neoruaa.xhsdn.data.tasks.LegacyTaskHistoryImporter
import com.neoruaa.xhsdn.data.tasks.RoomTaskRepository
import com.neoruaa.xhsdn.data.tasks.TaskDatabase
import com.neoruaa.xhsdn.data.tasks.TaskDatabaseConstants
import com.neoruaa.xhsdn.data.tasks.TaskRepository
import com.neoruaa.xhsdn.data.tasks.TASK_DATABASE_MIGRATION_1_2
import com.neoruaa.xhsdn.data.tasks.TASK_DATABASE_MIGRATION_2_3
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
            .addMigrations(TASK_DATABASE_MIGRATION_1_2, TASK_DATABASE_MIGRATION_2_3)
            .build()
    }

    val taskRepository: TaskRepository by lazy {
        RoomTaskRepository(taskDatabase)
    }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(appContext, scope)
    }

    val webAccount by lazy { com.neoruaa.xhsdn.data.account.WebAccountRepository(java.io.File(appContext.noBackupFilesDir, "web-account.json"), scope) }
    val credentials by lazy { com.neoruaa.xhsdn.data.network.SessionCredentials(appContext) }
    val network by lazy { com.neoruaa.xhsdn.data.network.XhsNetwork(credentials) }
    val downloadQueue by lazy { com.neoruaa.xhsdn.domain.download.DownloadQueue(appContext, this) }
    val updateChecker by lazy {
        val repository = com.neoruaa.xhsdn.data.update.GitHubUpdateRepository()
        com.neoruaa.xhsdn.feature.update.UpdateCheckController(scope, repository::check)
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
                taskDatabase.downloadSessionDao().recoverInterrupted()
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
