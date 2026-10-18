package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlayerContextTest {
    @Test fun bindsSolvingAndTokensToTheScriptVideoAndPurpose() {
        val purposes = ArrayList<TokenPurpose>()
        val provider = object : PlayerTokenProvider { override fun token(request: PlayerTokenRequest): PlayerToken {
            assertEquals("video", request.videoId); assertNull(request.context.accountId)
            purposes.add(request.purpose); return PlayerToken(request.purpose.name, 10000)
        }}
        val solve = object : PlayerSolver { override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
            assertEquals("https://script/one", request.script.url)
            assertEquals(setOf("abc"), request.signatures)
            return PlayerChallenge(mapOf("abc" to "sig"), emptyMap())
        }}
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertNull(request.headers["Authorization"])
            assertEquals("PLAYER", jsonObject(request.body)!!.obj("serviceIntegrityDimensions")!!.text("poToken"))
            return HttpReply(200, """{"streamingData":{"formats":[{"signatureCipher":"s=abc&url=https%3A%2F%2Fx"}]}}""")
        }}
        val result = loadPlayer("video", RequestContext("account", "secret"), http, PlayerScript("https://script/one", "signatureTimestamp:123"), solve, provider, now = {0})
        assertTrue(result.ok); assertEquals(listOf(TokenPurpose.PLAYER, TokenPurpose.MEDIA, TokenPurpose.SUBTITLE), purposes)
        assertEquals("MEDIA", urlParameter(result.value!!.answer!!.muxedFormats.single().url!!, "pot"))
    }
    @Test fun reportsMissingSourcesInsteadOfReturningASuccessWithNoUrls() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        assertFalse(loadPlayer("v", RequestContext(), http).ok)
    }
}
