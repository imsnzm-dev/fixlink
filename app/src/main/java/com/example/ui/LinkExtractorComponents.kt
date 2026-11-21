package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.CurlResolutionResult
import com.example.network.RedirectHop
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalHighlight
import com.example.ui.theme.TerminalRedirect
import com.example.ui.theme.TerminalSuccess
import com.example.ui.theme.TerminalText

@Composable
fun DirectLinkResultCard(
    result: CurlResolutionResult,
    strings: AppStrings,
    isDeveloperMode: Boolean,
    onDownloadInApp: (CurlResolutionResult) -> Unit,
    onCopyLink: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directUrl = result.directVideoUrl ?: return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = strings.resultCardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.extension?.let { ext ->
                        BadgePill(
                            text = ext,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (isDeveloperMode && result.finalStatusCode > 0) {
                        BadgePill(
                            text = "${result.finalStatusCode} OK",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    result.filename?.let { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    val metaParts = buildList {
                        result.fileSizeFormatted?.let { add(strings.resultSizeLabel(it)) }
                        if (isDeveloperMode && result.hops.isNotEmpty()) {
                            add(strings.resultHopsLabel(result.hops.size))
                        }
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString("  •  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = TerminalBg,
                border = BorderStroke(1.dp, TerminalBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer {
                        Text(
                            text = directUrl,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 12.5.sp,
                                lineHeight = 20.sp,
                            ),
                            color = TerminalSuccess,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                                .testTag("extracted_url_text"),
                        )
                    }
                }
            }

            // Primary Built-in Download Manager CTA
            Button(
                onClick = { onDownloadInApp(result) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("download_in_app_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.actionDownloadHere,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // Secondary Actions Row: Copy, Open External, Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { onCopyLink(directUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("copy_link_button"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = strings.cdCopyResult,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.actionCopyLink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = { onOpenLink(directUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("open_link_button"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = strings.cdOpenResult,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.actionOpenLink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(48.dp),
                ) {
                    IconButton(
                        onClick = { onShareLink(directUrl) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("share_link_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = strings.cdShareResult,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RedirectChainCard(
    hops: List<RedirectHop>,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    if (hops.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = strings.sectionRedirectChain,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            hops.forEach { hop ->
                val isSuccess = hop.statusCode in 200..299
                val badgeBg = if (isSuccess) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
                val badgeFg = if (isSuccess) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BadgePill(
                        text = "${hop.step} • ${hop.statusCode}",
                        containerColor = badgeBg,
                        contentColor = badgeFg,
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = hop.requestUrl,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.5.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalTraceCard(
    verboseLog: String,
    strings: AppStrings,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopyLog: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (verboseLog.isBlank()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = TerminalBg,
        ),
        border = BorderStroke(1.dp, TerminalBorder),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("toggle_log_button"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = strings.cdToggleLog,
                        tint = TerminalHighlight,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = strings.sectionTerminalLog,
                        style = MaterialTheme.typography.titleSmall,
                        color = TerminalText,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isExpanded) {
                        TextButton(
                            onClick = { onCopyLog(verboseLog) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("copy_log_button"),
                        ) {
                            Text(
                                text = strings.actionCopyLog,
                                color = TerminalHighlight,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = strings.cdToggleLog,
                        tint = TerminalText,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val horizontalScroll = rememberScrollState()
                    val styledLog = buildAnnotatedString {
                        verboseLog.lines().forEachIndexed { idx, line ->
                            if (idx > 0) append('\n')
                            val color = when {
                                line.startsWith("> ") || line.startsWith("$ ") -> TerminalHighlight
                                line.startsWith("< HTTP/") -> TerminalSuccess
                                line.startsWith("< location:", ignoreCase = true) -> TerminalRedirect
                                line.startsWith("* Issue another request") -> TerminalRedirect
                                else -> TerminalText
                            }
                            withStyle(SpanStyle(color = color)) {
                                append(line)
                            }
                        }
                    }

                    SelectionContainer {
                        Text(
                            text = styledLog,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.5.sp,
                                lineHeight = 18.sp,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(horizontalScroll)
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionHistorySection(
    history: List<CurlResolutionResult>,
    strings: AppStrings,
    onSelectItem: (CurlResolutionResult) -> Unit,
    onCopyLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = strings.recentLinksTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        history.forEach { item ->
            val directUrl = item.directVideoUrl ?: return@forEach
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelectItem(item) },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = item.filename ?: directUrl,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val subMeta = listOfNotNull(item.extension, item.fileSizeFormatted).joinToString(" • ")
                        if (subMeta.isNotEmpty()) {
                            Text(
                                text = subMeta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    IconButton(
                        onClick = { onCopyLink(directUrl) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = strings.cdCopyResult,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BadgePill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = contentColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
