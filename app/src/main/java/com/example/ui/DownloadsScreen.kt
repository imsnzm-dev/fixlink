package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.download.DownloadEngine
import com.example.download.DownloadSaveLocation
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.PendingDownloadRequest
import com.example.download.QueueConcurrency
import com.example.network.VideoLinkExtractor
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun DownloadsScreen(
    tasks: List<DownloadTask>,
    filter: DownloadFilter,
    concurrencyLimit: QueueConcurrency,
    connectionsPerDownload: Int,
    saveLocation: DownloadSaveLocation,
    strings: AppStrings,
    onFilterSelected: (DownloadFilter) -> Unit,
    onConcurrencyChanged: (QueueConcurrency) -> Unit,
    onConnectionsChanged: (Int) -> Unit,
    onPickSaveFolder: () -> Unit,
    onResetSaveFolder: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onMoveToTop: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onCopyTaskUrl: (String) -> Unit,
    onOpenTaskFile: (DownloadTask) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearCompleted: () -> Unit,
    onAddManualDownload: (url: String, fileName: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQueueSheet by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val activeTasks = tasks.filter { it.isActive }
    val queuedCount = tasks.count { it.status == DownloadStatus.QUEUED }
    val totalSpeed = activeTasks.sumOf { it.speedBytesPerSec }
    val formattedSpeed = if (totalSpeed > 0L) {
        "${VideoLinkExtractor.formatByteSize(totalSpeed) ?: "0 B"}/s"
    } else {
        "0 KB/s"
    }

    val filteredTasks = remember(tasks, filter) {
        filterDownloadTasks(tasks, filter)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header + Queue Summary
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.downloadsTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = strings.downloadsSubtitle(activeTasks.size, queuedCount, formattedSpeed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedButton(
                        onClick = { showQueueSheet = !showQueueSheet },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("queue_settings_toggle"),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.queueSettingsLabel)
                    }
                }
            }

            // Expandable Queue, Parts (1-8) & Save Location Control Panel
            if (showQueueSheet) {
                item {
                    QueueSettingsCard(
                        concurrencyLimit = concurrencyLimit,
                        connectionsPerDownload = connectionsPerDownload,
                        saveLocation = saveLocation,
                        strings = strings,
                        onConcurrencyChanged = onConcurrencyChanged,
                        onConnectionsChanged = onConnectionsChanged,
                        onPickSaveFolder = onPickSaveFolder,
                        onResetSaveFolder = onResetSaveFolder,
                    )
                }
            }

            // Global Queue Actions + Filters
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onResumeAll,
                            enabled = tasks.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("resume_all_button"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.actionResumeAll, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        OutlinedButton(
                            onClick = onPauseAll,
                            enabled = tasks.any { it.isActive || it.status == DownloadStatus.QUEUED },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("pause_all_button"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.actionPauseAll, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        if (tasks.any { it.status == DownloadStatus.COMPLETED }) {
                            IconButton(
                                onClick = onClearCompleted,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("clear_completed_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CleaningServices,
                                    contentDescription = strings.actionClearDone,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chips = listOf(
                            DownloadFilter.ALL to strings.filterAll,
                            DownloadFilter.ACTIVE to strings.filterActive,
                            DownloadFilter.QUEUED to strings.filterQueued,
                            DownloadFilter.COMPLETED to strings.filterCompleted,
                        )
                        items(chips) { (filterType, label) ->
                            FilterChip(
                                selected = filter == filterType,
                                onClick = { onFilterSelected(filterType) },
                                label = { Text(label) },
                                modifier = Modifier.heightIn(min = 40.dp),
                            )
                        }
                    }
                }
            }

            if (filteredTasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = strings.downloadsEmptyTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = strings.downloadsEmptyDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(filteredTasks, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        saveLocation = saveLocation,
                        strings = strings,
                        onPause = { onPauseTask(task.id) },
                        onResume = { onResumeTask(task.id) },
                        onMoveToTop = { onMoveToTop(task.id) },
                        onDelete = { onDeleteTask(task.id) },
                        onCopyUrl = { onCopyTaskUrl(task.url) },
                        onOpen = { onOpenTaskFile(task) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_download_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = strings.cdAddDownload,
            )
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            strings = strings,
            onDismiss = { showAddDialog = false },
            onConfirm = { url, name ->
                onAddManualDownload(url, name)
                showAddDialog = false
            },
        )
    }
}

@Composable
fun QueueSettingsCard(
    concurrencyLimit: QueueConcurrency,
    connectionsPerDownload: Int,
    saveLocation: DownloadSaveLocation,
    strings: AppStrings,
    onConcurrencyChanged: (QueueConcurrency) -> Unit,
    onConnectionsChanged: (Int) -> Unit,
    onPickSaveFolder: () -> Unit,
    onResetSaveFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Save Destination Folder
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = strings.saveLocationTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = if (saveLocation.isCustom) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            text = if (saveLocation.isCustom) {
                                strings.saveLocationCustomBadge
                            } else {
                                strings.saveLocationDefaultBadge
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (saveLocation.isCustom) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = saveLocation.displayPath,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("save_location_path"),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onPickSaveFolder,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("change_save_folder_button"),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.actionChangeFolder, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    if (saveLocation.isCustom) {
                        TextButton(
                            onClick = onResetSaveFolder,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("reset_save_folder_button"),
                        ) {
                            Text(strings.actionResetFolder)
                        }
                    }
                }

                if (!saveLocation.isCustom) {
                    Text(
                        text = strings.saveLocationHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // 2. Simultaneous Downloads Queue Limit
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.concurrencyTitle,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = strings.concurrencyDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    QueueConcurrency.ONE to strings.concurrencyOne,
                    QueueConcurrency.TWO to strings.concurrencyTwo,
                    QueueConcurrency.THREE to strings.concurrencyThree,
                    QueueConcurrency.ALL to strings.concurrencyAll,
                )
                options.forEachIndexed { idx, (option, label) ->
                    SegmentedButton(
                        selected = concurrencyLimit == option,
                        onClick = { onConcurrencyChanged(option) },
                        shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("concurrency_option_${option.code}"),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // 3. Parallel Parts per File (1 to 8)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.connectionsTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = strings.connectionsDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (connectionsPerDownload > DownloadEngine.MIN_PARTS) {
                                    onConnectionsChanged(connectionsPerDownload - 1)
                                }
                            },
                            enabled = connectionsPerDownload > DownloadEngine.MIN_PARTS,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("parts_decrement_button"),
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "-")
                        }

                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "${connectionsPerDownload}x",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("parts_current_value"),
                            )
                        }

                        IconButton(
                            onClick = {
                                if (connectionsPerDownload < DownloadEngine.MAX_PARTS) {
                                    onConnectionsChanged(connectionsPerDownload + 1)
                                }
                            },
                            enabled = connectionsPerDownload < DownloadEngine.MAX_PARTS,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("parts_increment_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "+")
                        }
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val presets = listOf(1, 2, 4, 6, 8)
                    presets.forEachIndexed { idx, count ->
                        SegmentedButton(
                            selected = connectionsPerDownload == count,
                            onClick = { onConnectionsChanged(count) },
                            shape = SegmentedButtonDefaults.itemShape(idx, presets.size),
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .testTag("connections_option_$count"),
                        ) {
                            Text("${count}x")
                        }
                    }
                }

                Text(
                    text = strings.partsRangeHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    saveLocation: DownloadSaveLocation,
    strings: AppStrings,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMoveToTop: () -> Unit,
    onDelete: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpen: () -> Unit,
) {
    val (statusLabel, statusBg, statusFg) = when (task.status) {
        DownloadStatus.QUEUED -> Triple(
            strings.statusQueued,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.CONNECTING -> Triple(
            strings.statusConnecting,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        DownloadStatus.DOWNLOADING -> Triple(
            strings.statusDownloading,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        DownloadStatus.PAUSED -> Triple(
            strings.statusPaused,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.COMPLETED -> Triple(
            strings.statusCompleted,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DownloadStatus.FAILED -> Triple(
            strings.statusFailed,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (task.status == DownloadStatus.DOWNLOADING) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else if (task.status == DownloadStatus.COMPLETED) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Top badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    task.extension?.let { ext ->
                        BadgePill(
                            text = ext,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (task.connectionCount > 1 && task.status != DownloadStatus.COMPLETED) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
                        ) {
                            Text(
                                text = strings.partsBadge(task.segments.size.takeIf { it > 0 } ?: task.connectionCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (task.supportsRange && task.status != DownloadStatus.COMPLETED) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        ) {
                            Text(
                                text = strings.badgeResumable,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Surface(shape = CircleShape, color = statusBg) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // Filename
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Progress Bar (Multi-part FDM visual or single bar)
            if (task.status == DownloadStatus.CONNECTING || (task.status == DownloadStatus.DOWNLOADING && task.totalBytes <= 0L)) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                )
            } else if (task.status != DownloadStatus.COMPLETED) {
                if (task.segments.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        task.segments.forEach { seg ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(seg.progressFraction)
                                        .background(
                                            if (seg.isComplete) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        ),
                                )
                            }
                        }
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { task.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }
            }

            // Metrics row (downloaded/total • percent • speed • ETA)
            val sizeSummary = buildString {
                append(task.formattedDownloaded())
                task.formattedTotal()?.let { total ->
                    append(" / ")
                    append(total)
                }
            }
            val rightStats = buildList {
                if (task.totalBytes > 0L && task.status != DownloadStatus.COMPLETED) {
                    add("${task.progressPercent}%")
                }
                if (task.status == DownloadStatus.DOWNLOADING) {
                    add(task.formattedSpeed())
                    task.formattedEta()?.let { add("ETA $it") }
                }
            }.joinToString(" • ")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sizeSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rightStats.isNotEmpty()) {
                    Text(
                        text = rightStats,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (task.status == DownloadStatus.COMPLETED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (saveLocation.isCustom) {
                                strings.savedToCustomFolderNote(saveLocation.displayPath)
                            } else {
                                strings.savedToDownloadsNote
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (task.status == DownloadStatus.FAILED && !task.errorMessage.isNullOrBlank()) {
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Per-item action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (task.status) {
                    DownloadStatus.CONNECTING, DownloadStatus.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("pause_task_${task.id}"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionPause)
                        }
                    }

                    DownloadStatus.QUEUED -> {
                        OutlinedButton(
                            onClick = onMoveToTop,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("prioritize_task_${task.id}"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionPrioritize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(strings.actionPause)
                        }
                    }

                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                        Button(
                            onClick = onResume,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("resume_task_${task.id}"),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                imageVector = if (task.status == DownloadStatus.FAILED) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (task.status == DownloadStatus.FAILED) strings.actionRetry else strings.actionResume,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        Button(
                            onClick = onOpen,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("open_task_${task.id}"),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionPlayOpen)
                        }
                    }
                }

                IconButton(
                    onClick = onCopyUrl,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("copy_url_task_${task.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = strings.actionCopyUrl,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_task_${task.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = strings.actionDelete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
fun FileConflictDialog(
    request: PendingDownloadRequest,
    strings: AppStrings,
    onRenameAndDownload: (customFileName: String) -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editedName by remember(request) { mutableStateOf(request.suggestedUniqueFileName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = { Text(strings.conflictDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.conflictDialogMessage(request.originalFileName, request.folderDisplayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text(strings.conflictFileNameLabel) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("conflict_filename_input"),
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val finalName = editedName.trim().ifBlank { request.suggestedUniqueFileName }
                        onRenameAndDownload(finalName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("conflict_rename_button"),
                ) {
                    Text(strings.actionConflictRename)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOverwrite,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("conflict_overwrite_button"),
                    ) {
                        Text(strings.actionConflictOverwrite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("conflict_cancel_button"),
                    ) {
                        Text(strings.actionDialogCancel)
                    }
                }
            }
        },
    )
}

@Composable
private fun AddDownloadDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (url: String, fileName: String?) -> Unit,
) {
    var urlInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addDownloadTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text(strings.addDownloadUrlLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_download_url_input"),
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(strings.addDownloadNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = VideoLinkExtractor.extractInputUrl(urlInput)
                    if (clean != null) {
                        onConfirm(clean, nameInput.trim().takeIf { it.isNotBlank() })
                    }
                },
                enabled = VideoLinkExtractor.extractInputUrl(urlInput) != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(strings.actionStartDownload)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(strings.actionDialogCancel)
            }
        },
    )
}
