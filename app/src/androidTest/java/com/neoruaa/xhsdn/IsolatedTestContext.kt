package com.neoruaa.xhsdn

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.UUID

/** Keeps migration fixtures away from the installed application's user data. */
class IsolatedTestContext(base: Context) : ContextWrapper(base) {
    private val prefix = "instrumentation_${UUID.randomUUID()}_"
    private val root = File(base.cacheDir, prefix).apply { mkdirs() }
    private val preferences = mutableSetOf<String>()

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolatedName = prefix + name
        preferences.add(isolatedName)
        return baseContext.getSharedPreferences(isolatedName, mode)
    }

    fun dispose() {
        preferences.forEach { baseContext.deleteSharedPreferences(it) }
        root.deleteRecursively()
    }
}
