package de.homevoice.assistant.radio

import de.homevoice.assistant.settings.RadioStation
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioBrowserTest {
    private val saved = listOf(
        RadioStation("1LIVE", "https://example.org/1live.mp3"),
        RadioStation("1LIVE diggi", "https://example.org/diggi.mp3"),
        RadioStation("WDR 2", "https://example.org/wdr2.mp3"),
        RadioStation("Deutschlandfunk", "https://example.org/dlf.mp3"),
    )

    @Test
    fun findsSavedStationsByLooseNames() {
        assertEquals("1LIVE", RadioBrowser.findSaved("Radio 1 live", saved)?.name)
        assertEquals("1LIVE", RadioBrowser.findSaved("1 Live", saved)?.name)
        assertEquals("1LIVE diggi", RadioBrowser.findSaved("1live Diggi", saved)?.name)
        assertEquals("WDR 2", RadioBrowser.findSaved("wdr2", saved)?.name)
        assertEquals("Deutschlandfunk", RadioBrowser.findSaved("deutschlandfunk", saved)?.name)
        assertNull(RadioBrowser.findSaved("Bayern 3", saved))
    }

    @Test
    fun parsesAndRanksExactMatchFirst() {
        val body = """[
          {"name":"1LIVE diggi","url_resolved":"https://a/diggi","countrycode":"DE","codec":"MP3","bitrate":128,"clickcount":900,"lastcheckok":1},
          {"name":"1LIVE","url":"https://b/1live","url_resolved":"","countrycode":"DE","codec":"MP3","bitrate":128,"clickcount":500,"lastcheckok":1},
          {"name":"1LIVE ","url_resolved":"https://c/dup","countrycode":"DE","codec":"AAC","bitrate":64,"clickcount":10,"lastcheckok":1},
          {"name":"Broken","url_resolved":"https://d/x","countrycode":"DE","codec":"MP3","bitrate":0,"clickcount":9999,"lastcheckok":0}
        ]"""
        val ranked = RadioBrowser.rank("1live", RadioBrowser(OkHttpClient()).parse(body))
        assertEquals(listOf("1LIVE", "1LIVE diggi"), ranked.map { it.name })
        assertEquals("https://b/1live", ranked.first().url) // falls back to url when url_resolved is empty
    }
}
