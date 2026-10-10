// The counting half of the download manager's summary line, ported upstream as
// an inline @Composable and split here (see DownloadManagerPanel.kt's
// [BatchSummary]) so the parameter sets the plurals are fed can be pinned
// without a device: what the test asserts is which resource each state asks
// for and with what count — the localized text itself stays a screenshot
// question.
package com.music.bitchord.ui

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadProgress
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.ui.components.BatchSummary
import com.music.bitchord.ui.components.batchSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadManagerSummaryTest {

    private fun song(id: String) =
        Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    /**
     * A [DownloadSession.State] built directly rather than driven through the
     * singleton: the summary reads nothing but the items, so the table can
     * name a batch shape outright instead of scripting its history.
     */
    private fun stateOf(vararg progress: DownloadProgress): DownloadSession.State =
        DownloadSession.State(
            items = progress.mapIndexed { index, p ->
                DownloadSession.Item(
                    videoId = "v$index",
                    song = song("v$index"),
                    progress = p,
                    sequence = index.toLong(),
                )
            },
        )

    @Test
    fun `an empty batch asks for nothing_downloading`() {
        assertEquals(BatchSummary.Empty, DownloadSession.State().batchSummary())
    }

    @Test
    fun `a batch still running counts whichever parts are alive`() {
        // Two queued, one mid-flight, one landed, one dead: all three plurals
        // asked for, each with its own count.
        assertEquals(
            BatchSummary.Counting(waiting = 3, finished = 1, failed = 1),
            stateOf(
                DownloadProgress.Queued,
                DownloadProgress.Queued,
                DownloadProgress.Running(0.4f),
                DownloadProgress.Done,
                DownloadProgress.Failed("nope"),
            ).batchSummary(),
        )
    }

    @Test
    fun `a running batch names only the parts it has`() {
        assertEquals(
            BatchSummary.Counting(waiting = 2, finished = 1, failed = null),
            stateOf(
                DownloadProgress.Done,
                DownloadProgress.Queued,
                DownloadProgress.Running(0f),
            ).batchSummary(),
        )
    }

    @Test
    fun `a lone queued track takes the singular waiting quantity`() {
        assertEquals(
            BatchSummary.Counting(waiting = 1, finished = null, failed = null),
            stateOf(DownloadProgress.Queued).batchSummary(),
        )
    }

    @Test
    fun `a lone failure is named without a done count`() {
        assertEquals(
            BatchSummary.Counting(waiting = null, finished = null, failed = 1),
            stateOf(DownloadProgress.Failed("nope")).batchSummary(),
        )
    }

    @Test
    fun `a quiet batch with a failure keeps the counting line`() {
        // Not busy — nothing left to wait for — but "1 done · 1 failed" is the
        // only line that says what needs acting on; the all-done sentence
        // would paper over the one track that did not arrive.
        assertEquals(
            BatchSummary.Counting(waiting = null, finished = 1, failed = 1),
            stateOf(DownloadProgress.Done, DownloadProgress.Failed("nope")).batchSummary(),
        )
    }

    @Test
    fun `a quiet failure-free batch is the all-done sentence`() {
        assertEquals(
            BatchSummary.AllDone(finished = 3),
            stateOf(DownloadProgress.Done, DownloadProgress.Done, DownloadProgress.Done)
                .batchSummary(),
        )
    }

    @Test
    fun `a single finished track takes the singular all-done quantity`() {
        // The count doubles as the plural's quantity selector: 1 must reach
        // the resource call as 1.
        assertEquals(
            BatchSummary.AllDone(finished = 1),
            stateOf(DownloadProgress.Done).batchSummary(),
        )
    }
}
