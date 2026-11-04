package com.liskovsoft.youtubeapi.videoinfo.V2

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * Error phases against real sockets: MockWebServer -> JvmHttpClient -> `requestBody`.
 * A dropped connection must not read as parse, a 401 must not read as transport,
 * and throttling must keep its Retry-After window.
 */
class TransportSocketTest {
    private fun read(server: MockWebServer): ReadResult<String> =
        requestBody(HttpRequest("GET", server.url("/next").toString()), JvmHttpClient(2000))

    private fun failure(server: MockWebServer): RequestError =
        read(server).error ?: error("expected an error")

    @Test fun aDroppedConnectionReportsTransportWithTheSocketCause() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val error = failure(server)
            assertEquals(RequestFailure.TRANSPORT, error.kind)
            assertTrue(error.code.startsWith("transport_"))
            assertNotNull(error.cause)
        }
    }

    @Test fun aStalledResponseSurfacesAsTimeoutNotParse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val error = failure(server)
            assertEquals(RequestFailure.TRANSPORT, error.kind)
            assertTrue("stall read as ${error.code}",
                error.code in setOf("transport_timeout", "transport_io", "transport_socket"))
        }
    }

    @Test fun unauthorizedResponsesKeepTheirStatusAndAuthKind() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val error = failure(server)
            assertEquals(RequestFailure.AUTH, error.kind)
            assertEquals("http_401", error.code)
            assertEquals(401, error.status)
        }
    }

    @Test fun forbiddenResponsesKeepTheirStatusAndAuthKind() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val error = failure(server)
            assertEquals(RequestFailure.AUTH, error.kind)
            assertEquals("http_403", error.code)
            assertEquals(403, error.status)
        }
    }

    @Test fun throttledResponsesCarryTheRetryAfterWindow() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
            val error = failure(server)
            assertEquals(RequestFailure.HTTP, error.kind)
            assertEquals("http_429", error.code)
            assertEquals(7000L, error.retryAfterMs)
        }
    }

    @Test fun serverErrorsKeepTheirStatus() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val error = failure(server)
            assertEquals(RequestFailure.HTTP, error.kind)
            assertEquals("http_500", error.code)
            assertEquals(500, error.status)
        }
    }

    @Test fun malformedBodiesReportParseNotTransport() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
            val error = requestObject(PlayerQuery(server.url("/next").toString(), emptyMap(), ""), JvmHttpClient(2000))
                .error ?: error("expected parse error")
            assertEquals(RequestFailure.PARSE, error.kind)
            assertEquals("invalid_json", error.code)
        }
    }
}
