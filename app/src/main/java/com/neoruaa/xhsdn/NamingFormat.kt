package com.neoruaa.xhsdn

import androidx.annotation.StringRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared constants and helpers for custom naming templates. */
object NamingFormat {
    const val TOKEN_USERNAME = "username"

    const val TOKEN_USER_ID = "userId"

    const val TOKEN_RED_ID = "redId"

    const val TOKEN_TITLE = "title"

    const val TOKEN_POST_ID = "postId"

    const val TOKEN_PUBLISH_TIME = "publishTime"

    const val TOKEN_INDEX = "index"

    const val TOKEN_INDEX_PADDED = "index_padded"

    const val TOKEN_DOWNLOAD_TIMESTAMP = "downloadTimestamp"

    const val DEFAULT_DATE_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    private val placeholders = Regex("\\{([^{}]*)\\}")
    private val supportedDateLetters = "yMdHhmsSaEZ"

    @JvmField
    val DEFAULT_TEMPLATE =
        buildPlaceholder(TOKEN_TITLE) + "(" + buildPlaceholder(TOKEN_USERNAME) + ")_" +
            buildPlaceholder(TOKEN_PUBLISH_TIME)

    private val tokenDefinitions: List<TokenDefinition> = listOf(
        TokenDefinition(TOKEN_USERNAME, R.string.token_username),
        TokenDefinition(TOKEN_USER_ID, R.string.token_user_id),
        TokenDefinition(TOKEN_RED_ID, R.string.token_red_id),
        TokenDefinition(TOKEN_TITLE, R.string.token_title),
        TokenDefinition(TOKEN_POST_ID, R.string.token_post_id),
        TokenDefinition(TOKEN_DOWNLOAD_TIMESTAMP, R.string.token_download_timestamp),
        TokenDefinition(TOKEN_PUBLISH_TIME, R.string.token_publish_time),
    )

    @JvmStatic
    fun getAvailableTokens(): List<TokenDefinition> = tokenDefinitions

    @JvmStatic
    fun buildPlaceholder(key: String, pattern: String? = null): String =
        if (pattern == null) "{$key}" else "{$key:$pattern}"

    fun isTimeToken(key: String): Boolean = key == TOKEN_PUBLISH_TIME || key == TOKEN_DOWNLOAD_TIMESTAMP

    fun containsToken(template: String, key: String): Boolean = placeholders.findAll(template)
        .any { it.groupValues[1].substringBefore(':') == key }

    /** Date letters shared by java.text and Android ICU, with quoted literals supported. */
    fun isValidDatePattern(pattern: String): Boolean {
        if (pattern.isBlank() || pattern.length > 80 || pattern.any { it == '{' || it == '}' || it.isISOControl() }) return false
        var quoted = false
        var hasDateField = false
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            if (character == '\'') {
                if (pattern.getOrNull(index + 1) == '\'') index++ else quoted = !quoted
            } else if (!quoted && (character in 'A'..'Z' || character in 'a'..'z')) {
                if (character !in supportedDateLetters) return false
                hasDateField = true
            }
            index++
        }
        return !quoted && hasDateField && runCatching { SimpleDateFormat(pattern, Locale.ROOT) }.isSuccess
    }

    fun formatTimestamp(timestamp: Long, pattern: String): String? =
        if (isValidDatePattern(pattern)) SimpleDateFormat(pattern, Locale.ROOT).format(Date(timestamp)) else null

    @StringRes
    fun validationError(template: String): Int? {
        val remaining = placeholders.replace(template, "")
        if ('{' in remaining || '}' in remaining) return R.string.settings_naming_invalid_template
        val known = tokenDefinitions.map { it.key } + listOf(TOKEN_INDEX, TOKEN_INDEX_PADDED)
        for (match in placeholders.findAll(template)) {
            val body = match.groupValues[1]
            val key = body.substringBefore(':')
            if (key !in known) return R.string.settings_naming_invalid_template
            if (':' in body && (!isTimeToken(key) || !isValidDatePattern(body.substringAfter(':')))) {
                return R.string.settings_naming_invalid_time_format
            }
        }
        return null
    }

    fun render(template: String, values: Map<String, String>, publishedAt: Long?, downloadedAt: Long): String {
        // Imported or legacy invalid templates must never interrupt a download.
        if (validationError(template) != null) return values[TOKEN_POST_ID].orEmpty()
        return placeholders.replace(template) { match ->
            val body = match.groupValues[1]
            val key = body.substringBefore(':')
            if (':' !in body) values[key].orEmpty() else {
                val timestamp = if (key == TOKEN_PUBLISH_TIME) publishedAt else downloadedAt
                timestamp?.let { formatTimestamp(it, body.substringAfter(':')) }.orEmpty()
            }
        }
    }

    class TokenDefinition(
        @JvmField val key: String,
        @param:StringRes @JvmField val labelResId: Int,
    ) {
        val placeholder: String
            get() = buildPlaceholder(key)
    }
}
