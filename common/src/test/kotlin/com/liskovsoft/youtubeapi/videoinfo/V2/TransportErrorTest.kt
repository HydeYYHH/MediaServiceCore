package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class TransportErrorTest {
    private fun readWith(error: Exception): RequestError? {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = throw error
        }
        return requestBody(HttpRequest("GET", "https://www.youtube.com/youtubei/v1/next"), http).error
    }

    @Test fun dnsFailuresClassifyAsTransportDns() {
        val error = readWith(UnknownHostException("no such host"))!!
        assertEquals(RequestFailure.TRANSPORT, error.kind)
        assertEquals("transport_dns", error.code)
        assertTrue(error.cause!!.contains("UnknownHostException"))
    }

    @Test fun connectFailuresClassifyAsTransportConnect() {
        val error = readWith(ConnectException("refused"))!!
        assertEquals("transport_connect", error.code)
        assertTrue(error.cause!!.contains("refused"))
    }

    @Test fun tlsFailuresClassifyAsTransportTls() {
        val error = readWith(SSLHandshakeException("handshake timed out"))!!
        assertEquals("transport_tls", error.code)
    }

    @Test fun readTimeoutsClassifyAsTransportTimeout() {
        val error = readWith(SocketTimeoutException("Read timed out"))!!
        assertEquals("transport_timeout", error.code)
    }

    @Test fun resetsClassifyAsTransportSocket() {
        val error = readWith(SocketException("Connection reset"))!!
        assertEquals("transport_socket", error.code)
    }

    @Test fun plainIoClassifiesAsTransportIo() {
        val error = readWith(IOException("unexpected end of stream"))!!
        assertEquals("transport_io", error.code)
    }

    @Test fun nonIoExceptionsKeepTheGenericTransportCodeWithCause() {
        val error = readWith(IllegalStateException("boom"))!!
        assertEquals(RequestFailure.TRANSPORT, error.kind)
        assertEquals("transport_failed", error.code)
        assertTrue(error.cause!!.contains("IllegalStateException"))
    }

    @Test fun cancelledCallsReportCancellationNotTransport() {
        val error = readWith(IOException("Canceled"))!!
        assertEquals(RequestFailure.CANCELLED, error.kind)
        assertEquals("cancelled", error.code)
    }

    @Test fun interruptsReportCancellationAndRestoreTheFlag() {
        val error = readWith(InterruptedException())!!
        assertEquals(RequestFailure.CANCELLED, error.kind)
        assertTrue(Thread.interrupted()) // consumed; the flag was set by the classifier
    }

    @Test fun httpStatusAndAuthErrorsStayUntouched() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = HttpReply(401, "{}")
        }
        val result = requestBody(HttpRequest("GET", "https://www.youtube.com/x"), http)
        assertEquals("http_401", result.error!!.code)
        assertEquals(RequestFailure.AUTH, result.error!!.kind)
        assertEquals(401, result.error!!.status)
    }

    @Test fun signedQueryFragmentsAreRedactedFromTheCause() {
        val error = readWith(IOException("failed: https://host/videoplayback?sig=SECRET&key=TOKEN&id=x"))!!
        assertFalse(error.cause!!.contains("SECRET"))
        assertFalse(error.cause!!.contains("TOKEN"))
    }

    @Test fun causeIsBoundedSoHugeMessagesCannotFloodDiagnostics() {
        val error = readWith(IOException("x".repeat(5000)))!!
        assertTrue(error.cause!!.length <= 160)
    }

    @Test fun transportCausesNeverRetainCredentialsOrUnlistedUrlParameters() {
        val error = readWith(IOException("Authorization: Bearer SECRET_COOKIE https://host/?sparams=PRIVATE&poToken=PRIVATE_TOKEN"))!!
        assertEquals("IOException", error.cause)
    }

    @Test fun aClosedSocketWithoutCancellationStaysRetryable() {
        assertEquals("transport_socket", readWith(SocketException("Socket closed"))!!.code)
    }
}
