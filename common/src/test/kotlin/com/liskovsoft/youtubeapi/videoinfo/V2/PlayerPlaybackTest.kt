package com.liskovsoft.youtubeapi.videoinfo.V2

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

class PlayerPlaybackTest {
    private val queues = PlayerClientQueues()
    private val walk = PlayerAttempt(queues)
    private lateinit var server: HttpServer
    private var port = 0
    private var followed = false

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/script") { exchange ->
            try {
                val bytes = "signatureTimestamp:19395;".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/land") { exchange ->
            try {
                followed = true
                exchange.sendResponseHeaders(200, -1)
            } finally {
                exchange.close()
            }
        }
        server.createContext("/redirect") { exchange ->
            try {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/land")
                exchange.sendResponseHeaders(302, -1)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun liveUsesHls() {
        val playback = selectPlayback(answer(
            live = true,
            hlsUrl = "https://manifest.example/hls",
            muxed = listOf(format("https://muxed.example/a", height = 720))
        ))
        assertEquals("https://manifest.example/hls", playback.hlsUrl)
        assertNull(playback.videoUrl)
        assertNull(playback.audioUrl)
    }

    @Test
    fun vodPicksTheTallerMuxedStream() {
        val playback = selectPlayback(answer(muxed = listOf(
            format("https://muxed.example/low", height = 360, bitrate = 900000),
            format("https://muxed.example/high", height = 720, bitrate = 1000),
            format("https://muxed.example/tied", height = 720, bitrate = 1000)
        )))
        assertEquals(Playback(null, "https://muxed.example/high", null), playback)
    }

    @Test
    fun adaptivePicksVideoAndAudio() {
        val playback = selectPlayback(answer(adaptive = listOf(
            format("https://video.example/sd", "video/mp4", 480, 500000),
            format("https://video.example/hd", "video/webm", 1080, 2000000),
            format("https://audio.example/low", "audio/mp4", null, 64000),
            format("https://audio.example/high", "audio/webm", null, 128000)
        )))
        assertEquals(Playback(null, "https://video.example/hd", "https://audio.example/high"), playback)
    }

    @Test
    fun loadsTheTimestampAndTheTallerStream() {
        val page = """"jsUrl":"https://www.youtube.com/s/player/abc/base.js""""
        val body = """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[
               {"url":"https://muxed.example/a","height":360},
               {"url":"https://muxed.example/b","height":720}
             ]}}
        """
        var sawTimestamp = false
        val load = loadPlayback(
            walk,
            SignIn.SIGNED_OUT,
            "dQw4w9WgXcQ",
            null,
            page,
            get = { url ->
                assertEquals("https://www.youtube.com/s/player/abc/base.js", url)
                "signatureTimestamp:19395;"
            }
        ) { query ->
            sawTimestamp = query.body.contains("\"signatureTimestamp\":19395")
            Pair(200, body)
        }
        assertTrue(sawTimestamp)
        assertEquals(PlayerClient.VISIONOS, load.client)
        assertEquals("https://muxed.example/b", load.playback.videoUrl)
        assertNull(load.playback.audioUrl)
    }

    @Test
    fun scriptFailureStillReturnsTheStream() {
        val page = """"jsUrl":"https://www.youtube.com/s/player/abc/base.js""""
        val body = """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[{"url":"https://muxed.example/a","height":360}]}}
        """
        var called = false
        var sawPlaybackContext = false
        val load = loadPlayback(
            walk,
            SignIn.SIGNED_OUT,
            "dQw4w9WgXcQ",
            null,
            page,
            get = {
                called = true
                throw IOException("down")
            }
        ) { query ->
            sawPlaybackContext = query.body.contains("playbackContext")
            Pair(200, body)
        }
        assertTrue(called)
        assertFalse(sawPlaybackContext)
        assertEquals("https://muxed.example/a", load.playback.videoUrl)
    }

    @Test
    fun getTextReadsTheBodyAndSkipsRedirects() {
        assertEquals("signatureTimestamp:19395;", getText("http://127.0.0.1:$port/script"))
        var failed = false
        try {
            getText("http://127.0.0.1:$port/redirect")
        } catch (failure: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(followed)
    }

    @Test
    fun cacheSkipsScriptAndSolver() {
        val cache = MemoryPlaybackCache()
        val page = """"jsUrl":"https://www.youtube.com/s/player/abc/base.js""""
        val body = """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[
               {"itag":18,"height":480,"signatureCipher":"s=abc&sp=sig&url=https%3A%2F%2Frr.example%2Fv"}
             ]}}
        """
        var gets = 0
        var solves = 0
        fun load() = loadPlayback(
            walk,
            SignIn.SIGNED_OUT,
            "dQw4w9WgXcQ",
            null,
            page,
            solve = { _, _ ->
                solves++
                PlayerChallenge(mapOf("abc" to "solved"), emptyMap())
            },
            get = {
                gets++
                "signatureTimestamp:19395;"
            },
            cache = cache
        ) { Pair(200, body) }
        val first = load()
        val second = load()
        assertEquals(1, gets)
        assertEquals(1, solves)
        assertTrue(first.playback.videoUrl!!.contains("sig=solved"))
        assertEquals(first.playback.videoUrl, second.playback.videoUrl)
    }

    private fun format(
        url: String,
        mime: String = "video/mp4",
        height: Int? = null,
        bitrate: Int? = null
    ) = PlayerFormat(null, mime, url, null, height, bitrate, null)

    private fun answer(
        live: Boolean = false,
        hlsUrl: String? = null,
        muxed: List<PlayerFormat> = emptyList(),
        adaptive: List<PlayerFormat> = emptyList()
    ) = PlayerAnswer(
        false,
        "OK",
        null,
        muxed.any { it.url != null } || adaptive.any { it.url != null },
        hlsUrl != null,
        muxed.any { it.url != null },
        live,
        hlsUrl,
        muxed.mapNotNull { it.url },
        adaptive.mapNotNull { it.url },
        muxed,
        adaptive
    )
}
