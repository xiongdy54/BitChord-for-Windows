// Ported from app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt.
//
// The sheet/panel split is the desktop's one structural change: upstream's
// ModalBottomSheet was hosted by MainActivity, and the phone bottom sheet's
// counterpart here is the window-level dialog (the established SettingsDialog
// shape), so the panel composable hosts its own [androidx.compose.ui.window.Dialog]
// and the shell only decides whether it is up. Everything else — the indicator's
// three states, the plurals, the four-state row, the counting summary — is the
// upstream file with the Android-only imports swapped: `collectAsState()` for
// `collectAsStateWithLifecycle()` (a JVM app has no stopped lifecycle to honour)
// and the merged compose-resources accessors for `R.string`.

package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.all_songs_downloaded
import com.music.bitchord.desktop.resources.cancel
import com.music.bitchord.desktop.resources.cancel_all
import com.music.bitchord.desktop.resources.clear
import com.music.bitchord.desktop.resources.download_done_count
import com.music.bitchord.desktop.resources.download_failed_count
import com.music.bitchord.desktop.resources.download_waiting_count
import com.music.bitchord.desktop.resources.downloading_progress
import com.music.bitchord.desktop.resources.downloads
import com.music.bitchord.desktop.resources.downloads_failed_count
import com.music.bitchord.desktop.resources.downloads_finished
import com.music.bitchord.desktop.resources.downloads_progress
import com.music.bitchord.desktop.resources.download_paused
import com.music.bitchord.desktop.resources.nothing_downloading
import com.music.bitchord.desktop.resources.pause
import com.music.bitchord.desktop.resources.queued
import com.music.bitchord.desktop.resources.resume
import com.music.bitchord.desktop.resources.retry
import com.music.bitchord.desktop.resources.saved_to_bitchord_folder
import com.music.bitchord.desktop.resources.starting
import com.music.bitchord.desktop.resources.unknown_artist
import com.music.bitchord.download.DownloadProgress
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The download indicator in the toolbar, right of the volume control — the
 * desktop seat that corresponds to upstream's top-bar slot beside the account
 * photo.
 *
 * Absent until something is actually downloading, and absent again once the
 * user has looked at the result — neither of which is this composable's
 * decision. [DownloadSession.State.visible] owns both, because "should this be
 * on screen" is a question about a batch of work rather than about a bar, and
 * the same answer has to hold whichever page is showing.
 *
 * It draws the batch's overall progress rather than the running track's. The
 * track is what the notification reports on; from here the interesting number
 * is how much of the *album* is left, and a ring that restarts from zero forty
 * times says nothing about that. Once the queue is quiet the ring is dropped
 * for a tick or a warning — a full circle and a finished job look identical,
 * and only one of them is worth walking over to.
 */
@Composable
fun TopBarDownloadButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val session by DownloadSession.state.collectAsState()

    val haptics = rememberHaptics()
    // Animated, because the fraction lands in steps — one track at a time, plus
    // whatever the running one reports — and a ring that jumps in twenty-fifths
    // reads as a stutter rather than as progress.
    val progress by animateFloatAsState(
        targetValue = session.fraction,
        animationSpec = tween(300),
        label = "downloadRingProgress",
    )
    val failed = session.failed > 0
    val tint by animateColorAsState(
        targetValue = when {
            failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(220),
        label = "downloadRingTint",
    )

    AnimatedVisibility(
        visible = session.visible,
        modifier = modifier,
        // Open the slot first; the glyph arrives as the pill approaches its
        // new width. Siblings move with the expanding slot and make room rather
        // than jumping directly to their final positions.
        enter = expandHorizontally(
            animationSpec = tween(260),
            expandFrom = Alignment.End,
        ) + fadeIn(
            animationSpec = tween(durationMillis = 160, delayMillis = 100),
        ),
        exit = fadeOut(
            animationSpec = tween(120),
        ) + shrinkHorizontally(
            animationSpec = tween(durationMillis = 220, delayMillis = 60),
            shrinkTowards = Alignment.End,
        ),
    ) {
        IconButton(
            onClick = {
                haptics.play(Haptic.Select)
                onClick()
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (session.busy) {
                    CircularProgressIndicator(
                        // Never quite zero: a ring pinned at nothing reads as
                        // stalled where the first sliver reads as starting.
                        progress = { progress.coerceAtLeast(0.02f) },
                        modifier = Modifier.size(RING_SIZE),
                        color = tint,
                        trackColor = tint.copy(alpha = 0.22f),
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                }
                Icon(
                    imageVector = when {
                        failed -> Icons.Rounded.ErrorOutline
                        // A bare arrow inside the ring, not the tray-and-arrow: the
                        // ring is already saying "downloading", and a second glyph
                        // that says it too is detail nobody can resolve at 15dp.
                        session.busy -> Icons.Rounded.ArrowDownward
                        else -> Icons.Rounded.DownloadDone
                    },
                    contentDescription = when {
                        session.busy -> stringResource(
                            Res.string.downloads_progress,
                            (session.fraction * 100).toInt(),
                        )
                        failed -> pluralStringResource(
                            Res.plurals.downloads_failed_count,
                            session.failed,
                            session.failed,
                        )
                        else -> stringResource(Res.string.downloads_finished)
                    },
                    tint = tint,
                    modifier = Modifier.size(if (session.busy) GLYPH_IN_RING else GLYPH_SIZE),
                )
            }
        }
    }
}

/**
 * The list behind that indicator: every track asked for this session, what
 * became of it, and a way out of the ones still going.
 *
 * A list rather than a single line because the thing being reported on is a
 * batch. `SongActionsSheet`'s download row already answers "what about *this*
 * song" perfectly well and is the right size for that question; the question
 * here is the one it cannot answer — forty tracks were asked for, which of them
 * arrived — and that has as many answers as there were tracks.
 *
 * Rows carry the cover, the title and the credit because a filename is not how
 * anybody remembers a song, and because a batch download is precisely when a
 * user cannot tell from a name whether the right thing is being fetched: the
 * track that fails is one of forty and the only way to recognise it is to see it.
 *
 * Upstream this was a bottom sheet hosted by the activity; here it hosts its
 * own window-level dialog, the desktop counterpart for a phone's global panel
 * and the shape every other window-level surface in this build takes.
 *
 * @param onDismiss closes the panel. Called by the header's own control rather
 *   than left to the drag, so there is something obvious to press once the list
 *   is read — and the host marks the batch seen on the way out, which is what
 *   takes the indicator down.
 */
@Composable
fun DownloadManagerPanel(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = PANEL_WIDTH),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            PanelBody(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun PanelBody(onDismiss: () -> Unit) {
    val session by DownloadSession.state.collectAsState()
    // Newest ask last, the order the queue will actually reach them in.
    val items = remember(session.items) { session.items.sortedBy { it.sequence } }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.downloads),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = session.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (session.failed > 0 && !session.busy) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (session.busy) {
                TextButton(
                    onClick = {
                        // Only what is still going. Cancelling a finished row
                        // would drop it from the list, which is the one thing
                        // this panel exists to still be showing.
                        items.filterNot { it.progress.settled }
                            .forEach { Downloads.cancel(it.videoId) }
                    },
                ) {
                    Text(stringResource(Res.string.cancel_all))
                }
            } else {
                TextButton(
                    onClick = {
                        DownloadSession.clear()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(Res.string.clear))
                }
            }
        }

        // The batch's own bar, under the heading it belongs to. The per-row bars
        // below are about one track each; this is the one that answers "how much
        // longer", which is what someone opening this panel mid-album wants.
        if (session.busy) {
            LinearProgressIndicator(
                progress = { session.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Capped rather than left to grow: a hundred-track playlist would
        // otherwise be a panel that covers the screen and has to be scrolled
        // back up before anything else can be reached.
        LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
            items(items, key = { it.videoId }) { item ->
                DownloadManagerRow(
                    item = item,
                    onCancel = { Downloads.cancel(item.videoId) },
                    onRetry = { Downloads.enqueue(item.song, item.from) },
                    onPause = { Downloads.pause(item.videoId) },
                    onResume = { Downloads.resume(item.videoId) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One track: its cover, what it is, and where it has got to. */
@Composable
private fun DownloadManagerRow(
    item: DownloadSession.Item,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val progress = item.progress
    val failed = progress as? DownloadProgress.Failed
    val paused = progress as? DownloadProgress.Paused

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ART_SIZE), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = item.song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(ART_SIZE)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            // A finished or failed track is stated over its own cover rather
            // than in a fourth column: the list is scanned for the odd one out,
            // and a mark on the artwork is what the eye actually lands on.
            if (progress.settled) {
                Box(
                    modifier = Modifier
                        .size(ART_SIZE)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (failed != null) {
                            Icons.Rounded.ErrorOutline
                        } else {
                            Icons.Rounded.DownloadDone
                        },
                        contentDescription = null,
                        tint = if (failed != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.White
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = item.song,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                // The release is worth naming where there is one: in a list of
                // forty rows off three albums, the credit alone does not say
                // which batch a row belongs to.
                text = listOfNotNull(
                    item.song.artist.takeIf { it.isNotBlank() },
                    item.from?.takeIf { it.isNotBlank() && it != item.song.artist },
                ).joinToString(" · ").ifBlank { stringResource(Res.string.unknown_artist) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            when (progress) {
                is DownloadProgress.Queued -> RowStatus(stringResource(Res.string.queued))
                is DownloadProgress.Paused -> RowStatus(stringResource(Res.string.download_paused))
                is DownloadProgress.Running -> {
                    LinearProgressIndicator(
                        // Indeterminate until the first response names a
                        // length — a bar frozen at nothing reads as broken.
                        progress = { progress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(3.dp))
                    RowStatus(
                        if (progress.fraction > 0f) {
                            stringResource(
                                Res.string.downloading_progress,
                                (progress.fraction * 100).toInt(),
                            )
                        } else {
                            stringResource(Res.string.starting)
                        },
                    )
                }
                is DownloadProgress.Done -> RowStatus(
                    stringResource(Res.string.saved_to_bitchord_folder),
                )
                is DownloadProgress.Failed ->
                    RowStatus(progress.reason, MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.width(8.dp))
        // The row's controls, which follow its state: a running or queued track
        // can be held (and stopped), a held one can be continued (and given up
        // on), a failed one can be asked for again, and a finished one needs
        // nothing at all. The pause/resume pair arrived with slice 20 — the
        // cancel beside it is the upstream control, kept so a hold never has
        // to become the only way out.
        when {
            failed != null -> RowAction(Icons.Rounded.Refresh, stringResource(Res.string.retry), onRetry)
            paused != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RowAction(Icons.Rounded.PlayArrow, stringResource(Res.string.resume), onResume)
                RowAction(Icons.Rounded.Close, stringResource(Res.string.cancel), onCancel)
            }
            !progress.settled -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RowAction(Icons.Rounded.Pause, stringResource(Res.string.pause), onPause)
                RowAction(Icons.Rounded.Close, stringResource(Res.string.cancel), onCancel)
            }
            else -> Spacer(Modifier.width(36.dp))
        }
    }
}

@Composable
private fun RowStatus(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * What the summary line asks resources for, in the upstream composable's own
 * terms, but as data rather than as a string — the desktop's one extraction
 * from upstream's [summary].
 *
 * Upstream built the whole line inside a `@Composable`, so the counting logic
 * was welded to the resource calls and could only be tested on a device. This
 * is the same computation split at the seam the plurals sit on: the pure half
 * ([batchSummary]) decides *which* plurals get asked for and with which counts,
 * and the composable half ([summary]) turns that into text. The unit test
 * asserts the parameter sets — the localized strings themselves stay a
 * screenshot question.
 */
internal sealed interface BatchSummary {

    /** Ask for `nothing_downloading` — the list is empty. */
    data object Empty : BatchSummary

    /**
     * The counting plurals, in upstream's order (waiting, done, failed),
     * joined with " · ". A part whose count is zero is simply absent — the
     * line names only what there is to name.
     */
    data class Counting(val waiting: Int?, val finished: Int?, val failed: Int?) : BatchSummary

    /** Ask for `all_songs_downloaded`, told how many arrived. */
    data class AllDone(val finished: Int) : BatchSummary
}

/**
 * The one line under the heading — what state the batch as a whole is in.
 *
 * Counts rather than a percentage: the panel already draws the percentage as a
 * bar, and what a percentage cannot say is that thirty-nine of forty arrived
 * and one did not, which is the only outcome anybody needs to act on.
 *
 * The `all_songs_downloaded` branch is the quiet, failure-free batch: the
 * counting line would read "N done", which says what happened but not that
 * there is nothing left to wait for — the sentence a returning user wants is
 * the one that says the errand is finished. Any failure at all, quiet or not,
 * keeps the counting line, because "1 failed" is the part that needs reading.
 */
internal fun DownloadSession.State.batchSummary(): BatchSummary {
    val waiting = waiting.takeIf { it > 0 }
    val finished = finished.takeIf { it > 0 }
    val failed = failed.takeIf { it > 0 }
    return when {
        waiting == null && finished == null && failed == null -> BatchSummary.Empty
        busy || failed != null -> BatchSummary.Counting(waiting, finished, failed)
        // Not busy, nothing failed, and yet a part exists: every item is Done.
        // (`Empty` above already took the empty list, so `finished` is set.)
        else -> BatchSummary.AllDone(finished ?: items.size)
    }
}

@Composable
private fun DownloadSession.State.summary(): String = when (val spec = batchSummary()) {
    BatchSummary.Empty -> stringResource(Res.string.nothing_downloading)
    is BatchSummary.Counting -> listOfNotNull(
        spec.waiting?.let { pluralStringResource(Res.plurals.download_waiting_count, it, it) },
        spec.finished?.let { pluralStringResource(Res.plurals.download_done_count, it, it) },
        spec.failed?.let { pluralStringResource(Res.plurals.download_failed_count, it, it) },
    ).joinToString(" · ")
    is BatchSummary.AllDone -> pluralStringResource(
        Res.plurals.all_songs_downloaded,
        spec.finished,
        spec.finished,
    )
}

/** The progress ring in the bar, sized to sit in the toolbar's control row. */
private val RING_SIZE = 26.dp

/** The glyph inside that ring, small enough to leave the stroke clear. */
private val GLYPH_IN_RING = 15.dp

/** With no ring around it, the glyph is a normal bar icon. */
private val GLYPH_SIZE = 22.dp

/** A row's cover: under a track row's 52dp, since this is a status list. */
private val ART_SIZE = 44.dp

/** How much of the screen the list may take before it scrolls instead. */
private val LIST_MAX_HEIGHT = 380.dp

/**
 * The panel's width cap — the settings dialog's own ceiling, since both are
 * window-level decision surfaces and one width keeps them reading as a family.
 * The height needs no cap: the list's own [LIST_MAX_HEIGHT] bounds the panel.
 */
private val PANEL_WIDTH = 420.dp
