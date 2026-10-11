package de.homevoice.assistant.media

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Parses real search responses captured from music.youtube.com (test/resources/ytmusic). */
class YouTubeMusicSearchTest {
    private fun results(name: String) = YouTubeMusicSearch.parse(
        Json.parseToJsonElement(javaClass.getResource("/ytmusic/$name.json")!!.readText())
    )

    @Test
    fun artistPlaysShuffleMix() {
        val t = YouTubeMusicSearch.select(results("queen"), "artist")!!
        assertEquals("Queen", t.title)
        assertEquals("https://music.youtube.com/watch?list=RDAObHaAxpOZhcVmmF6I3y0siA", t.url)
    }

    @Test
    fun trackPrefersSongOverVideoAndStartsRadio() {
        val t = YouTubeMusicSearch.select(results("bohemian_rhapsody"), "track")!!
        assertEquals("Bohemian Rhapsody", t.title)
        assertEquals("https://music.youtube.com/watch?v=BSTsnWoslP4&list=RDAMVMBSTsnWoslP4", t.url)
    }

    @Test
    fun anyUsesTopResult() {
        assertEquals("fJ9rUzIMcZQ", YouTubeMusicSearch.select(results("bohemian_rhapsody"), "any")!!.videoId)
        assertEquals("A Night At The Opera", YouTubeMusicSearch.select(results("a_night_at_the_opera_queen"), "any")!!.title)
    }

    @Test
    fun albumPlaysAlbumPlaylist() {
        val t = YouTubeMusicSearch.select(results("a_night_at_the_opera_queen"), "album")!!
        assertEquals("OLAK5uy_mQGa-V4qspfisNHu1kXthY1w93yJNT0rw", t.playlistId)
    }

    @Test
    fun playlistPrefersRealPlaylists() {
        val t = YouTubeMusicSearch.select(results("workout_playlist"), "playlist")!!
        assertEquals("playlist", t.kind)
        assertEquals(true, t.title.contains("Workout"))
    }
}
