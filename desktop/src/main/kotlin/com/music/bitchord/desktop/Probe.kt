package com.music.bitchord.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.runBlocking

/**
 * Headless smoke test for the ported data layer: browse the home feed, search
 * for a track, print what came back. The point is to prove the ported
 * Innertube stack talks to YouTube Music from this JVM before any UI depends
 * on it.
 *
 *     ./gradlew -p desktop probe
 *     ./gradlew -p desktop probe -PprobeQuery="Radiohead"
 */
fun main(args: Array<String>) {
    runBlocking { probe(args) }
}

private suspend fun probe(args: Array<String>) {
    val query = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "周杰伦 晴天"

    println("── home ──────────────────────────────")
    YtMusicRepository.home()
        .onSuccess { feed ->
            println("shelves=${feed.shelves.size} continuation=${feed.continuation != null}")
            feed.shelves.take(6).forEach { shelf ->
                println("  【${shelf.title}】 ${shelf.items.size} items")
                shelf.items.take(3).forEach { item ->
                    println("     - ${item.title} · ${item.subtitle}")
                }
            }
        }
        .onFailure { println("home failed: ${it.javaClass.simpleName}: ${it.message}") }

    println()
    println("── search: $query ────────────────────")
    YtMusicRepository.search(query, SearchFilter.SONGS)
        .onSuccess { rows ->
            println("${rows.size} rows")
            rows.take(10).forEach { println("  " + it.describe()) }
        }
        .onFailure { println("search failed: ${it.javaClass.simpleName}: ${it.message}") }

    println()
    println("── search (all): $query ──────────────")
    YtMusicRepository.search(query, SearchFilter.ALL)
        .onSuccess { rows ->
            println("${rows.size} rows")
            rows.take(6).forEach { println("  " + it.describe()) }
        }
        .onFailure { println("search all failed: ${it.javaClass.simpleName}: ${it.message}") }
}

private fun SearchResult.describe(): String = when (this) {
    is SearchResult.TopTrack -> "TOP  ${song.title} — ${song.artist} ${song.durationText.orEmpty()} [${song.videoId}]"
    is SearchResult.Track -> "SONG ${song.title} — ${song.artist} ${song.durationText.orEmpty()} [${song.videoId}]"
    is SearchResult.Browse -> "ITEM $item"
}
