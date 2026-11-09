package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PlayerContextTest {
    @Test fun bindsSolvingAndTokensToTheScriptVideoAndPurpose() {
        val purposes = ArrayList<TokenPurpose>()
        val provider = object : PlayerTokenProvider { override fun token(request: PlayerTokenRequest): PlayerToken {
            assertEquals("video", request.videoId); assertNull(request.context.accountId)
            purposes.add(request.purpose)
            return PlayerToken(request.purpose.name, if (request.purpose == TokenPurpose.SUBTITLE) 5000 else 10000)
        }}
        val solve = object : PlayerSolver { override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
            assertEquals("https://script/one", request.script.url)
            assertEquals(setOf("abc"), request.signatures)
            return PlayerChallenge(mapOf("abc" to "sig"), emptyMap())
        }}
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertNull(request.headers["Authorization"])
            assertEquals("PLAYER", jsonObject(request.body)!!.obj("serviceIntegrityDimensions")!!.text("poToken"))
            return HttpReply(200, """{"streamingData":{"formats":[{"signatureCipher":"s=abc&url=https%3A%2F%2Fx"}]},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://captions.test/track","languageCode":"en"}]}}}""")
        }}
        val result = loadPlayer("video", RequestContext("account", "secret"), http, PlayerScript("https://script/one", "signatureTimestamp:123"), solve, provider, now = {0})
        assertTrue(result.ok); assertEquals(listOf(TokenPurpose.PLAYER, TokenPurpose.MEDIA, TokenPurpose.SUBTITLE), purposes)
        assertEquals("MEDIA", urlParameter(result.value!!.answer!!.muxedFormats.single().url!!, "pot"))
        assertEquals("SUBTITLE", urlParameter(result.value!!.answer!!.captions.single().url!!, "pot"))
        assertEquals(5000L, result.value!!.answer!!.expiresAtMs)
    }

    @Test fun missingOrUnusableCaptionUrlsDoNotEnterTheSubtitleTokenStage() {
        val excluded = PlayerClient.values().toSet() - PlayerClient.WEB_SAFARI
        for (captions in listOf("", ""","captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"languageCode":"en"},{"baseUrl":" ","languageCode":"fr"}]}}""")) {
            var time = 0L
            val purposes = mutableListOf<TokenPurpose>()
            val attempts = mutableListOf<PlayerLoadAttempt>()
            val tokens = object : PlayerTokenProvider {
                override fun token(request: PlayerTokenRequest): PlayerToken {
                    purposes += request.purpose
                    time += 20 // Deterministic stand-in for blocking mint work.
                    return PlayerToken(request.purpose.name, 10000)
                }
            }
            val result = loadPlayer("video", RequestContext(), object : HttpClient {
                override fun execute(request: HttpRequest) = HttpReply(200,
                    """{"streamingData":{"formats":[{"url":"https://media.test/video"}]}$captions}""")
            }, tokens = tokens, excluded = excluded, now = { time }, onAttempt = attempts::add)

            assertTrue(result.ok)
            assertEquals(listOf(TokenPurpose.PLAYER, TokenPurpose.MEDIA), purposes)
            assertEquals(40L, attempts.single().elapsedMs)
            assertEquals("MEDIA", urlParameter(result.value!!.answer!!.muxedFormats.single().url!!, "pot"))
        }
    }

    @Test fun rejectedAnswersDoNotEnterTheMediaOrSubtitleTokenStages() {
        val excluded = PlayerClient.values().toSet() - PlayerClient.WEB_SAFARI
        val responses = listOf(
            """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Unavailable"}}""" to "playability_unplayable",
            "{}" to "no_matching_format",
            """{"streamingData":{"formats":[{"url":"https://media.test/2160","height":2160}]}}""" to "no_matching_format",
            """{"streamingData":{"formats":[{"signatureCipher":"s=unresolved&url=https%3A%2F%2Fmedia.test%2Fvideo"}]}}""" to "no_matching_format"
        )
        for ((body, code) in responses) {
            var time = 0L
            val purposes = mutableListOf<TokenPurpose>()
            val attempts = mutableListOf<PlayerLoadAttempt>()
            val tokens = object : PlayerTokenProvider {
                override fun token(request: PlayerTokenRequest): PlayerToken {
                    purposes += request.purpose
                    time += 20
                    return PlayerToken(request.purpose.name, 10000)
                }
            }
            val result = loadPlayer("video", RequestContext(), object : HttpClient {
                override fun execute(request: HttpRequest) = HttpReply(200, body)
            }, tokens = tokens, excluded = excluded, now = { time }, preference = PlaybackPreference(maxHeight = 480),
                onAttempt = attempts::add)

            assertEquals(code, result.error?.code)
            assertEquals(listOf(TokenPurpose.PLAYER), purposes)
            assertEquals(20L, attempts.single().elapsedMs)
        }
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
            assertEquals("playability_${status.toLowerCase(Locale.ROOT)}", error?.code)
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

    @Test fun verificationSurvivesLaterGenericLoginFailures() {
        val attempts = mutableListOf<PlayerLoadAttempt>()
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val reason = if (calls++ == 0) "Sign in to confirm you're not a bot" else "Sign in"
                return HttpReply(200, """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":{"runs":[{"text":"$reason"}]}}}""")
            }
        }
        val result = loadPlayer("video", RequestContext(), http, onAttempt = attempts::add)
        assertEquals(RequestFailure.VERIFICATION, result.error?.kind)
        assertEquals("playability_bot_check", result.error?.code)
        assertEquals(6, attempts.size)
        assertEquals("playability_bot_check", attempts.first().error?.code)
        assertTrue(attempts.all { it.status == "LOGIN_REQUIRED" })
    }

    @Test fun verificationDoesNotPreventAnotherClientFromPlaying() {
        val attempts = mutableListOf<PlayerLoadAttempt>()
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, if (calls++ == 0)
                """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
                else """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"url":"https://media.example.test/video.mp4"}]}}""")
        }
        val result = loadPlayer("video", RequestContext(), http, onAttempt = attempts::add)
        assertTrue(result.ok)
        assertEquals(PlayerClient.WEB, result.value?.client)
        assertEquals(2, attempts.size)
        assertNull(attempts.last().error)
    }

}
