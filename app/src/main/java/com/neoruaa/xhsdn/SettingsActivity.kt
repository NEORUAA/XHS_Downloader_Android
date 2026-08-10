package com.neoruaa.xhsdn

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neoruaa.xhsdn.data.settings.AppSettings
import com.neoruaa.xhsdn.data.settings.SettingsRepository
import com.neoruaa.xhsdn.ui.ActionIconButton
import com.neoruaa.xhsdn.ui.AdaptiveTopAppBar
import com.neoruaa.xhsdn.ui.TopAppBarIconButton
import com.neoruaa.xhsdn.ui.groupedCardItems
import com.neoruaa.xhsdn.ui.rememberWindowLayoutInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.squircle.squircleBorder
import android.graphics.Color as AndroidColor

data class SettingsUiState(
    val createLivePhotos: Boolean = true,
    val useCustomNaming: Boolean = false,
    val template: TextFieldValue = TextFieldValue(NamingFormat.DEFAULT_TEMPLATE),
    val tokens: List<NamingFormat.TokenDefinition> = emptyList(),
    val debugNotificationEnabled: Boolean = false,
    val selectiveDownload: Boolean = false,
    val keepScreenOn: Boolean = false,
    val showClipboardBubble: Boolean = true,
    val autoReadClipboard: Boolean = false,
    val manualInputLinks: Boolean = false
)

class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {
    private val _state = MutableStateFlow(repository.currentSettings.toUiState())
    val state: StateFlow<SettingsUiState> = _state
    private val persistenceMutex = Mutex()
    private var hasChanges = false

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val currentTemplate = _state.value.template
                _state.value = settings.toUiState(
                    templateValue = if (currentTemplate.text == settings.customNamingTemplate) {
                        currentTemplate
                    } else {
                        TextFieldValue(settings.customNamingTemplate)
                    }
                )
            }
        }
    }

    fun onCreateLivePhotosChange(enabled: Boolean) = updateState {
        it.copy(createLivePhotos = enabled)
    }

    fun onUseCustomNamingChange(enabled: Boolean) = updateState {
        it.copy(useCustomNaming = enabled)
    }

    fun onTemplateChange(value: TextFieldValue) = updateState {
        it.copy(template = value)
    }

    fun onResetTemplate() = updateState {
        it.copy(template = TextFieldValue(NamingFormat.DEFAULT_TEMPLATE))
    }

    fun onDebugNotificationChange(enabled: Boolean) = updateState {
        it.copy(debugNotificationEnabled = enabled)
    }

    fun onSelectiveDownloadChange(enabled: Boolean) = updateState {
        it.copy(selectiveDownload = enabled)
    }

    fun onKeepScreenOnChange(enabled: Boolean) = updateState {
        it.copy(keepScreenOn = enabled)
    }

    fun onShowClipboardBubbleChange(enabled: Boolean) = updateState {
        it.copy(showClipboardBubble = enabled)
    }

    fun onAutoReadClipboardChange(enabled: Boolean) = updateState {
        it.copy(autoReadClipboard = enabled)
    }

    fun onManualInputLinksChange(enabled: Boolean) = updateState {
        it.copy(manualInputLinks = enabled)
    }

    private fun persist(state: SettingsUiState) {
        hasChanges = true
        viewModelScope.launch {
            persistenceMutex.withLock {
                repository.update { current ->
                    current.copy(
                        createLivePhotos = state.createLivePhotos,
                        useCustomNamingFormat = state.useCustomNaming,
                        customNamingTemplate = state.template.text.ifBlank { NamingFormat.DEFAULT_TEMPLATE },
                        debugNotificationEnabled = state.debugNotificationEnabled,
                        selectiveDownload = state.selectiveDownload,
                        keepScreenOn = state.keepScreenOn,
                        showClipboardBubble = state.showClipboardBubble,
                        autoReadClipboard = state.autoReadClipboard,
                        manualInputLinks = state.manualInputLinks,
                        useMetadataFileNames = false
                    )
                }
            }
        }
    }

    fun hasChanges(): Boolean = hasChanges

    private fun updateState(block: (SettingsUiState) -> SettingsUiState) {
        val updated = block(_state.value)
        _state.value = updated
        persist(updated)
    }

    private fun AppSettings.toUiState(
        templateValue: TextFieldValue = TextFieldValue(customNamingTemplate)
    ): SettingsUiState = SettingsUiState(
        createLivePhotos = createLivePhotos,
        useCustomNaming = useCustomNamingFormat,
        template = templateValue,
        tokens = NamingFormat.getAvailableTokens(),
        debugNotificationEnabled = debugNotificationEnabled,
        selectiveDownload = selectiveDownload,
        keepScreenOn = keepScreenOn,
        showClipboardBubble = showClipboardBubble,
        autoReadClipboard = autoReadClipboard,
        manualInputLinks = manualInputLinks
    )
}

class SettingsViewModelFactory(
    private val repository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(
            (application as XHSApplication).appContainer.settingsRepository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            )
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !isNightMode
        setContent {
            val controller = ThemeController(ColorSchemeMode.System)
            val uiState by viewModel.state.collectAsStateWithLifecycle()
            val topBarState = rememberTopAppBarState()
            MiuixTheme(controller = controller) {
                SettingsScreen(
                    uiState = uiState,
                    onBack = { finishWithResult() },
                    onCreateLivePhotosChange = viewModel::onCreateLivePhotosChange,
                    onUseCustomNamingChange = viewModel::onUseCustomNamingChange,
                    onTemplateChange = viewModel::onTemplateChange,
                    onResetTemplate = viewModel::onResetTemplate,
                    onDebugNotificationChange = viewModel::onDebugNotificationChange,
                    onSelectiveDownloadChange = viewModel::onSelectiveDownloadChange,
                    onKeepScreenOnChange = viewModel::onKeepScreenOnChange,
                    onShowClipboardBubbleChange = viewModel::onShowClipboardBubbleChange,
                    onAutoReadClipboardChange = viewModel::onAutoReadClipboardChange,
                    onManualInputLinksChange = viewModel::onManualInputLinksChange,
                    topBarState = topBarState
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        checkAccessibilityState()
    }
    
    private fun checkAccessibilityState() {
        // No-op
    }

    private fun finishWithResult() {
        setResult(if (viewModel.hasChanges()) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onCreateLivePhotosChange: (Boolean) -> Unit,
    onUseCustomNamingChange: (Boolean) -> Unit,
    onTemplateChange: (TextFieldValue) -> Unit,
    onResetTemplate: () -> Unit,
    onDebugNotificationChange: (Boolean) -> Unit,
    onSelectiveDownloadChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onShowClipboardBubbleChange: (Boolean) -> Unit,
    onAutoReadClipboardChange: (Boolean) -> Unit,
    onManualInputLinksChange: (Boolean) -> Unit,
    topBarState: TopAppBarState
) {
    val context = LocalContext.current
    val scrollBehavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior(state = topBarState)
    val windowLayoutInfo = rememberWindowLayoutInfo()
    val downloadRows = listOf(
        "create_live_photos",
        "selective_download",
        "debug_notifications",
        "keep_screen_on"
    )
    val clipboardRows = buildList {
        add("manual_input_links")
        if (!uiState.manualInputLinks) {
            add("show_clipboard_bubble")
            add("auto_read_clipboard")
        }
    }

    top.yukonga.miuix.kmp.basic.Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars
            .union(androidx.compose.foundation.layout.WindowInsets.displayCutout),
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.settings),
                isWideScreen = windowLayoutInfo.isWideScreen,
                navigationIcon = {
                    TopAppBarIconButton(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 4.dp)
                    )
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .background(MiuixTheme.colorScheme.surface),
            contentPadding = PaddingValues(
                start = windowLayoutInfo.contentStartPadding,
                top = padding.calculateTopPadding(),
                end = windowLayoutInfo.contentEndPadding
            )
        ) {
            item(key = "download_options_title") {
                SmallTitle(stringResource(R.string.download_options))
            }

            groupedCardItems(
                items = downloadRows,
                key = { "download_option_$it" }
            ) { row ->
                when (row) {
                    "create_live_photos" -> MiuixSwitchWidget(
                        title = stringResource(R.string.create_live_photos),
                        description = stringResource(R.string.create_live_photos_desc),
                        checked = uiState.createLivePhotos,
                        onCheckedChange = onCreateLivePhotosChange
                    )
                    "selective_download" -> MiuixSwitchWidget(
                        title = stringResource(R.string.selective_download),
                        description = stringResource(R.string.selective_download_desc),
                        checked = uiState.selectiveDownload,
                        onCheckedChange = onSelectiveDownloadChange
                    )
                    "debug_notifications" -> MiuixSwitchWidget(
                        title = stringResource(R.string.debug_notifications),
                        description = stringResource(R.string.debug_notifications_desc),
                        checked = uiState.debugNotificationEnabled,
                        onCheckedChange = onDebugNotificationChange
                    )
                    "keep_screen_on" -> MiuixSwitchWidget(
                        title = stringResource(R.string.keep_screen_on),
                        description = stringResource(R.string.keep_screen_on_desc),
                        checked = uiState.keepScreenOn,
                        onCheckedChange = onKeepScreenOnChange
                    )
                }
            }

            item(key = "clipboard_title") {
                SmallTitle(stringResource(R.string.clipboard))
            }

            groupedCardItems(
                items = clipboardRows,
                key = { "clipboard_option_$it" }
            ) { row ->
                when (row) {
                    "manual_input_links" -> MiuixSwitchWidget(
                        title = stringResource(R.string.manual_input_links),
                        description = stringResource(R.string.manual_input_links_desc),
                        checked = uiState.manualInputLinks,
                        onCheckedChange = onManualInputLinksChange
                    )
                    "show_clipboard_bubble" -> MiuixSwitchWidget(
                        title = stringResource(R.string.show_clipboard_bubble),
                        description = stringResource(R.string.show_clipboard_bubble_desc),
                        checked = uiState.showClipboardBubble,
                        onCheckedChange = onShowClipboardBubbleChange
                    )
                    "auto_read_clipboard" -> MiuixSwitchWidget(
                        title = stringResource(R.string.auto_read_clipboard),
                        description = stringResource(R.string.auto_read_clipboard_desc),
                        checked = uiState.autoReadClipboard,
                        onCheckedChange = onAutoReadClipboardChange
                    )
                }
            }

            item(key = "file_naming_title") {
                SmallTitle(stringResource(R.string.file_naming))
            }

            groupedCardItems(
                items = listOf("custom_naming"),
                key = { "file_naming_$it" }
            ) {
                MiuixSwitchWidget(
                    title = stringResource(R.string.enable_custom_naming),
                    description = stringResource(R.string.enable_custom_naming_desc),
                    checked = uiState.useCustomNaming,
                    onCheckedChange = onUseCustomNamingChange
                )
            }

            if (uiState.useCustomNaming) {
                item(key = "naming_template_field") {
                    TextField(
                        value = uiState.template,
                        onValueChange = onTemplateChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        label = stringResource(R.string.naming_template),
                        enabled = true,
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { }),
                        trailingIcon = {
                            ActionIconButton(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.reset_template),
                                onClick = onResetTemplate,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    )
                }

                item(key = "naming_placeholder_hint") {
                    Text(
                        text = stringResource(R.string.insert_placeholder_hint),
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 12.dp,
                            bottom = 8.dp
                        ),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                item(key = "naming_token_grid") {
                    TokenGrid(
                        tokens = uiState.tokens,
                        enabled = true,
                        onInsert = { placeholder ->
                            val current = uiState.template
                            val selectionStart = current.selection.start.coerceAtLeast(0)
                            val selectionEnd = current.selection.end.coerceAtLeast(0)
                            val min = minOf(selectionStart, selectionEnd)
                            val max = maxOf(selectionStart, selectionEnd)
                            val newText = buildString {
                                append(current.text.substring(0, min))
                                append(placeholder)
                                append(current.text.substring(max))
                            }
                            val newSelection = min + placeholder.length
                            onTemplateChange(
                                TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(newSelection)
                                )
                            )
                        }
                    )
                }
            }

            item(key = "about_title") {
                SmallTitle(stringResource(R.string.about))
            }

            groupedCardItems(
                items = listOf("version", "github"),
                key = { "about_$it" }
            ) { row ->
                when (row) {
                    "version" -> BasicComponent(
                        title = stringResource(R.string.version),
                        summary = stringResource(
                            R.string.version_with_prefix,
                            BuildConfig.VERSION_NAME
                        ),
                        onClick = { }
                    )
                    "github" -> BasicComponent(
                        title = stringResource(R.string.visit_github),
                        titleColor = BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.primary
                        ),
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/NEORUAA/XHS_Downloader_Android")
                            )
                            context.startActivity(intent)
                        }
                    )
                }
            }

            item(key = "settings_bottom_spacer") {
                Spacer(
                    modifier = Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun MiuixSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val toggleAction = {
        onCheckedChange(!checked)
    }

    BasicComponent(
        title = title,
        summary = description,
        onClick = toggleAction,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
private fun TokenGrid(
    tokens: List<NamingFormat.TokenDefinition>,
    enabled: Boolean,
    onInsert: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 0.dp, start = 12.dp, end = 12.dp, bottom = 16.dp)
    ) {
        tokens.forEach { token ->
            TokenChip(
                token = token,
                enabled = enabled,
                onInsert = onInsert,
                modifier = Modifier.fillMaxWidth(0.48f)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TokenChip(
    token: NamingFormat.TokenDefinition,
    enabled: Boolean,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = MiuixTheme.colorScheme.surface
    Card(
        modifier = modifier,
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = if (enabled) MiuixTheme.colorScheme.surfaceVariant else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = if (enabled) {
            { onInsert(token.placeholder) }
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(token.labelResId),
                fontSize = 16.sp,
                color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = token.placeholder,
                fontSize = 14.sp,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
            )
        }
    }
}
