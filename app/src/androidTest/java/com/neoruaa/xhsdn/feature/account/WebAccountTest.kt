package com.neoruaa.xhsdn.feature.account

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoruaa.xhsdn.*
import com.neoruaa.xhsdn.data.account.*
import com.neoruaa.xhsdn.data.xhs.XhsNoteParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WebAccountTest {
    private val app = ApplicationProvider.getApplicationContext<XHSApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val userId = "0123456789abcdef01234567"
    private fun signedIn(name: String = "Account fixture") = JSONObject()
        .put("loggedIn", true).put("userId", userId).put("nickname", name).put("avatar", "")

    @Test fun persistsOnlyDisplayMetadataAndDistinguishesUnknownFromLogout() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(app.cacheDir, "account-persistence-fixture.json")
        try {
            val repo = WebAccountRepository(file, scope)
            repo.update(signedIn().put("cookie", "must-not-persist"))
            assertFalse(file.readText().contains("must-not-persist"))
            val reopened = WebAccountRepository(file, scope)
            assertEquals("Account fixture", withTimeout(3000) { reopened.account.first { it != null } }?.nickname)
            reopened.update(JSONObject())
            assertNotNull(reopened.account.value)
            reopened.update(signedIn("Switched account"))
            assertEquals("Switched account", reopened.account.value?.nickname)
            reopened.update(JSONObject().put("loggedIn", false))
            assertNull(reopened.account.value)
            assertFalse(file.readText().contains("Switched account"))
        } finally { scope.cancel(); file.delete() }
    }

    @Test fun desktopPageExtractsViewerSeparatelyFromAuthorAndRetainsNoteExtraction() = runBlocking<Unit> {
        val done = CompletableDeferred<Unit>()
        var web: WebView? = null
        instrumentation.runOnMainSync {
            web = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = XhsWebSession.desktopUserAgent(WebSettings.getDefaultUserAgent(app))
                assertFalse(settings.userAgentString.contains("Mobile"))
                assertFalse(settings.userAgentString.contains("Android"))
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { done.complete(Unit) }
                }
                val state = """{"user":{"loggedIn":{"__v_isRef":true,"value":true},"userInfo":{"__v_isRef":true,"value":{"userId":"$userId","nickname":"Viewer","images":"https://example.com/avatar.jpg"}},"userPageData":{"nickname":"Other author"}},"note":{"noteDetailMap":{"$userId":{"note":{"noteId":"$userId","title":"Desktop note","imageList":[{"urlDefault":"https://example.com/image.jpg"}]},"comments":{"list":[],"hasMore":false}}}}}"""
                loadDataWithBaseURL("https://www.xiaohongshu.com/explore/$userId", "<script>window.__INITIAL_STATE__=$state;</script>", "text/html", "UTF-8", null)
            }
        }
        suspend fun evaluate(script: String): JSONObject {
            val result = CompletableDeferred<String>()
            instrumentation.runOnMainSync { web!!.evaluateJavascript(script) { result.complete(it) } }
            return JSONObject(JSONTokener(withTimeout(3000) { result.await() }).nextValue() as String)
        }
        try {
            withTimeout(10_000) { done.await() }
            val accountScript = app.assets.open("xhs_account.js").bufferedReader().use { it.readText() }
            val account = evaluate(accountScript)
            assertEquals("Viewer", account.getString("nickname"))
            val note = evaluate(app.assets.open("xhs_extractor.js").bufferedReader().use { it.readText() })
            assertEquals("Desktop note", XhsNoteParser().parseDetail(note, "", userId).title)
            val loggedOut = evaluate("window.__INITIAL_STATE__.user.loggedIn.value=false;" + accountScript)
            assertFalse(loggedOut.getBoolean("loggedIn"))
            assertTrue(XhsWebSession.isTrusted("https://www.xiaohongshu.com/explore"))
            assertFalse(XhsWebSession.isTrusted("https://www.xiaohongshu.com.evil.example/"))
            assertFalse(XhsWebSession.isTrusted("http://www.xiaohongshu.com/"))
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }

    @Test fun settingsCardUpdatesAndOpensBrowser() = runBlocking<Unit> {
        val repo = app.appContainer.webAccount
        // Allow the existing asynchronous snapshot to load before preserving it.
        repo.update(JSONObject())
        val original = repo.account.value
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            return (0 until node.childCount).firstNotNullOfOrNull { find(text, node.getChild(it)) }
        }
        suspend fun awaitText(text: String): AccessibilityNodeInfo = withTimeout(8_000) {
            var node = find(text)
            while (node == null) { delay(50); node = find(text) }
            node
        }
        try {
            repo.update(JSONObject().put("loggedIn", false))
            awaitText(app.getString(R.string.settings_account_signed_out))
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(app.cacheDir, "account-signed-out.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            repo.update(signedIn())
            awaitText("Account fixture")
            awaitText(app.getString(R.string.settings_account_signed_in))
            var node = awaitText("Account fixture")
            while (!node.isClickable) node = node.parent ?: error("Missing account action")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitText(app.getString(R.string.webview_title))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            awaitText(app.getString(R.string.settings))
        } finally {
            scenario.close()
            repo.update(if (original == null) JSONObject().put("loggedIn", false) else JSONObject()
                .put("loggedIn", true).put("userId", original.userId).put("nickname", original.nickname).put("avatar", original.avatar))
        }
    }
}
