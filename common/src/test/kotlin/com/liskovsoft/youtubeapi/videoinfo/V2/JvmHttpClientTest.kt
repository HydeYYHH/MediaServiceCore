package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class JvmHttpClientTest {
    @Test fun consumedSuccessAndErrorResponsesReuseTheSameSocketAcrossClients() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("first"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            server.enqueue(MockResponse().setBody("third"))
            val url = server.url("/page").toString()
            assertEquals("first", JvmHttpClient().execute(HttpRequest("GET", url)).body)
            assertEquals("missing", JvmHttpClient().execute(HttpRequest("GET", url)).body)
            assertEquals("third", JvmHttpClient().execute(HttpRequest("GET", url)).body)
            assertEquals(listOf(0, 1, 2), List(3) { server.takeRequest().sequenceNumber })
        }
    }

    @Test fun negotiatesGzipForJsonAndBoundsTheDecompressedBody() {
        val body = "{\"content\":\"${"中文 text ".repeat(1000)}\"}"
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body.toByteArray()) } }.toByteArray()
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bytes))) }
            val request = HttpRequest("GET", server.url("/page").toString())
            val reply = JvmHttpClient().execute(request)
            assertEquals(body, reply.body)
            assertNull(reply.header("Content-Encoding"))
            assertNull(reply.header("Content-Length"))
            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
            val limited = requestBody(request, JvmHttpClient(maxBodyBytes = 128))
            assertEquals("response_too_large", limited.error?.code)
            assertEquals(RequestFailure.PARSE, limited.error?.kind)
        }
    }

    @Test fun keepsAnExplicitAcceptEncodingAndDecodesCompressedErrors() {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("error".toByteArray()) } }.toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Encoding", "gzip")
                .setHeader("Retry-After", "2").setBody(Buffer().write(bytes)))
            val reply = JvmHttpClient().execute(HttpRequest("GET", server.url("/page").toString(), mapOf("accept-encoding" to "identity")))
            assertEquals("error", reply.body)
            assertEquals(429, reply.status)
            assertEquals("2", reply.header("Retry-After"))
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }
    }
}
