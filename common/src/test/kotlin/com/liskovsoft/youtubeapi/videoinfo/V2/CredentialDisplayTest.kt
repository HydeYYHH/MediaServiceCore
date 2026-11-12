package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CredentialDisplayTest {
    private val cookie = "fixture-cookie-display-boundary"
    private val authorization = "fixture-authorization-display-boundary"
    private val visitor = "fixture-visitor-display-boundary"
    private val token = "fixture-token-display-boundary"
    private val screen = "fixture-screen-display-boundary"
    private val url = "https://example.invalid/private-path?sig=$token&visitorData=$visitor#$cookie"
    private val headers = mapOf("Cookie" to cookie, "Authorization" to authorization, "X-Goog-Visitor-Id" to visitor)
    private val body = "{\"visitorData\":\"$visitor\",\"token\":\"$token\"}"

    private fun assertNoSensitiveFields(display: String) {
        for (value in listOf(cookie, authorization, visitor, token, screen, url, body, "private-path", "visitorData", "Authorization"))
            assertFalse("Default display must not expand request, response or pairing material", display.contains(value))
    }

    @Test fun httpRequestShowsOnlySupportedMethodAndCountsIncludingForUntrustedMethods() {
        for (method in listOf("GET", "POST", "HEAD", "untrusted-$cookie\r\n$authorization")) {
            val request = HttpRequest(method, url, headers, body)
            val text = request.toString()
            assertNoSensitiveFields(text)
            val safeMethod = if (method in setOf("GET", "POST", "HEAD")) method else "OTHER"
            assertTrue("Display has only safe method and sizes",
                text == "HttpRequest(method=$safeMethod, headerCount=3, bodyLength=${body.length})")
            assertTrue("Raw fields remain available to the transport", request.url == url && request.headers == headers && request.body == body)
            val (actualMethod, actualUrl, actualHeaders, actualBody) = request
            assertTrue("Data-class components are unchanged", actualMethod == method && actualUrl == url && actualHeaders == headers && actualBody == body)
            assertTrue("Data-class copy equality is unchanged", request.copy() == request)
            assertTrue("Data-class copy can change transport data", request.copy(body = "different") != request)
        }
    }

    @Test fun httpReplyHidesSetCookieRedirectAndResponseBodyWithoutChangingResponseAccess() {
        val responseHeaders = mapOf("Set-Cookie" to cookie, "Location" to url)
        val reply = HttpReply(302, body, responseHeaders)
        assertNoSensitiveFields(reply.toString())
        assertTrue("Response display has only status and sizes", reply.toString() == "HttpReply(status=302, headerCount=2, bodyLength=${body.length})")
        assertTrue("Header access remains case-insensitive", reply.header("set-cookie") == cookie)
        val (status, actualBody, actualHeaders) = reply
        assertTrue("Data-class components are unchanged", status == 302 && actualBody == body && actualHeaders == responseHeaders)
        assertTrue("Data-class copy equality is unchanged", reply.copy() == reply)
        assertTrue("Data-class copy can change the status", reply.copy(status = 200) != reply)
    }

    @Test fun playerQueryHidesSignedUrlsHeadersAndVisitorPayloadButStillConvertsToAnExactRequest() {
        val query = PlayerQuery(url, headers, body)
        assertNoSensitiveFields(query.toString())
        assertTrue("Query display has only sizes", query.toString() == "PlayerQuery(headerCount=3, bodyLength=${body.length})")
        val (actualUrl, actualHeaders, actualBody) = query
        assertTrue("Data-class components are unchanged", actualUrl == url && actualHeaders == headers && actualBody == body)
        assertTrue("Data-class copy equality is unchanged", query.copy() == query)
        val request = query.request()
        assertTrue("Transport conversion keeps exact raw material", request.method == "POST" && request.url == url && request.headers == headers && request.body == body)
        assertNoSensitiveFields(request.toString())
    }

    @Test fun loungeScreenNeverDisplaysPairingCredentialsWhileItsDataSemanticsStayIntact() {
        val pairing = LoungeScreen(screen, token)
        assertNoSensitiveFields(pairing.toString())
        assertTrue("Pairing default display is a fixed type label", pairing.toString() == "LoungeScreen")
        val (actualScreen, actualToken) = pairing
        assertTrue("Data-class components are unchanged", actualScreen == screen && actualToken == token)
        assertTrue("Data-class copy equality is unchanged", pairing.copy() == pairing)
        assertTrue("Data-class copy can rotate the token", pairing.copy(loungeToken = "rotated") != pairing)
    }
}
