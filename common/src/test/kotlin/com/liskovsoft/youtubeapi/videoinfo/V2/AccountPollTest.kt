package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class AccountPollTest {
    private val client = SignInClient("id", "secret", "device")
    @Test fun followsPendingAndSlowDownWithoutBusyPolling() {
        var now = 0L; var calls = 0; val waits = ArrayList<Long>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertEquals("d", jsonObject(request.body)!!.text("code"))
            return when (calls++) { 0 -> HttpReply(428, "{\"error\":\"authorization_pending\"}"); 1 -> HttpReply(403, "{\"error\":\"slow_down\"}")
                else -> HttpReply(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}") }
        }}
        val result = pollSignIn(SignInCode("d", "u", "url", 20000, 1000), client, http, {now}, { waits.add(it); now += it })
        assertTrue(result.ok); assertEquals(listOf(1000L, 6000L), waits); assertEquals(3, calls)
        assertFalse(result.value.toString().contains("refresh_token"))
    }
    @Test fun stopsOnDenialExpiryAndCancellation() {
        val denied = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(403, "{\"error\":\"access_denied\"}") }
        val code = SignInCode("d", "u", "url", 10, 1)
        assertEquals("access_denied", pollSignIn(code, client, denied, {0}).error!!.code)
        assertEquals("expired_token", pollSignIn(code, client, denied, {10}).error!!.code)
        assertEquals(RequestFailure.CANCELLED, pollSignIn(code, client, denied, {0}, {}, {true}).error!!.kind)
    }
}
