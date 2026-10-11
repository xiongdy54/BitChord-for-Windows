package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.add_addon
import com.music.bitchord.desktop.resources.add_addon_detail
import com.music.bitchord.desktop.resources.always_on
import com.music.bitchord.desktop.resources.cancel
import com.music.bitchord.desktop.resources.checking
import com.music.bitchord.desktop.resources.close
import com.music.bitchord.desktop.resources.enable_jiosaavn
import com.music.bitchord.desktop.resources.jiosaavn_mismatch_warning
import com.music.bitchord.desktop.resources.remove_source
import com.music.bitchord.desktop.resources.save
import com.music.bitchord.desktop.resources.source
import com.music.bitchord.desktop.resources.source_matching
import com.music.bitchord.desktop.resources.source_setup_required
import com.music.bitchord.desktop.resources.source_unreachable
import com.music.bitchord.desktop.resources.sources_order_footer
import com.music.bitchord.desktop.resources.sources_order_header
import com.music.bitchord.desktop.resources.test
import com.music.bitchord.desktop.resources.testing
import com.music.bitchord.desktop.resources.upgrade_length_slack
import com.music.bitchord.desktop.resources.upgrade_length_slack_footer
import com.music.bitchord.desktop.resources.upgrade_length_slack_subtitle
import com.music.bitchord.ui.components.PillTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Sources family inside the desktop's settings dialog — upstream's
 * `SourcesScreen.kt` folded into the modal shape the settings page uses.
 *
 * Same semantics, one adaptation: the reorder is a pair of arrow buttons per
 * row rather than a long-press drag, because a settings dialog is a poor
 * place to invent a drag surface and the underlying call
 * ([SourceRegistry.reorderAddons]) takes an ordered id list either way.
 */
@Composable
internal fun SourcesSettingsGroup(onEditSource: (SourceConfig?) -> Unit) {
    val configs by SourceRegistry.configs.collectAsState()

    GroupHeaderLabel(stringResource(Res.string.source))
    Text(
        text = stringResource(Res.string.sources_order_header),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))

    val userAdded = configs.filter { it.kind.isUserAdded }
    val fixed = configs.filterNot { it.kind.isUserAdded }.sortedBy { it.kind.rank }
    val ordered = userAdded + fixed
    val health = rememberSourceHealth(ordered)

    ordered.forEachIndexed { index, config ->
        SourceRow(
            position = index + 1,
            config = config,
            health = health[config.id],
            isFirst = index == 0,
            isLast = index == ordered.size - 1,
            onToggle = { enabled -> SourceRegistry.setEnabled(config.id, enabled) },
            onMoveUp = { moveWithin(userAdded, config, -1) },
            onMoveDown = { moveWithin(userAdded, config, +1) },
            onEdit = { onEditSource(config) },
        )
        if (index != ordered.size - 1) GroupDividerLine()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEditSource(null) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = stringResource(Res.string.add_addon),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(Res.string.add_addon),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(Res.string.add_addon_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Text(
        text = stringResource(Res.string.sources_order_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    GroupDividerLine()
    GroupHeaderLabel(stringResource(Res.string.source_matching))
    UpgradeLengthSlackRow()
}

/**
 * Steps [config] one slot up or down inside the user-added block, through the
 * same ordered-id call a drag would end in.
 */
private fun moveWithin(userAdded: List<SourceConfig>, config: SourceConfig, step: Int) {
    val index = userAdded.indexOfFirst { it.id == config.id }
    val target = index + step
    if (index < 0 || target < 0 || target >= userAdded.size) return
    val ids = userAdded.map { it.id }.toMutableList()
    ids[index] = ids[target].also { ids[target] = ids[index] }
    SourceRegistry.reorderAddons(ids)
}

/**
 * Health for every config that owns a server, re-asked whenever one's address
 * changes. An addon whose manifest names itself also has that name written
 * back into its label, so the row stops saying "192.168.0.4" once the server
 * has said what it is.
 */
@Composable
private fun rememberSourceHealth(configs: List<SourceConfig>): Map<String, SourceHealth?> {
    var health by remember { mutableStateOf<Map<String, SourceHealth?>>(emptyMap()) }
    val key = configs.filter { it.kind.needsServer }.joinToString("|") { "${it.id}@${it.baseUrl}" }
    LaunchedEffect(key) {
        configs.filter { it.kind.needsServer }.forEach { config ->
            health = health + (config.id to null) // null = "checking"
            val instance = SourceRegistry.instance(config.id) ?: return@forEach
            val answer = runCatching { instance.health() }.fold(
                onSuccess = { it },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    SourceHealth.Unreachable(failure.message ?: "no answer")
                },
            )
            health = health + (config.id to answer)
            if (instance is com.music.bitchord.data.sources.AddonSource) {
                val name = runCatching { withContext(Dispatchers.IO) { instance.manifestName() } }.getOrNull()
                if (!name.isNullOrBlank() && config.label.isBlank()) {
                    SourceRegistry.update(config.copy(label = name))
                }
            }
        }
    }
    return health
}

@Composable
private fun SourceRow(
    position: Int,
    config: SourceConfig,
    health: SourceHealth?,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
) {
    val (icon, canReorder) = when (config.kind) {
        SourceKind.JIOSAAVN -> Icons.Rounded.GraphicEq to false
        SourceKind.YOUTUBE -> Icons.Rounded.PlayCircle to false
        else -> Icons.Rounded.Extension to true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = config.kind.isUserAdded, onClick = onEdit)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Icon(
            icon,
            contentDescription = config.kind.label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = config.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine(config, health),
                style = MaterialTheme.typography.bodySmall,
                color = if (health is SourceHealth.Rejected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canReorder) {
            IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (config.kind == SourceKind.YOUTUBE) {
            Text(
                text = stringResource(Res.string.always_on),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val checked = config.enabled
            var confirming by remember(config.id) { mutableStateOf(false) }
            Switch(
                checked = checked,
                onCheckedChange = { wanted ->
                    if (config.kind == SourceKind.JIOSAAVN && wanted) confirming = true else onToggle(wanted)
                },
            )
            if (confirming) {
                JioSaavnConfirmDialog(
                    onConfirm = {
                        confirming = false
                        SourceRegistry.setEnabled(config.id, true)
                    },
                    onDismiss = { confirming = false },
                )
            }
        }
    }
}

/** The second line of a row: what this source is, or what is wrong with it. */
@Composable
private fun statusLine(config: SourceConfig, health: SourceHealth?): String = when {
    !config.isComplete -> stringResource(Res.string.source_setup_required)
    config.kind == SourceKind.JIOSAAVN -> stringResource(Res.string.jiosaavn_mismatch_warning)
    health is SourceHealth.Ok -> listOfNotNull(
        health.detail,
        config.kind.labels.take(3).joinToString(" · "),
    ).joinToString(" · ")
    health is SourceHealth.Rejected -> health.reason
    health is SourceHealth.Unreachable -> stringResource(Res.string.source_unreachable, health.reason)
    config.kind.needsServer -> stringResource(Res.string.checking)
    else -> config.kind.labels.take(3).joinToString(" · ")
}

@Composable
private fun JioSaavnConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.enable_jiosaavn),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.jiosaavn_mismatch_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                    TextButton(onClick = onConfirm) { Text(stringResource(Res.string.save)) }
                }
            }
        }
    }
}

/**
 * The one row of upstream's "Source matching" group: how far a replacement's
 * runtime may drift and still be the same recording.
 */
@Composable
private fun UpgradeLengthSlackRow() {
    val seconds by AppSettings.upgradeLengthSlackSeconds.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.upgrade_length_slack),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$seconds s",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(Res.string.upgrade_length_slack_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = seconds.toFloat(),
            onValueChange = { AppSettings.setUpgradeLengthSlackSeconds(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
        )
        Text(
            text = stringResource(Res.string.upgrade_length_slack_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Add or edit a source that has an address — upstream's `SourceEditorAlert`,
 * in the desktop dialog shape.
 *
 * Test is offered rather than required: a server that happens to be asleep is
 * still worth saving, and refusing to store it until it answers would make
 * setting one up impossible. What Test buys is the difference between "not
 * answering" and "answering, but not with something this app can use".
 */
@Composable
internal fun SourceEditorDialog(
    existing: SourceConfig?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        SourceEditorPanel(existing = existing, onDismiss = onDismiss)
    }
}

/**
 * The editor's contents, split from its window the way [OutputDevicePanel]
 * is: a dialog inside the settings dialog is a window inside a window, which
 * the screenshot pipeline cannot see — the probe renders this panel inline.
 */
@Composable
internal fun SourceEditorPanel(
    existing: SourceConfig?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = existing == null || SourceRegistry.config(existing.id) == null
    val base = existing ?: SourceConfig(kind = SourceKind.ADDON)
    var baseUrl by remember(existing?.id) { mutableStateOf(base.baseUrl) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }

    /**
     * Identify what is at the URL, then say so — and store on Save only.
     * Both buttons run the same question: a Save that skipped identification
     * could store a config whose kind was guessed.
     */
    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val identified = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.identify(baseUrl.trim(), base.takeUnless { isNew }) }
                    .getOrElse { Result.failure(it) }
            }
            val found = identified.getOrNull()
            if (found == null) {
                statusIsGood = false
                status = identified.exceptionOrNull()?.message ?: "Unrecognised"
                busy = false
                return@launch
            }

            SourceRegistry.duplicateOf(found.baseUrl, exceptId = base.id.takeUnless { isNew })
                ?.let { duplicate ->
                    statusIsGood = false
                    status = "Already added as ${duplicate.displayName}"
                    busy = false
                    return@launch
                }

            val health = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.probeCandidate(found) }
            }
            statusIsGood = health.getOrNull() is SourceHealth.Ok
            status = health.getOrNull()?.let {
                when (it) {
                    is SourceHealth.Ok -> it.detail ?: "Connected"
                    is SourceHealth.Rejected -> it.reason
                    is SourceHealth.Unreachable -> it.reason
                }
            } ?: health.exceptionOrNull()?.message ?: "Unreachable"

            if (thenSave) {
                val stored = base.copy(
                    kind = found.kind,
                    baseUrl = found.baseUrl,
                    label = found.label,
                )
                if (isNew) SourceRegistry.add(stored) else SourceRegistry.update(stored)
                onDismiss()
                return@launch
            }
            busy = false
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier.width(440.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.source),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "https://my-addon.example.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PillTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        status = null
                    },
                    placeholder = "https://my-addon.example.com",
                    enabled = !busy,
                )
                status?.let { line ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusIsGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { run(thenSave = false) }, enabled = !busy && baseUrl.isNotBlank()) {
                        Text(stringResource(if (busy) Res.string.testing else Res.string.test))
                    }
                    Spacer(Modifier.weight(1f))
                    if (!isNew) {
                        TextButton(
                            onClick = {
                                existing?.let { SourceRegistry.remove(it.id) }
                                onDismiss()
                            },
                            enabled = !busy,
                        ) {
                            Text(
                                stringResource(Res.string.remove_source),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(stringResource(Res.string.close))
                    }
                    TextButton(onClick = { run(thenSave = true) }, enabled = !busy && baseUrl.isNotBlank()) {
                        Text(stringResource(Res.string.save))
                    }
                }
            }
        }
}

/** SettingsDialog's group primitives live private in that file; these mirror their look. */
@Composable
internal fun GroupHeaderLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
internal fun GroupDividerLine() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
