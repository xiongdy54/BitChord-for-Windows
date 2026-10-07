package com.music.bitchord.ui.components

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The right-click menu's whole surface, as a table over what the row carries
 * and whether it is the one playing. The menu is rebuilt on every open from
 * [songActions], so this table is the contract the user sees.
 */
class SongContextMenuTest {

    private fun song(albumId: String? = null, artistId: String? = null) = Song(
        videoId = "video-1",
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        albumId = albumId,
        artistId = artistId,
    )

    private val fullMenu = listOf(
        SongAction.PlayNext,
        SongAction.AddToQueue,
        SongAction.ToggleLike,
        SongAction.StartRadio,
        SongAction.Download,
        SongAction.OpenAlbum,
        SongAction.OpenArtist,
        SongAction.CopyLink,
    )

    @Test
    fun `a row with both browse ids offers the full seven`() {
        assertEquals(fullMenu, songActions(song("album-1", "artist-1"), isCurrent = false))
    }

    @Test
    fun `no album id, no open-album entry`() {
        assertEquals(
            fullMenu - SongAction.OpenAlbum,
            songActions(song(albumId = null, artistId = "artist-1"), isCurrent = false),
        )
    }

    @Test
    fun `no artist id, no open-artist entry`() {
        assertEquals(
            fullMenu - SongAction.OpenArtist,
            songActions(song(albumId = "album-1", artistId = null), isCurrent = false),
        )
    }

    @Test
    fun `neither id leaves only what needs no destination`() {
        val actions = songActions(song(), isCurrent = false)
        assertTrue(SongAction.OpenAlbum !in actions)
        assertTrue(SongAction.OpenArtist !in actions)
        assertEquals(
            listOf(
                SongAction.PlayNext,
                SongAction.AddToQueue,
                SongAction.ToggleLike,
                SongAction.StartRadio,
                SongAction.Download,
                SongAction.CopyLink,
            ),
            actions,
        )
    }

    @Test
    fun `copy log only ever rides on the track that is playing`() {
        assertTrue(SongAction.CopyLog !in songActions(song("a", "t"), isCurrent = false))
        assertTrue(SongAction.CopyLog !in songActions(song(), isCurrent = false))
        assertTrue(SongAction.CopyLog in songActions(song(), isCurrent = true))
    }

    @Test
    fun `the like entry is always present, whichever way the toggle points`() {
        // Which label it carries is the UI's business; the menu never loses
        // the toggle, or a disliked song could never be un-disliked.
        assertTrue(SongAction.ToggleLike in songActions(song(), isCurrent = false))
    }

    @Test
    fun `the download row is three faces of one state`() {
        val faces = listOf(SongAction.Download, SongAction.CancelDownload, SongAction.DeleteDownload)
        fun face(vararg actions: SongAction) = faces.single { it in actions }

        // Not on disk, not in flight: the download itself — also what a failed
        // attempt offers, since a re-ask is exactly what a retry is.
        assertEquals(
            SongAction.Download,
            face(*songActions(song(), isCurrent = false).toTypedArray()),
        )
        // In flight: a cancel, whether the row is waiting or running.
        assertEquals(
            SongAction.CancelDownload,
            face(*songActions(song(), isCurrent = false, downloadState = DownloadState.Queued).toTypedArray()),
        )
        assertEquals(
            SongAction.CancelDownload,
            face(*songActions(song(), isCurrent = false, downloadState = DownloadState.Running(0.5f)).toTypedArray()),
        )
        // Saved: a delete, and nothing else in the section.
        assertEquals(
            SongAction.DeleteDownload,
            face(*songActions(song(), isCurrent = false, saved = true).toTypedArray()),
        )
    }
}
