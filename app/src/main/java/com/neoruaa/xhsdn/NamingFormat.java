package com.neoruaa.xhsdn;

import androidx.annotation.StringRes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shared constants and helpers for custom naming templates.
 */
public final class NamingFormat {
    public static final String TOKEN_USERNAME = "username";
    public static final String TOKEN_USER_ID = "userId";
    public static final String TOKEN_TITLE = "title";
    public static final String TOKEN_POST_ID = "postId";
    public static final String TOKEN_PUBLISH_TIME = "publishTime";
    public static final String TOKEN_INDEX = "index";
    public static final String TOKEN_INDEX_PADDED = "index_padded";
    public static final String TOKEN_DOWNLOAD_TIMESTAMP = "downloadTimestamp";

    public static final String DEFAULT_TEMPLATE =
            buildPlaceholder(TOKEN_USERNAME) + "(" + buildPlaceholder(TOKEN_USER_ID) + ")" +
            buildPlaceholder(TOKEN_TITLE);

    private static final List<TokenDefinition> TOKEN_DEFINITIONS = Collections.unmodifiableList(Arrays.asList(
            new TokenDefinition(TOKEN_USERNAME, R.string.token_username),
            new TokenDefinition(TOKEN_USER_ID, R.string.token_user_id),
            new TokenDefinition(TOKEN_TITLE, R.string.token_title),
            new TokenDefinition(TOKEN_POST_ID, R.string.token_post_id),
            new TokenDefinition(TOKEN_PUBLISH_TIME, R.string.token_publish_time),
            new TokenDefinition(TOKEN_DOWNLOAD_TIMESTAMP, R.string.token_download_timestamp)
    ));

    private NamingFormat() {
    }

    public static List<TokenDefinition> getAvailableTokens() {
        return TOKEN_DEFINITIONS;
    }

    public static String buildPlaceholder(String key) {
        return "{" + key + "}";
    }

    public static class TokenDefinition {
        public final String key;
        @StringRes
        public final int labelResId;

        TokenDefinition(String key, @StringRes int labelResId) {
            this.key = key;
            this.labelResId = labelResId;
        }

        public String getPlaceholder() {
            return buildPlaceholder(key);
        }
    }
}
