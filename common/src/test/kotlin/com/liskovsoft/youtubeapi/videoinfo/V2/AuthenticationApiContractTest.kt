package com.liskovsoft.youtubeapi.videoinfo.V2

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class AuthenticationApiContractTest {
    @Test fun javaNoArgumentConstructorsProduceAnonymousUsableInstances() {
        val context = RequestContext::class.java.getConstructor().newInstance()
        assertFalse(context.signedIn)
        assertEquals("en", context.language)
        assertEquals("US", context.region)
        val session = AccountSession::class.java.getConstructor().newInstance()
        assertFalse(session.snapshot().signedIn)
        assertTrue(session.accounts().isEmpty())
        assertFalse(session.select("unknown"))
        val history = WatchHistory::class.java.getConstructor().newInstance()
        assertEquals(RequestFailure.AUTH, history.update("video", 0, 1000,
            PlayerTracking(null, null), context).error?.kind)
        history.reset()
    }

    @Test fun legacyPostPreservesTheHttpRequestAndResponseWithoutInventingSuccess() {
        val query = PlayerQuery("https://example.test/api", mapOf("Content-Type" to "application/json"), "{\"value\":1}")
        val post = legacyPost(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("POST", request.method)
                assertEquals(query.url, request.url)
                assertEquals(query.headers, request.headers)
                assertEquals(query.body, request.body)
                return HttpReply(409, "conflict")
            }
        })
        assertEquals(409 to "conflict", post(query))
    }

    @Test fun historyControlsUseAuthenticatedWebCommandsAndRetainExplicitAcknowledgements() {
        val paths = ArrayList<String>()
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture", 1), onBehalfOfUser = "brand")
        var paused = true
        fun control(command: String, icon: String) = """{"buttonRenderer":{"icon":{"iconType":"$icon"},"navigationEndpoint":{
            "confirmDialogEndpoint":{"content":{"confirmDialogRenderer":{"confirmEndpoint":{"feedbackEndpoint":{
            "feedbackToken":"$command","actions":[{"localWatchHistoryCommand":{
            "localWatchHistoryCommandType":"LOCAL_WATCH_HISTORY_COMMAND_TYPE_$command"}}]}}}}}}}}"""
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                paths += request.url.removePrefix(YOUTUBE_API).substringBefore('?')
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                assertEquals("1", request.headers["X-Goog-AuthUser"])
                assertEquals("brand", request.headers["X-Goog-PageId"])
                val root = jsonObject(request.body)!!
                assertEquals("WEB", root.path("context", "client")!!.text("clientName"))
                return if (request.url.substringBefore('?').endsWith("/browse")) {
                    assertEquals("FEhistory", root.text("browseId"))
                    val available = if (paused) control("RESUME", "PLAY_OUTLINED") else control("PAUSE", "PAUSE_OUTLINED")
                    HttpReply(200, """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
                        "browseFeedActionsRenderer":{"contents":[$available,${control("CLEAR", "DELETE")}]}}}}}""")
                } else {
                    when ((root.arr("feedbackTokens").single() as Json.Str).value) {
                        "RESUME" -> paused = false
                        "PAUSE" -> paused = true
                        "CLEAR", "remove-this-item" -> Unit
                        else -> fail("Unexpected history token")
                    }
                    assertNull(root.text("nop"))
                    HttpReply(200, """{"feedbackResponses":[{"isProcessed":true}]}""")
                }
            }
        }
        assertTrue(setWatchHistory(true, context, http).value!!.confirmed)
        assertTrue(setWatchHistory(false, context, http).value!!.confirmed)
        assertTrue(clearWatchHistory(context, http).value!!.confirmed)
        assertTrue(removeWatchHistory("remove-this-item", context, http).value!!.confirmed)
        assertEquals(listOf("browse", "feedback", "browse", "feedback", "browse", "feedback", "feedback"), paths)
        assertEquals(RequestFailure.INVALID, removeWatchHistory(" ", context, http).error?.kind)
        assertEquals(RequestFailure.AUTH, setWatchHistory(true, context.anonymous(), http).error?.kind)
        assertEquals(7, paths.size)
    }

    @Test fun resettingWatchHistoryStartsANewPlaybackRecord() {
        val requests = ArrayList<HttpRequest>()
        val history = WatchHistory(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { requests += request; return HttpReply(204, "") }
        })
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture"))
        val tracking = PlayerTracking("https://www.youtube.com/api/stats/playback", "https://www.youtube.com/api/stats/watchtime")
        assertTrue(history.update("video", 1000, 10_000, tracking, context).ok)
        assertTrue(history.update("video", 2000, 10_000, tracking, context).ok)
        val firstCpn = urlParameter(requests.first().url, "cpn")
        history.reset()
        assertTrue(history.update("video", 3000, 10_000, tracking, context).ok)
        assertTrue(requests.last().url.contains("/api/stats/playback"))
        assertNotEquals(firstCpn, urlParameter(requests.last().url, "cpn"))
        assertEquals("3.0", urlParameter(requests.last().url, "st"))
        assertEquals(3, requests.size)
    }

    @Test fun javaPlayerOverloadsKeepDefaultsAndExplicitInputsOnAValidFixture() {
        val facade = Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.PlayerContextKt")
        val overloads = facade.declaredMethods.filter { it.name == "loadPlayer" && Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue(overloads.size > 2)
        for (method in overloads.filter { it.parameterCount >= 3 }) {
            val size = method.parameterCount
            val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture"))
            val expectedClient = when { size >= 12 -> PlayerClient.WEB_EMBED; size >= 8 -> PlayerClient.WEB; else -> PlayerClient.WEB_SAFARI }
            val expectedTimestamp = when { size >= 13 -> 456; size >= 4 -> 123; else -> null }
            val requests = ArrayList<HttpRequest>()
            val purposes = ArrayList<TokenPurpose>()
            val attempts = ArrayList<PlayerLoadAttempt>()
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    requests += request
                    assertEquals("SAPISID=fixture", request.headers["Cookie"])
                    val root = jsonObject(request.body)!!
                    assertEquals(expectedTimestamp, root.path("playbackContext", "contentPlaybackContext")?.int("signatureTimestamp"))
                    assertEquals(if (size >= 6) "PLAYER" else null, root.obj("serviceIntegrityDimensions")?.text("poToken"))
                    return HttpReply(200, """{"streamingData":{"expiresInSeconds":60,"formats":[
                        {"url":"https://media.test/360","mimeType":"video/mp4","height":360},
                        {"url":"https://media.test/1080","mimeType":"video/mp4","height":1080}]}}""")
                }
            }
            val solver = object : PlayerSolver {
                override fun solve(request: PlayerChallengeRequest): PlayerChallenge = error("Direct fixture must not need a solver")
            }
            val tokens = object : PlayerTokenProvider {
                override fun token(request: PlayerTokenRequest): PlayerToken {
                    assertEquals(expectedClient, request.client)
                    assertEquals(context, request.context)
                    purposes += request.purpose
                    return PlayerToken(request.purpose.name, Long.MAX_VALUE)
                }
            }
            val allowed = if (size >= 12) setOf(PlayerClient.WEB, PlayerClient.WEB_EMBED) else setOf(PlayerClient.WEB)
            val preference = PlaybackPreference(maxHeight = 480)
            val args = listOf<Any?>("video", context, http, PlayerScript("script", "signatureTimestamp:123"), solver, tokens,
                PlayerClientQueues(), PlayerClient.values().toSet() - allowed, { 1000L }, preference,
                { attempt: PlayerLoadAttempt -> attempts.add(attempt); Unit }, setOf(PlayerClient.WEB),
                { PlayerScript("rotated-script", "signatureTimestamp:456") }).take(size).toTypedArray()
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(null, *args) as ReadResult<PlayerLoad>
            assertTrue(method.toGenericString(), result.ok)
            assertEquals(expectedClient, result.value!!.client)
            val selected = selectPlayback(result.value!!.answer, if (size >= 10) preference else PlaybackPreference())
            assertTrue(selected.videoUrl!!.startsWith(if (size >= 10) "https://media.test/360" else "https://media.test/1080"))
            assertEquals(if (size >= 6) "MEDIA" else null, urlParameter(selected.videoUrl!!, "pot"))
            assertEquals(if (size >= 6) listOf(TokenPurpose.PLAYER, TokenPurpose.MEDIA) else emptyList<TokenPurpose>(), purposes)
            assertEquals(if (size >= 11) 1 else 0, attempts.size)
            assertEquals(1, requests.size)
        }
        // The no-transport overload uses the real JvmHttpClient. Its validation
        // contract is checked without performing external network I/O.
        val defaultTransport = overloads.single { it.parameterCount == 2 }
        @Suppress("UNCHECKED_CAST")
        val invalid = defaultTransport.invoke(null, "../invalid", RequestContext()) as ReadResult<PlayerLoad>
        assertEquals(RequestFailure.INVALID, invalid.error?.kind)
    }
}
