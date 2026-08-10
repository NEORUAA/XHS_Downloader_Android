package com.neoruaa.xhsdn

import androidx.annotation.StringRes

/** Shared constants and helpers for custom naming templates. */
object NamingFormat {
    const val TOKEN_USERNAME = "username"

    const val TOKEN_USER_ID = "userId"

    const val TOKEN_TITLE = "title"

    const val TOKEN_POST_ID = "postId"

    const val TOKEN_PUBLISH_TIME = "publishTime"

    const val TOKEN_INDEX = "index"

    const val TOKEN_INDEX_PADDED = "index_padded"

    const val TOKEN_DOWNLOAD_TIMESTAMP = "downloadTimestamp"

    @JvmField
    val DEFAULT_TEMPLATE =
        buildPlaceholder(TOKEN_TITLE) + "(" + buildPlaceholder(TOKEN_USERNAME) + ")_" +
            buildPlaceholder(TOKEN_PUBLISH_TIME)

    private val tokenDefinitions: List<TokenDefinition> = listOf(
        TokenDefinition(TOKEN_USERNAME, R.string.token_username),
        TokenDefinition(TOKEN_USER_ID, R.string.token_user_id),
        TokenDefinition(TOKEN_TITLE, R.string.token_title),
        TokenDefinition(TOKEN_POST_ID, R.string.token_post_id),
        TokenDefinition(TOKEN_DOWNLOAD_TIMESTAMP, R.string.token_download_timestamp),
        TokenDefinition(TOKEN_PUBLISH_TIME, R.string.token_publish_time),
    )

    @JvmStatic
    fun getAvailableTokens(): List<TokenDefinition> = tokenDefinitions

    @JvmStatic
    fun buildPlaceholder(key: String): String = "{$key}"

    class TokenDefinition(
        @JvmField val key: String,
        @param:StringRes @JvmField val labelResId: Int,
    ) {
        val placeholder: String
            get() = buildPlaceholder(key)
    }
}
