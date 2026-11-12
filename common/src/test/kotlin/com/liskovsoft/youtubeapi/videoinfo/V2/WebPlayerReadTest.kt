package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WebPlayerReadTest {
    private val video = "jNQXAC9IVRw"
    private val signed = RequestContext(accountId = "gaia:fixture", webSession = WebSession("SAPISID=private; SID=second", 2), visitorCookie = "YSC=guest")
    private fun page(loggedIn: Boolean = true, index: String = "2", player: String = response(), extra: String = "") =
        """<script>ytcfg.set({"LOGGED_IN":$loggedIn,"SESSION_INDEX":"$index"$extra});var ytInitialPlayerResponse = $player;</script><script>var cfg={"jsUrl":"/s/player/revision/player_ias.vflset/en_US/base.js"};</script>"""
    private fun response(id: String = video) = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id","lengthSeconds":"20"},"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?private=one"},"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?private=two"}}}"""
    private fun http(body: String, verify: (HttpRequest) -> Unit = {}) = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply { verify(request); return HttpReply(200, body) }
    }

    @Test fun signedBootstrapUsesTheExplicitBrowserJarAndIndexAndExposesPreparedPlayerData() {
        val result = loadWebPlayer(video, signed, http(page()) { request ->
            assertEquals("GET", request.method)
            assertEquals("https://www.youtube.com/watch?v=$video&hl=en&gl=US&authuser=2", request.url)
            assertEquals(signed.webSession!!.cookies, request.headers["Cookie"])
            assertFalse(request.headers.keys.any { it.equals("Authorization", true) || it.equals("X-Goog-PageId", true) })
        })
        assertTrue(result.ok)
        val value = result.value!!
        assertEquals(video, value.answer.details!!.videoId)
        assertEquals("https://www.youtube.com/s/player/revision/player_ias.vflset/en_US/base.js", value.scriptUrl)
        assertTrue(value.tracking!!.watchTimeUrl!!.contains("private=two"))
        assertEquals("WebPlayerPage(status=OK, hasTracking=true)", value.toString())
        val copy = WebPlayerPage(value.answer, null)
        assertNull(copy.scriptUrl); assertSame(value.answer, copy.answer); assertSame(value.tracking, copy.tracking)
    }

    @Test fun anonymousBootstrapUsesOnlyValidatedVisitorCookies() {
        val context = RequestContext(visitor = "visitor", visitorCookie = "VISITOR_INFO1_LIVE=fixture; YSC=guest", language = "zh CN", region = "TW")
        assertTrue(loadWebPlayer(video, context, http(page(false)) { request ->
            assertEquals(context.visitorCookie, request.headers["Cookie"])
            assertFalse(request.url.contains("authuser")); assertTrue(request.url.contains("hl=zh+CN"))
        }).ok)
        assertTrue(loadWebPlayer(video, RequestContext(), http(page(false)) { assertNull(it.headers["Cookie"]) }).ok)
        assertEquals(RequestFailure.INVALID, loadWebPlayer(video, context.copy(visitorCookie = "SAPISID=secret"), http("unused")).error!!.kind)
    }

    @Test fun mismatchedLoginAccountDelegationAndVideoNeverYieldAnotherIdentitysTracking() {
        for (body in listOf(page(false), page(index = "1"), page(extra = ",\"DELEGATED_SESSION_ID\":\"brand-fixture\"")))
            assertEquals(RequestFailure.AUTH, loadWebPlayer(video, signed, http(body)).error!!.kind)
        assertEquals("web_player_video_mismatch", loadWebPlayer(video, signed, http(page(player = response("different")))).error!!.code)
        assertEquals(RequestFailure.AUTH, loadWebPlayer(video, RequestContext(), http(page())).error!!.kind)
    }

    @Test fun unsupportedDelegationAndInvalidIdentityRejectBeforeTransportIncludingJavaOverload() {
        var count = 0
        val noIo = http("unused") { count++ }
        assertEquals(RequestFailure.UNSUPPORTED, loadWebPlayer(video, signed.copy(onBehalfOfUser = "brand"), noIo).error!!.kind)
        assertEquals(RequestFailure.AUTH, loadWebPlayer(video, RequestContext(accountId = "orphan"), noIo).error!!.kind)
        assertEquals(RequestFailure.INVALID, loadWebPlayer("not a video", signed, noIo).error!!.kind)
        val owner = Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.WebPlayerReadKt")
        val wrapper = owner.getMethod("loadWebPlayer", String::class.java, RequestContext::class.java)
        val rejected = wrapper.invoke(null, "not a video", RequestContext()) as ReadResult<*>
        assertEquals(RequestFailure.INVALID, rejected.error!!.kind)
        assertEquals(0, count)
    }

    @Test fun literalParserSkipsQuotedFakeAssignmentsAndCommentsAndRejectsAmbiguity() {
        val misleading = """<script>var text='ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"}};'; // ytInitialPlayerResponse = {};
            /* var ytInitialPlayerResponse = {}; */ ytcfg.set({"LOGGED_IN":true,"SESSION_INDEX":2});
            window["ytInitialPlayerResponse"] = ${response()};</script>"""
        assertTrue(loadWebPlayer(video, signed, http(misleading)).ok)
        val twice = page().replace("</script>", "var ytInitialPlayerResponse = ${response()};</script>")
        assertEquals("missing_web_player_response", loadWebPlayer(video, signed, http(twice)).error!!.code)
        assertEquals("missing_web_player_response", loadWebPlayer(video, signed, http(page(player = "null"))).error!!.code)
        assertEquals("missing_web_player_config", loadWebPlayer(video, signed, http("<script>var ytInitialPlayerResponse = ${response()};</script>")).error!!.code)
    }

    @Test(timeout = 15000) fun largeBootstrapScriptsRetainLiteralAndIdentityChecks() {
        val prefix = "value=0;".repeat(250_000)
        val body = page().replace("<script>ytcfg", "<script>$prefix\nytcfg")
        val result = loadWebPlayer(video, signed, http(body))
        assertTrue(result.ok)
        assertEquals(video, result.value!!.answer.details!!.videoId)
        assertTrue(result.value!!.tracking!!.playbackUrl!!.contains("private=one"))
    }

    @Test fun malformedResponsesChallengesAndUntrustedScriptDestinationsStayStructured() {
        assertEquals("missing_playability_status", loadWebPlayer(video, signed, http(page(player = "{}"))).error!!.code)
        val restricted = loadWebPlayer(video, signed, http(page(player = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Login required"}}"""))).value!!
        assertEquals("LOGIN_REQUIRED", restricted.answer.status); assertNull(restricted.tracking)
        assertNull(loadWebPlayer(video, signed, http(page().replace("/s/player/revision/player_ias.vflset/en_US/base.js", "https://other.test/base.js"))).value!!.scriptUrl)
        val challenge = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(429, "", mapOf("Retry-After" to "3")) }
        assertEquals(RequestFailure.HTTP, loadWebPlayer(video, signed, challenge).error!!.kind)
    }
}
