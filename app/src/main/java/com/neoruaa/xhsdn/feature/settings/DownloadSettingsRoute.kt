package com.neoruaa.xhsdn.feature.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import top.yukonga.miuix.kmp.preference.ArrowPreference
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.data.network.SessionCredentials
import com.neoruaa.xhsdn.data.settings.*
import com.neoruaa.xhsdn.data.tasks.exportRecords
import com.neoruaa.xhsdn.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun DownloadSettingsRoute(section: Int, onBack: () -> Unit, onOpenBrowser: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = remember(context) { (context.applicationContext as XHSApplication).appContainer }
    val repository = container.settingsRepository
    val settings by repository.settings.collectAsStateWithLifecycle(repository.currentSettings)
    val options = settings.downloadOptions
    val authors by remember { container.taskDatabase.downloadSessionDao().observeAuthors() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val layout = rememberWindowLayoutInfo()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberMiuixTopBarBackdrop()
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    var showChoice by rememberSaveable { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    // Do not persist credential text in saved instance state.
    var input by remember { mutableStateOf("") }
    var sessionRevision by remember { mutableIntStateOf(0) }
    val sessionStates by produceState<Map<String, Boolean>>(emptyMap(), sessionRevision) {
        value = withContext(Dispatchers.IO) { SessionCredentials.HOSTS.associateWith { container.credentials.get(it).isNotBlank() } }
    }
    fun message(id: Int) { Toast.makeText(context, resources.getString(id), Toast.LENGTH_SHORT).show() }
    fun update(transform: (DownloadOptions) -> DownloadOptions) {
        scope.launch {
            try { repository.update { state ->
                val updated = transform(state.downloadOptions)
                state.copy(downloadOptions = updated, createLivePhotos = updated.livePhotoMode == LivePhotoMode.MERGED)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message(R.string.download_error_storage) }
        }
    }
    fun export(uri: Uri?, json: Boolean) {
        if (uri == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { exportRecords(container.taskDatabase.downloadSessionDao(), it, json) }
                        ?: error("Unable to open record export")
                }
                message(R.string.settings_export_success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message(R.string.download_error_storage) }
        }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, true) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    val title = when (section) { 0 -> R.string.settings_media_title; 1 -> R.string.settings_archive_title; else -> R.string.settings_network_title }
    val rows = when (section) {
        0 -> listOf("images", "videos", "cover", "image_format", "video_quality", "live")
        1 -> listOf("skip", "author_archive", "note_archive", "note_format", "publish_time", "export_json", "export_csv")
        else -> listOf("timeout", "retries", "proxy", "proxy_media", "web_session", "login") + SessionCredentials.HOSTS.map { "cookie:$it" }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        topBar = { AdaptiveTopAppBar(stringResource(title), layout.isWideScreen, backdrop = backdrop,
            scrollBehavior = if (layout.isWideScreen) null else scrollBehavior,
            navigationIcon = { TopAppBarIconButton(MiuixIcons.Back, stringResource(R.string.back_content_description), onBack) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).miuixBackdropSource(backdrop)
                .miuixVerticalScrollEffects().nestedScroll(scrollBehavior.nestedScrollConnection),
            overscrollEffect = null,
            contentPadding = PaddingValues(start = layout.contentStartPadding, end = layout.contentEndPadding, top = padding.calculateTopPadding())
        ) {
            item("snapshot_hint") { SmallTitle(stringResource(R.string.settings_snapshot_hint)) }
            groupedCardItems(rows, key = { "setting_$it" }) { row ->
                when (row) {
                    "images" -> OptionSwitch(R.string.settings_download_images, checked = options.imageDownload) { update { it.copy(imageDownload = !it.imageDownload) } }
                    "videos" -> OptionSwitch(R.string.settings_download_videos, checked = options.videoDownload) { update { it.copy(videoDownload = !it.videoDownload) } }
                    "cover" -> OptionSwitch(R.string.settings_download_cover, checked = options.videoCoverDownload) { update { it.copy(videoCoverDownload = !it.videoCoverDownload) } }
                    "image_format" -> ArrowPreference(title = stringResource(R.string.settings_image_format), summary = stringResource(imageLabels[options.imageFormat.ordinal]), onClick = { choice = row; showChoice = true })
                    "video_quality" -> ArrowPreference(title = stringResource(R.string.settings_video_quality), summary = stringResource(videoLabels[options.videoPreference.ordinal]), onClick = { choice = row; showChoice = true })
                    "live" -> ArrowPreference(title = stringResource(R.string.settings_live_mode), summary = stringResource(liveLabels[options.livePhotoMode.ordinal]), onClick = { choice = row; showChoice = true })
                    "skip" -> OptionSwitch(R.string.settings_skip_existing, R.string.settings_skip_hint, options.skipExisting) { update { it.copy(skipExisting = !it.skipExisting) } }
                    "author_archive" -> OptionSwitch(R.string.settings_author_archive, checked = options.authorArchive) { update { it.copy(authorArchive = !it.authorArchive) } }
                    "note_archive" -> OptionSwitch(R.string.settings_note_archive, checked = options.noteArchive) { update { it.copy(noteArchive = !it.noteArchive) } }
                    "note_format" -> ArrowPreference(title = stringResource(R.string.settings_note_format), summary = stringResource(noteLabels[options.noteFormat.ordinal]), onClick = { choice = row; showChoice = true })
                    "publish_time" -> OptionSwitch(R.string.settings_publish_time, R.string.settings_publish_time_hint, options.writePublishTime) { update { it.copy(writePublishTime = !it.writePublishTime) } }
                    "export_json" -> BasicComponent(title = stringResource(R.string.settings_export_json), summary = stringResource(R.string.settings_export_hint), onClick = { exportJson.launch("xhs-download-records.json") },
                        endActions = { ActionIconButton(MiuixIcons.Regular.FileDownloads, stringResource(R.string.settings_export_json), { exportJson.launch("xhs-download-records.json") }) })
                    "export_csv" -> BasicComponent(title = stringResource(R.string.settings_export_csv), onClick = { exportCsv.launch("xhs-download-records.csv") },
                        endActions = { ActionIconButton(MiuixIcons.Regular.FileDownloads, stringResource(R.string.settings_export_csv), { exportCsv.launch("xhs-download-records.csv") }) })
                    "timeout", "retries" -> ArrowPreference(title = stringResource(if (row == "timeout") R.string.settings_timeout else R.string.settings_retries),
                        summary = (if (row == "timeout") options.timeoutSeconds else options.maxRetries).toString(),
                        onClick = { input = (if (row == "timeout") options.timeoutSeconds else options.maxRetries).toString(); edit = row; showEdit = true })
                    "proxy" -> ArrowPreference(title = stringResource(R.string.settings_proxy), summary = options.proxy.ifBlank { stringResource(R.string.settings_proxy_hint) }, onClick = { input = options.proxy; edit = row; showEdit = true })
                    "proxy_media" -> OptionSwitch(R.string.settings_proxy_media, checked = options.proxyDownload) { update { it.copy(proxyDownload = !it.proxyDownload) } }
                    "web_session" -> OptionSwitch(R.string.settings_web_session, checked = options.useWebSession) { update { it.copy(useWebSession = !it.useWebSession) } }
                    "login" -> BasicComponent(title = stringResource(R.string.settings_open_login), onClick = onOpenBrowser,
                        endActions = { ActionIconButton(MiuixIcons.Regular.Link, stringResource(R.string.settings_open_login), onOpenBrowser) })
                    else -> if (row.startsWith("cookie:")) {
                        val host = row.substringAfter(':')
                        ArrowPreference(title = stringResource(R.string.settings_cookie, host),
                            summary = stringResource(if (sessionStates[host] == true) R.string.settings_session_saved else R.string.settings_session_empty),
                            onClick = { input = ""; edit = row; showEdit = true })
                    }
                }
            }
            if (section == 0) item("media_hint") {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
                    listOf(R.string.settings_format_hint, R.string.settings_video_hint, R.string.settings_live_hint).forEach {
                        Text(stringResource(it), modifier = Modifier.padding(bottom = 8.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (section == 1) {
                item("authors_title") { SmallTitle(stringResource(R.string.settings_author_remarks)) }
                item("authors_hint") { Text(stringResource(if (authors.isEmpty()) R.string.settings_author_empty else R.string.settings_author_remarks_hint), modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                groupedCardItems(authors, key = { "author_${it.id}" }) { author ->
                    ArrowPreference(title = author.remark.ifBlank { author.nickname }, summary = stringResource(R.string.settings_author_identity, author.nickname, author.id),
                        onClick = { input = author.remark; edit = "author:${author.id}"; showEdit = true })
                }
            }
            item("bottom") { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
    choice?.let { key ->
        val (label, labels, selected) = when (key) {
            "image_format" -> Triple(R.string.settings_image_format, imageLabels, options.imageFormat.ordinal)
            "video_quality" -> Triple(R.string.settings_video_quality, videoLabels, options.videoPreference.ordinal)
            "live" -> Triple(R.string.settings_live_mode, liveLabels, options.livePhotoMode.ordinal)
            else -> Triple(R.string.settings_note_format, noteLabels, options.noteFormat.ordinal)
        }
        WindowDialog(
            title = stringResource(label),
            show = showChoice,
            onDismissRequest = { showChoice = false },
            onDismissFinished = { choice = null }
        ) {
            Column(Modifier.heightIn(max = 500.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    labels.forEachIndexed { index, resource ->
                        BasicComponent(title = stringResource(resource), modifier = Modifier.semantics { this.selected = selected == index }, onClick = {
                            update { when (key) {
                                "image_format" -> it.copy(imageFormat = ImageFormat.entries[index])
                                "video_quality" -> it.copy(videoPreference = VideoPreference.entries[index])
                                "live" -> it.copy(livePhotoMode = LivePhotoMode.entries[index])
                                else -> it.copy(noteFormat = NoteFormat.entries[index])
                            } }; showChoice = false
                        }, endActions = { if (selected == index) Icon(MiuixIcons.Regular.Ok, contentDescription = null) })
                    }
                }
                TextButton(stringResource(R.string.cancel), onClick = { showChoice = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    edit?.let { key ->
        val cookie = key.startsWith("cookie:")
        val label = when {
            cookie -> R.string.settings_cookie_hint
            key.startsWith("author:") -> R.string.settings_author_remarks
            key == "timeout" -> R.string.settings_timeout
            key == "retries" -> R.string.settings_retries
            else -> R.string.settings_proxy
        }
        val summary = if (cookie) stringResource(R.string.settings_cookie_hint) else when (key) {
            "timeout" -> stringResource(R.string.settings_integer_range, 5, 180)
            "retries" -> stringResource(R.string.settings_integer_range, 0, 10)
            "proxy" -> stringResource(R.string.settings_proxy_hint)
            else -> null
        }
        WindowDialog(
            title = if (cookie) stringResource(R.string.settings_cookie, key.substringAfter(':')) else stringResource(label),
            summary = summary,
            show = showEdit,
            onDismissRequest = { showEdit = false },
            onDismissFinished = { input = ""; edit = null }
        ) {
            Column(Modifier.heightIn(max = 500.dp)) {
                TextField(value = input, onValueChange = { input = it }, singleLine = true,
                    visualTransformation = if (cookie) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(stringResource(R.string.common_not_modified), { showEdit = false }, Modifier.weight(1f))
                    TextButton(stringResource(R.string.cancel), { showEdit = false }, Modifier.weight(1f))
                    TextButton(stringResource(R.string.apply), {
                        val value = input.trim()
                        val valid = when (key) {
                            "timeout" -> value.toIntOrNull() in 5..180
                            "retries" -> value.toIntOrNull() in 0..10
                            "proxy" -> value.isEmpty() || runCatching { java.net.URI(value).let { it.scheme in setOf("http", "socks5") && it.host != null && it.port in 1..65535 && it.userInfo == null && it.path.isNullOrEmpty() } }.getOrDefault(false)
                            else -> !value.contains('\n') && !value.contains('\r')
                        }
                        if (!valid) message(R.string.settings_invalid_value) else {
                            scope.launch {
                                try {
                                    when {
                                        cookie -> withContext(Dispatchers.IO) { container.credentials.set(key.substringAfter(':'), value) }
                                        key.startsWith("author:") -> withContext(Dispatchers.IO) {
                                            val dao = container.taskDatabase.downloadSessionDao()
                                            dao.author(key.substringAfter(':'))?.let { dao.saveAuthor(it.copy(remark = value)) }
                                        }
                                        else -> repository.update { state -> state.copy(downloadOptions = when (key) {
                                            "timeout" -> state.downloadOptions.copy(timeoutSeconds = value.toInt())
                                            "retries" -> state.downloadOptions.copy(maxRetries = value.toInt())
                                            else -> state.downloadOptions.copy(proxy = value)
                                        }) }
                                    }
                                    sessionRevision++; message(R.string.settings_saved)
                                    showEdit = false
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { message(R.string.download_error_storage) }
                            }
                        }
                    }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
private fun OptionSwitch(title: Int, summary: Int? = null, checked: Boolean, onToggle: () -> Unit) {
    BasicComponent(title = stringResource(title), summary = summary?.let { stringResource(it) }, onClick = onToggle,
        endActions = { Switch(checked = checked, onCheckedChange = { onToggle() }) })
}

private val imageLabels = listOf(R.string.settings_image_auto, R.string.settings_format_jpeg, R.string.settings_format_png, R.string.settings_format_webp, R.string.settings_format_heic, R.string.settings_format_avif)
private val videoLabels = listOf(R.string.settings_quality_resolution, R.string.settings_quality_bitrate, R.string.settings_quality_size, R.string.settings_quality_compatibility)
private val liveLabels = listOf(R.string.settings_live_merged, R.string.settings_live_separate, R.string.settings_live_still)
private val noteLabels = listOf(R.string.settings_format_none, R.string.settings_format_txt, R.string.settings_format_md, R.string.settings_format_both)
