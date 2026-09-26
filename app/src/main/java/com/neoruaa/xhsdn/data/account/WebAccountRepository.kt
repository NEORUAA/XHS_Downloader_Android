package com.neoruaa.xhsdn.data.account

import android.util.AtomicFile
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Display metadata only. Authentication remains in WebView's private cookie store. */
data class WebAccount(val userId: String, val nickname: String, val avatar: String)

class WebAccountRepository(file: File, scope: CoroutineScope) {
    private val storage = AtomicFile(file)
    private val lock = Mutex()
    private val mutableAccount = MutableStateFlow<WebAccount?>(null)
    val account = mutableAccount.asStateFlow()
    private val ready = scope.async {
        mutableAccount.value = runCatching {
            storage.openRead().bufferedReader().use { decode(JSONObject(it.readText())) }
        }.getOrNull()
    }

    suspend fun update(result: JSONObject) {
        ready.await()
        if (result.opt("loggedIn") !is Boolean) return
        val next = if (result.getBoolean("loggedIn")) decode(result) ?: return else null
        lock.withLock {
            if (next == mutableAccount.value) return
            val json = JSONObject().put("loggedIn", next != null)
            next?.let { json.put("userId", it.userId).put("nickname", it.nickname).put("avatar", it.avatar) }
            val output = storage.startWrite()
            try { output.write(json.toString().toByteArray()); storage.finishWrite(output) }
            catch (error: Throwable) { storage.failWrite(output); throw error }
            mutableAccount.value = next
        }
    }

    private fun decode(json: JSONObject): WebAccount? {
        if (!json.optBoolean("loggedIn")) return null
        val id = json.optString("userId")
        if (!id.matches(Regex("[a-fA-F0-9]{24}"))) return null
        val avatar = json.optString("avatar").takeIf { url ->
            runCatching { val uri = URI(url); uri.scheme == "https" && uri.host != null }.getOrDefault(false)
        }.orEmpty()
        return WebAccount(id, json.optString("nickname").take(200), avatar)
    }
}

object XhsWebSession {
    const val HOME = "https://www.xiaohongshu.com/explore"
    fun isTrusted(url: String?): Boolean = runCatching {
        val uri = URI(url.orEmpty())
        uri.scheme == "https" && uri.host in setOf("xiaohongshu.com", "www.xiaohongshu.com", "www.rednote.com", "rednote.com")
    }.getOrDefault(false)

    fun desktopUserAgent(default: String): String = default
        .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
        .replace(" Version/4.0", "").replace(" Mobile", "")
}
