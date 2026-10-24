package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** The `/sorry` traffic challenge is reported as VERIFICATION, never retried or hidden as a generic failure. */
class TrafficChallengeTest {
    private fun http(status: Int, location: String? = null) = object : HttpClient {
        override fun execute(request: HttpRequest) =
            HttpReply(status, "", if (location != null) mapOf("Location" to location) else emptyMap())
    }

    @Test fun sorryRedirectBecomesAVerificationErrorWithTheChallengeUrl() {
        val location = "https://www.google.com/sorry/index?continue=https%3A%2F%2Fwww.youtube.com%2F&q=EhAm"
        val result = requestBody(HttpRequest("GET", "https://www.youtube.com/"), http(302, location))
        assertNull(result.value)
        val error = result.error!!
        assertEquals(RequestFailure.VERIFICATION, error.kind)
        assertEquals("traffic_challenge", error.code)
        assertEquals(location, error.detail)
        assertEquals(302, error.status)
    }

    @Test fun sorryOnYoutubeHostsIsAlsoAVerificationError() {
        val result = requestBody(HttpRequest("GET", "https://m.youtube.com/"),
            http(303, "https://www.youtube.com/sorry/index?q=captcha"))
        assertEquals(RequestFailure.VERIFICATION, result.error!!.kind)
    }

    @Test fun ordinaryRedirectsStayPlainFailures() {
        val result = requestBody(HttpRequest("GET", "https://www.youtube.com/"),
            http(302, "https://www.youtube.com/watch?v=aaaaaaaaaaa"))
        assertEquals(RequestFailure.HTTP, result.error!!.kind)
        assertEquals("http_302", result.error!!.code)
    }

    @Test fun lookalikeChallengeLocationsAreRejected() {
        for (location in listOf(
            "https://evil.test/sorry/index?q=x", "http://www.google.com/sorry/index?q=x",
            "https://www.google.com/sorryevil", "https://www.google.com/not-sorry/index",
        )) {
            val result = requestBody(HttpRequest("GET", "https://www.youtube.com/"), http(302, location))
            assertEquals("not a challenge: $location", RequestFailure.HTTP, result.error!!.kind)
        }
    }

    @Test fun rateLimitResponsesAreNotVerificationErrors() {
        val result = requestBody(HttpRequest("GET", "https://www.youtube.com/"), http(429))
        assertEquals(RequestFailure.HTTP, result.error!!.kind)
        assertEquals("http_429", result.error!!.code)
    }

    @Test fun relativeAndPreservingRedirectsKeepTheChallenge() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val result = requestBody(HttpRequest("GET", "https://m.youtube.com/youtubei/v1/browse"), http(status, "/sorry/index?q=x"))
            assertEquals(RequestFailure.VERIFICATION, result.error?.kind)
            assertEquals("https://m.youtube.com/sorry/index?q=x", result.error?.detail)
        }
    }

    @Test fun credentialsAndUnexpectedPortsCannotOpenVerification() {
        for (location in listOf("https://user@www.google.com/sorry/", "https://www.google.com:444/sorry/")) {
            assertEquals(RequestFailure.HTTP, requestBody(HttpRequest("GET", "https://m.youtube.com/"), http(302, location)).error?.kind)
        }
    }

    @Test fun verifiedRequestsAreNotRetriedAnonymously() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            return HttpReply(302, "", mapOf("Location" to "https://www.google.com/sorry/index?q=x"))
        }}
        val result = loadSearch("q", RequestContext("account", "secret", "visitor"), http = http)
        assertFalse(result.ok)
        assertEquals(RequestFailure.VERIFICATION, result.error!!.kind)
        assertEquals(1, calls)
    }
}
