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
    @Test fun keepsPlayabilityFailuresDistinctFromFormatSelection() {
        for (status in listOf("ERROR", "UNPLAYABLE", "LOGIN_REQUIRED")) {
            val http = object : HttpClient {
                override fun execute(request: HttpRequest) = HttpReply(200,
                    """{"playabilityStatus":{"status":"$status","reason":"Unavailable"}}""")
            }
            val error = loadPlayer("v", RequestContext(), http).error
            assertEquals(RequestFailure.UNSUPPORTED, error?.kind)
            assertEquals("playability_${status.toLowerCase(java.util.Locale.ROOT)}", error?.code)
        }
    }

    @Test fun preservesTheLeadingClientAfterAContentSpecificFailure() {
        val queues = PlayerClientQueues()
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return if (calls == 1) HttpReply(200,
                    """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Restricted video"}}""")
                else HttpReply(200, """{"streamingData":{"formats":[{"url":"https://x"}]}}""")
            }
        }
        assertTrue(loadPlayer("v", RequestContext(), http, queues = queues).ok)
        assertEquals(PlayerClient.VISIONOS, queues.order(ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)).first())
    }

    @Test fun reportsAnActualSelectionMismatchAsAMissingFormat() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"url":"https://x","height":2160}]}}""")
        }
        val result = loadPlayer("v", RequestContext(), http, preference = PlaybackPreference(maxHeight = 480))
        assertEquals("no_matching_format", result.error?.code)
    }

}
