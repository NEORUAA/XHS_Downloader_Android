package com.neoruaa.xhsdn

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.neoruaa.xhsdn.app.AppContainer
import com.neoruaa.xhsdn.data.tasks.TaskManager

class XHSApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.startInitialization()
        TaskManager.attach(
            this,
            appContainer.taskRepository,
            appContainer.scope,
            appContainer.initialization
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                isAppInForeground = true
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                isAppInForeground = false
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        @Volatile
        var isAppInForeground: Boolean = false
            private set
    }
}
