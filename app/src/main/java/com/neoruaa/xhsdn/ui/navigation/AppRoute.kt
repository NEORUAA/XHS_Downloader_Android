package com.neoruaa.xhsdn.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Main : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data class Detail(
        val taskId: String,
        val taskTitle: String,
        val filePaths: List<String>,
        val noteContent: String? = null,
        val noteUrl: String? = null
    ) : AppRoute

    @Serializable
    data class WebView(
        val url: String?,
        val taskId: Long? = null
    ) : AppRoute
}
