package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
class AccountTokenTest {
    @Test fun sharesARefreshAndPreservesTheRefreshToken() {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            count.incrementAndGet(); assertEquals("r", jsonObject(request.body)!!.text("refresh_token"))
            return HttpReply(200, "{\"access_token\":\"next\",\"expires_in\":3600}")
        }}
        val tokens = AccountToken(AccessToken("old", "r", 0), SignInClient("c", "s", "d"), http, {0})
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { pool.submit(Callable { tokens.token() }) }
            tasks.forEach { assertEquals("r", it.get(2, TimeUnit.SECONDS).value!!.refreshToken) }
            assertEquals(1, count.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun surfacesRevokedCredentials() {
        val bad = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(400, "{\"error\":\"invalid_grant\"}") }
        assertEquals("invalid_grant", refreshAccessToken("r", SignInClient("c", "s", "d"), bad).error!!.code)
    }
}
