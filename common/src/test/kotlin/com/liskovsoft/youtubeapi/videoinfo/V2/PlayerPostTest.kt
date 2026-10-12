package com.liskovsoft.youtubeapi.videoinfo.V2

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class PlayerPostTest {
    private lateinit var server: HttpServer
    private var port = 0
    private var method: String? = null
    private var clientName: String? = null
    private var requestBody: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/ok") { exchange ->
            try {
                method = exchange.requestMethod
                clientName = exchange.requestHeaders.getFirst("X-Youtube-Client-Name")
                requestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val payload = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"url":"https://rr.googlevideo.com/videoplayback?id=v"}]}}"""
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/denied") { exchange ->
            try {
                val bytes = "no".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(403, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/redirect") { exchange ->
            try {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/ok")
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
    fun postsTheQueryAndReadsADirectUrl() {
        val answer = fetchPlayer(query("/ok"), ::postPlayer)
        assertEquals("POST", method)
        assertEquals("101", clientName)
        assertTrue(requestBody!!.contains("dQw4w9WgXcQ"))
        assertEquals("OK", answer.status)
        assertTrue(answer.hasDirectUrl)
        assertFalse(answer.transportFailed)
    }

    @Test
    fun returnsTheStatusWhenNotOk() {
        val http = postPlayer(query("/denied"))
        assertEquals(403, http.first)
        assertEquals("no", http.second)
        val answer = fetchPlayer(query("/denied"), ::postPlayer)
        assertTrue(answer.transportFailed)
    }

    @Test
    fun doesNotFollowRedirects() {
        val http = postPlayer(query("/redirect"))
        assertEquals(302, http.first)
    }

    private fun query(path: String) = PlayerQuery(
        "http://127.0.0.1:$port$path",
        mapOf(
            "Content-Type" to "application/json",
            "X-Youtube-Client-Name" to "101"
        ),
        """{"videoId":"dQw4w9WgXcQ"}"""
    )
}
