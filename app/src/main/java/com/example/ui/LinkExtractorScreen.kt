package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.download.DownloadEngine
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.PendingDownloadRequest
import com.example.intent.VlcIntentHelper
import com.example.network.CurlResolutionResult
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.launch

@Composable
fun FiXLinkApp(
    viewModel: LinkExtractorViewModel,
    downloadEngine: DownloadEngine,
    onLanguagePersisted: (AppLanguage) -> Unit,
    onDeveloperModePersisted: (Boolean) -> Unit,
) {
    val state by viewModel.screenState.collectAsStateWithLifecycle()
    val downloadTasks by downloadEngine.tasks.collectAsStateWithLifecycle()
    val concurrencyLimit by downloadEngine.concurrencyLimit.collectAsStateWithLifecycle()
    val connectionsPerDownload by downloadEngine.connectionsPerDownload.collectAsStateWithLifecycle()
    val saveLocation by downloadEngine.saveLocation.collectAsStateWithLifecycle()

    val strings = stringsFor(state.language)
    val layoutDirection = if (state.language == AppLanguage.FA) LayoutDirection.Rtl else LayoutDirection.Ltr
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var pendingConflictRequest by remember { mutableStateOf<PendingDownloadRequest?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Download continues regardless; notification shown if granted */ },
    )

    fun showMessage(msg: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri ->
            if (treeUri != null) {
                runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(treeUri, flags)
                }
                downloadEngine.setCustomSaveLocation(treeUri)
                showMessage(strings.msgFolderUpdated)
            }
        },
    )

    fun launchSaveFolderPicker() {
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        } else {
            null
        }
        folderPickerLauncher.launch(initialUri)
    }

    fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun showQueuedFeedback(fromResolver: Boolean) {
        if (!fromResolver) {
            showMessage(strings.msgDownloadQueued)
            return
        }
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val action = snackbarHostState.showSnackbar(
                message = strings.msgDownloadQueued,
                actionLabel = strings.navDownloads,
            )
            if (action == SnackbarResult.ActionPerformed) {
                viewModel.navigateTo(AppDestination.DOWNLOADS)
            }
        }
    }

    fun requestDownloadWithConflictCheck(
        url: String,
        sourcePlayUrl: String?,
        suggestedFileName: String?,
        fromResolver: Boolean,
    ) {
        val candidateName = downloadEngine.resolveCandidateFileName(url, suggestedFileName)
        if (downloadEngine.hasFileConflict(candidateName, url = url, sourcePlayUrl = sourcePlayUrl)) {
            pendingConflictRequest = PendingDownloadRequest(
                url = url,
                sourcePlayUrl = sourcePlayUrl,
                originalFileName = candidateName,
                suggestedUniqueFileName = downloadEngine.suggestUniqueFileName(candidateName),
                folderDisplayName = saveLocation.displayPath,
            )
        } else {
            ensureNotificationPermissionIfNeeded()
            downloadEngine.enqueue(
                url = url,
                sourcePlayUrl = sourcePlayUrl,
                suggestedFileName = candidateName,
                overwriteExisting = false,
            )
            showQueuedFeedback(fromResolver)
        }
    }

    fun startDownloadFromResolver(result: CurlResolutionResult) {
        val directUrl = result.directVideoUrl ?: return
        requestDownloadWithConflictCheck(
            url = directUrl,
            sourcePlayUrl = result.sourceUrl.takeIf { it.isNotBlank() },
            suggestedFileName = result.filename,
            fromResolver = true,
        )
    }

    fun startManualDownload(url: String, fileName: String?) {
        requestDownloadWithConflictCheck(
            url = url,
            sourcePlayUrl = null,
            suggestedFileName = fileName,
            fromResolver = false,
        )
    }

    fun openDownloadedFile(task: DownloadTask) {
        val uri = downloadEngine.getOpenableUri(task)
        if (uri == null) {
            showMessage(strings.msgNoFileOpener)
            return
        }
        try {
            val mimeType = downloadEngine.getMimeTypeForTask(task.fileName)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            showMessage(strings.msgNoFileOpener)
        }
    }

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            showMessage(strings.msgNoBrowser)
        }
    }

    fun toggleVlcInterceptor(enabled: Boolean) {
        VlcIntentHelper.setVlcInterceptorEnabled(context, enabled)
        viewModel.onVlcInterceptorToggled(enabled)
        showMessage(if (enabled) strings.msgVlcEnabled else strings.msgVlcDisabled)
    }

    if (state.destination != AppDestination.RESOLVER) {
        BackHandler {
            viewModel.navigateTo(AppDestination.RESOLVER)
        }
    }

    val activeDownloadCount = downloadTasks.count { it.isActive || it.status == DownloadStatus.QUEUED }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars,
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            NavigationBarItem(
                                selected = state.destination == AppDestination.RESOLVER,
                                onClick = { viewModel.navigateTo(AppDestination.RESOLVER) },
                                icon = {
                                    Icon(
                                        imageVector = if (state.destination == AppDestination.RESOLVER) {
                                            Icons.Rounded.Link
                                        } else {
                                            Icons.Outlined.Link
                                        },
                                        contentDescription = strings.navResolver,
                                    )
                                },
                                label = { Text(strings.navResolver) },
                                modifier = Modifier.testTag("nav_resolver"),
                            )
                            NavigationBarItem(
                                selected = state.destination == AppDestination.DOWNLOADS,
                                onClick = { viewModel.navigateTo(AppDestination.DOWNLOADS) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (activeDownloadCount > 0) {
                                                Badge { Text(activeDownloadCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (state.destination == AppDestination.DOWNLOADS) {
                                                Icons.Rounded.Download
                                            } else {
                                                Icons.Outlined.Download
                                            },
                                            contentDescription = strings.navDownloads,
                                        )
                                    }
                                },
                                label = { Text(strings.navDownloads) },
                                modifier = Modifier.testTag("nav_downloads"),
                            )
                            NavigationBarItem(
                                selected = state.destination == AppDestination.SETTINGS,
                                onClick = {
                                    viewModel.syncVlcStatus(
                                        enabled = VlcIntentHelper.isVlcInterceptorEnabled(context),
                                        installed = VlcIntentHelper.isVlcInstalled(context),
                                    )
                                    viewModel.navigateTo(AppDestination.SETTINGS)
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (state.destination == AppDestination.SETTINGS) {
                                            Icons.Rounded.Settings
                                        } else {
                                            Icons.Outlined.Settings
                                        },
                                        contentDescription = strings.navSettings,
                                    )
                                },
                                label = { Text(strings.navSettings) },
                                modifier = Modifier.testTag("nav_settings"),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (useNavRail) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            NavigationRailItem(
                                selected = state.destination == AppDestination.RESOLVER,
                                onClick = { viewModel.navigateTo(AppDestination.RESOLVER) },
                                icon = { Icon(Icons.Rounded.Link, contentDescription = strings.navResolver) },
                                label = { Text(strings.navResolver) },
                            )
                            NavigationRailItem(
                                selected = state.destination == AppDestination.DOWNLOADS,
                                onClick = { viewModel.navigateTo(AppDestination.DOWNLOADS) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (activeDownloadCount > 0) {
                                                Badge { Text(activeDownloadCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Download, contentDescription = strings.navDownloads)
                                    }
                                },
                                label = { Text(strings.navDownloads) },
                            )
                            NavigationRailItem(
                                selected = state.destination == AppDestination.SETTINGS,
                                onClick = {
                                    viewModel.syncVlcStatus(
                                        enabled = VlcIntentHelper.isVlcInterceptorEnabled(context),
                                        installed = VlcIntentHelper.isVlcInstalled(context),
                                    )
                                    viewModel.navigateTo(AppDestination.SETTINGS)
                                },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = strings.navSettings) },
                                label = { Text(strings.navSettings) },
                            )
                        }
                    }

                    when (state.destination) {
                        AppDestination.RESOLVER -> {
                            ResolverScreen(
                                state = state,
                                strings = strings,
                                onShowMessage = ::showMessage,
                                onInputChanged = viewModel::onInputChanged,
                                onClearInput = viewModel::onClearInput,
                                onExecute = viewModel::executeExtraction,
                                onCancel = viewModel::onCancelExecution,
                                onToggleLogExpanded = viewModel::onToggleLogExpanded,
                                onSelectHistoryItem = viewModel::onSelectHistoryItem,
                                onAcceptClipboardUrl = viewModel::acceptDetectedClipboardUrl,
                                onDismissClipboardUrl = viewModel::dismissDetectedClipboardUrl,
                                onDownloadInApp = ::startDownloadFromResolver,
                            )
                        }

                        AppDestination.DOWNLOADS -> {
                            DownloadsScreen(
                                tasks = downloadTasks,
                                filter = state.downloadFilter,
                                concurrencyLimit = concurrencyLimit,
                                connectionsPerDownload = connectionsPerDownload,
                                saveLocation = saveLocation,
                                strings = strings,
                                onFilterSelected = viewModel::setDownloadFilter,
                                onConcurrencyChanged = downloadEngine::setConcurrencyLimit,
                                onConnectionsChanged = downloadEngine::setConnectionsPerDownload,
                                onPickSaveFolder = ::launchSaveFolderPicker,
                                onResetSaveFolder = {
                                    downloadEngine.resetSaveLocationToDefault()
                                    showMessage(strings.msgFolderReset)
                                },
                                onPauseTask = downloadEngine::pause,
                                onResumeTask = {
                                    ensureNotificationPermissionIfNeeded()
                                    downloadEngine.resume(it)
                                },
                                onMoveToTop = downloadEngine::moveToTop,
                                onDeleteTask = { downloadEngine.remove(it, deleteFiles = true) },
                                onCopyTaskUrl = { url ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("download_url", url))
                                    showMessage(strings.msgTaskUrlCopied)
                                },
                                onOpenTaskFile = ::openDownloadedFile,
                                onPauseAll = downloadEngine::pauseAll,
                                onResumeAll = {
                                    ensureNotificationPermissionIfNeeded()
                                    downloadEngine.resumeAll()
                                },
                                onClearCompleted = downloadEngine::clearCompleted,
                                onAddManualDownload = ::startManualDownload,
                            )
                        }

                        AppDestination.SETTINGS -> {
                            SettingsScreen(
                                state = state,
                                concurrencyLimit = concurrencyLimit,
                                connectionsPerDownload = connectionsPerDownload,
                                saveLocation = saveLocation,
                                currentVersion = BuildConfig.VERSION_NAME,
                                strings = strings,
                                onSelectLanguage = { lang ->
                                    viewModel.setLanguage(lang)
                                    onLanguagePersisted(lang)
                                },
                                onConcurrencyChanged = downloadEngine::setConcurrencyLimit,
                                onConnectionsChanged = downloadEngine::setConnectionsPerDownload,
                                onPickSaveFolder = ::launchSaveFolderPicker,
                                onResetSaveFolder = {
                                    downloadEngine.resetSaveLocationToDefault()
                                    showMessage(strings.msgFolderReset)
                                },
                                onToggleVlcInterceptor = ::toggleVlcInterceptor,
                                onOpenVlcSettings = { VlcIntentHelper.openVlcAppSettings(context) },
                                onCheckUpdates = { viewModel.checkForAppUpdates(BuildConfig.VERSION_NAME) },
                                onOpenGitHubUrl = ::openExternalUrl,
                                onDownloadReleaseApk = { apkUrl, apkName ->
                                    startManualDownload(apkUrl, apkName)
                                },
                                onAttemptUnlockDeveloper = { password ->
                                    val ok = viewModel.tryUnlockDeveloperMode(password)
                                    if (ok) {
                                        onDeveloperModePersisted(true)
                                        showMessage(strings.msgDevEnabled)
                                    }
                                    ok
                                },
                                onDisableDeveloperMode = {
                                    viewModel.disableDeveloperMode()
                                    onDeveloperModePersisted(false)
                                    showMessage(strings.msgDevDisabled)
                                },
                            )
                        }
                    }
                }
            }

            pendingConflictRequest?.let { req ->
                FileConflictDialog(
                    request = req,
                    strings = strings,
                    onRenameAndDownload = { customName ->
                        pendingConflictRequest = null
                        ensureNotificationPermissionIfNeeded()
                        downloadEngine.enqueue(
                            url = req.url,
                            sourcePlayUrl = req.sourcePlayUrl,
                            suggestedFileName = customName,
                            overwriteExisting = false,
                        )
                        showQueuedFeedback(fromResolver = state.destination == AppDestination.RESOLVER)
                    },
                    onOverwrite = {
                        pendingConflictRequest = null
                        ensureNotificationPermissionIfNeeded()
                        downloadEngine.enqueue(
                            url = req.url,
                            sourcePlayUrl = req.sourcePlayUrl,
                            suggestedFileName = req.originalFileName,
                            overwriteExisting = true,
                        )
                        showQueuedFeedback(fromResolver = state.destination == AppDestination.RESOLVER)
                    },
                    onDismiss = { pendingConflictRequest = null },
                )
            }
        }
    }
}

@Composable
private fun ResolverScreen(
    state: LinkExtractorScreenState,
    strings: AppStrings,
    onShowMessage: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onClearInput: () -> Unit,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onToggleLogExpanded: () -> Unit,
    onSelectHistoryItem: (CurlResolutionResult) -> Unit,
    onAcceptClipboardUrl: () -> Unit,
    onDismissClipboardUrl: () -> Unit,
    onDownloadInApp: (CurlResolutionResult) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val primaryGlow = MaterialTheme.colorScheme.primary
    val secondaryGlow = MaterialTheme.colorScheme.secondary

    fun copyToClipboard(label: String, text: String, toastMessage: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        onShowMessage(toastMessage)
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!clipText.isNullOrBlank()) {
            onInputChanged(clipText.trim())
        } else {
            onShowMessage(strings.msgNoClipboard)
        }
    }

    fun openDownloadUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {
            onShowMessage(strings.msgNoBrowser)
        }
    }

    fun shareDownloadUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, strings.actionShareLink))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryGlow.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.85f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(secondaryGlow.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.25f),
                        radius = size.width * 0.65f,
                    ),
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderBanner(
                uiState = state.uiState,
                strings = strings,
                isDeveloperMode = state.isDeveloperMode,
            )

            AnimatedVisibility(visible = state.capturedFromExternalIntent) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CastConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = strings.vlcCapturedBanner,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.detectedClipboardUrl != null) {
                state.detectedClipboardUrl?.let { clipUrl ->
                    ClipboardDetectedBanner(
                        url = clipUrl,
                        strings = strings,
                        onExtract = onAcceptClipboardUrl,
                        onDismiss = onDismissClipboardUrl,
                    )
                }
            }

            // Clean URL Input Card (zero mention of curl or VLC settings unless Dev/captured)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = strings.inputCardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = onInputChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("url_input_field"),
                            placeholder = {
                                Text(
                                    text = if (state.isDeveloperMode) {
                                        strings.inputPlaceholderDev
                                    } else {
                                        strings.inputPlaceholder
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFamily,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            ),
                            minLines = 2,
                            maxLines = 5,
                            shape = MaterialTheme.shapes.medium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    onExecute()
                                }
                            ),
                        )
                    }

                    Text(
                        text = if (state.isDeveloperMode) strings.inputHelperDev else strings.inputHelper,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = ::pasteFromClipboard,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("paste_button"),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = strings.cdPasteInput,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = strings.actionPaste)
                        }

                        OutlinedButton(
                            onClick = onClearInput,
                            enabled = state.inputText.isNotEmpty() || state.uiState !is ExtractorUiState.Idle,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("clear_button"),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = strings.cdClearInput,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = strings.actionClear)
                        }
                    }

                    val isRunning = state.uiState is ExtractorUiState.Loading
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (isRunning) onCancel() else onExecute()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("extract_button"),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (isRunning) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        ),
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.actionCancel,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.actionExtract,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            when (val uiState = state.uiState) {
                is ExtractorUiState.Idle -> {
                    if (state.sessionHistory.isEmpty()) {
                        EmptyGuideCard(strings = strings)
                    }
                }

                is ExtractorUiState.Loading -> {
                    if (state.isDeveloperMode) {
                        TerminalTraceCard(
                            verboseLog = uiState.liveLog,
                            strings = strings,
                            isExpanded = true,
                            onToggleExpand = {},
                            onCopyLog = {
                                copyToClipboard("dev_log", it, strings.msgLogCopied)
                            },
                        )
                    } else {
                        NormalLoadingCard(strings = strings)
                    }
                }

                is ExtractorUiState.Success -> {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            DirectLinkResultCard(
                                result = uiState.result,
                                strings = strings,
                                isDeveloperMode = state.isDeveloperMode,
                                onDownloadInApp = onDownloadInApp,
                                onCopyLink = {
                                    copyToClipboard("direct_video_url", it, strings.msgCopied)
                                },
                                onOpenLink = ::openDownloadUrl,
                                onShareLink = ::shareDownloadUrl,
                            )

                            if (state.isDeveloperMode) {
                                RedirectChainCard(
                                    hops = uiState.result.hops,
                                    strings = strings,
                                )

                                TerminalTraceCard(
                                    verboseLog = uiState.result.verboseLog,
                                    strings = strings,
                                    isExpanded = state.isLogExpanded,
                                    onToggleExpand = onToggleLogExpanded,
                                    onCopyLog = {
                                        copyToClipboard("dev_log", it, strings.msgLogCopied)
                                    },
                                )
                            }
                        }
                    }
                }

                is ExtractorUiState.Error -> {
                    val errorText = when (uiState.errorType) {
                        ErrorType.EMPTY_URL -> strings.errorEmptyUrl
                        ErrorType.INVALID_URL -> strings.errorInvalidUrl
                        ErrorType.NO_VIDEO_FOUND -> strings.errorNoVideoFound
                        ErrorType.HOST_UNREACHABLE -> strings.errorHostUnreachable
                        ErrorType.NETWORK_GENERIC -> strings.errorNetworkGeneric
                        ErrorType.CANCELLED -> strings.errorCancelled
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }

                    if (state.isDeveloperMode && uiState.verboseLog.isNotBlank()) {
                        TerminalTraceCard(
                            verboseLog = uiState.verboseLog,
                            strings = strings,
                            isExpanded = state.isLogExpanded,
                            onToggleExpand = onToggleLogExpanded,
                            onCopyLog = {
                                copyToClipboard("dev_log", it, strings.msgLogCopied)
                            },
                        )
                    }
                }
            }

            SessionHistorySection(
                history = state.sessionHistory,
                strings = strings,
                onSelectItem = onSelectHistoryItem,
                onCopyLink = {
                    copyToClipboard("direct_video_url", it, strings.msgCopied)
                },
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HeaderBanner(
    uiState: ExtractorUiState,
    strings: AppStrings,
    isDeveloperMode: Boolean,
) {
    val (statusLabel, statusBg, statusFg) = when (uiState) {
        is ExtractorUiState.Idle -> Triple(
            strings.statusReady,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ExtractorUiState.Loading -> Triple(
            if (isDeveloperMode) strings.statusProcessingStep(uiState.currentHop) else strings.statusProcessing,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        is ExtractorUiState.Success -> Triple(
            strings.statusDone,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        is ExtractorUiState.Error -> Triple(
            strings.statusError,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (isDeveloperMode) {
                        BadgePill(
                            text = strings.devBadge,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = strings.appSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = statusBg,
        ) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = statusFg,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun NormalLoadingCard(strings: AppStrings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = strings.loadingNormalMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ClipboardDetectedBanner(
    url: String,
    strings: AppStrings,
    onExtract: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.clipboardDetectedTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onExtract,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("clipboard_extract_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Text(strings.clipboardExtractAction)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clipboard_dismiss_button"),
                ) {
                    Text(strings.clipboardDismissAction)
                }
            }
        }
    }
}

@Composable
private fun EmptyGuideCard(strings: AppStrings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = strings.emptyTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = strings.emptyDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
