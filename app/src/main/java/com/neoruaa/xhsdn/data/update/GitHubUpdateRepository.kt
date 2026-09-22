package com.neoruaa.xhsdn.data.update

import com.neoruaa.xhsdn.BuildConfig
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class GitHubRelease(val tag: String, val notes: String, val url: String)

sealed interface UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data class UpToDate(val latestTag: String) : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data class Failed(val reason: Reason, val httpCode: Int = 0) : UpdateCheckResult
    enum class Reason { NETWORK, TIMEOUT, RATE_LIMITED, HTTP, INVALID_RESPONSE, INVALID_VERSION }
}

class GitHubUpdateRepository(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = LATEST_RELEASE_URL,
) {
    suspend fun check(): UpdateCheckResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "XHS-Downloader-Android/$currentVersion")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.success(networkFailure(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val result = try {
                        when {
                            response.code == 404 -> UpdateCheckResult.NoRelease
                            response.code == 429 || (response.code == 403 &&
                                (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null)) ->
                                UpdateCheckResult.Failed(UpdateCheckResult.Reason.RATE_LIMITED)
                            response.code != 200 -> UpdateCheckResult.Failed(UpdateCheckResult.Reason.HTTP, response.code)
                            else -> evaluateRelease(JSONObject(response.body.string()), currentVersion)
                        }
                    } catch (error: IOException) {
                        networkFailure(error)
                    } catch (_: Exception) {
                        UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_RESPONSE)
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                }
            }
        })
    }

    private fun networkFailure(error: IOException) = UpdateCheckResult.Failed(
        if (error is InterruptedIOException) UpdateCheckResult.Reason.TIMEOUT else UpdateCheckResult.Reason.NETWORK
    )

    companion object {
        const val REPOSITORY_URL = "https://github.com/NEORUAA/XHS_Downloader_Android"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/NEORUAA/XHS_Downloader_Android/releases/latest"

        internal fun evaluateRelease(json: JSONObject, currentVersion: String): UpdateCheckResult {
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) return UpdateCheckResult.NoRelease
            if (json.isNull("published_at") || json.getString("published_at").isBlank()) {
                return UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_RESPONSE)
            }
            val tag = json.getString("tag_name").trim()
            val current = ReleaseVersion.parse(currentVersion)
            val latest = ReleaseVersion.parse(tag)
            if (current == null || latest == null) return UpdateCheckResult.Failed(UpdateCheckResult.Reason.INVALID_VERSION)
            if (latest <= current) return UpdateCheckResult.UpToDate(tag)
            val url = REPOSITORY_URL.toHttpUrl().newBuilder().addPathSegments("releases/tag").addPathSegment(tag).build().toString()
            return UpdateCheckResult.Available(GitHubRelease(tag, json.optString("body", "").trim(), url))
        }
    }
}

/** Numeric version segments and SemVer prerelease ordering; build metadata does not affect precedence. */
internal data class ReleaseVersion(val numbers: List<BigInteger>, val prerelease: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val comparison = (numbers.getOrNull(index) ?: BigInteger.ZERO).compareTo(other.numbers.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return when {
            prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
            prerelease.isEmpty() -> 1
            else -> -1
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("[vV]?(\\d+(?:\\.\\d+){1,3})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        fun parse(value: String): ReleaseVersion? {
            if (value.length > 256) return null
            val match = pattern.matchEntire(value.trim()) ?: return null
            return ReleaseVersion(match.groupValues[1].split('.').map(::BigInteger),
                match.groupValues[2].takeIf(String::isNotEmpty)?.split('.').orEmpty())
        }
    }
}
