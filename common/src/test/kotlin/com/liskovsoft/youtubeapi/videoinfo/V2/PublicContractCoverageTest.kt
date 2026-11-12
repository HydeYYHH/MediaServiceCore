package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Behavioral contracts for public entry points that hosts use without the internal parsers. */
class PublicContractCoverageTest {
    @Test fun solverCombinesOnlyRequestedCacheEntriesAndSeparatesScriptRevisions() {
        val pending = ArrayList<PlayerChallengeRequest>()
        val cache = MemoryPlaybackCache()
        val solver = CachedPlayerSolver(object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                pending += request
                return PlayerChallenge(request.signatures.associateWith { "signature:$it" } + ("injected" to "unrequested"),
                    request.throttles.associateWith { "throttle:$it" } + ("injected" to "unrequested"))
            }
        }, cache)
        val first = PlayerChallengeRequest(PlayerScript("https://www.youtube.com/base.js", "revision one"),
            setOf("a"), setOf("n"))
        assertEquals(PlayerChallenge(mapOf("a" to "signature:a"), mapOf("n" to "throttle:n")), solver.solve(first))
        val expanded = first.copy(signatures = setOf("a", "b"), throttles = setOf("n", "m"))
        assertEquals(setOf("a", "b"), solver.solve(expanded).signatures.keys)
        assertEquals(setOf("b"), pending[1].signatures)
        assertEquals(setOf("m"), pending[1].throttles)
        assertEquals("throttle:n", solver.solve(first).throttles["n"])
        assertEquals(2, pending.size)

        solver.solve(first.copy(signatures = setOf("injected"), throttles = emptySet()))
        assertEquals(3, pending.size) // A delegate cannot poison the cache with unsolicited solutions.
        solver.solve(first.copy(script = first.script.copy(source = "revision two")))
        assertEquals(4, pending.size)
        assertEquals(first.signatures, pending.last().signatures)
        cache.clear()
        solver.solve(first)
        assertEquals(5, pending.size)
    }

    @Test fun tokensAreScopedToClientVideoIdentityAndPurposeAndNeverExposeTheirValue() {
        var calls = 0
        val tokens = CachedPlayerTokens(object : PlayerTokenProvider {
            override fun token(request: PlayerTokenRequest) = PlayerToken("private-${++calls}", 100)
        }, now = { 0 })
        val context = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=fixture"))
        val request = PlayerTokenRequest(PlayerClient.WEB, "video", context, TokenPurpose.PLAYER)
        val first = tokens.token(request)!!
        assertSame(first, tokens.token(request))
        assertFalse(first.toString().contains("private"))
        assertEquals("PlayerToken(expiresAtMs=100)", first.toString())
        tokens.token(request.copy(client = PlayerClient.TV))
        tokens.token(request.copy(videoId = "other"))
        tokens.token(request.copy(context = context.copy(onBehalfOfUser = "brand")))
        tokens.token(request.copy(purpose = TokenPurpose.MEDIA))
        assertEquals(5, calls)
        tokens.clear()
        assertNotSame(first, tokens.token(request))
        assertEquals(6, calls)
    }

    @Test fun tokenClearFencesAnInflightMintAndInvalidTokensAreNotCached() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val tokens = CachedPlayerTokens(object : PlayerTokenProvider {
            override fun token(request: PlayerTokenRequest): PlayerToken {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return PlayerToken("mint-$call", 100)
            }
        }, now = { 0 })
        val request = PlayerTokenRequest(PlayerClient.WEB, "video", RequestContext(), TokenPurpose.MEDIA)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit(Callable { tokens.token(request) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            tokens.clear()
            release.countDown()
            assertEquals("mint-1", first.get(2, TimeUnit.SECONDS)!!.value)
            assertEquals("mint-2", tokens.token(request)!!.value)
            assertEquals("mint-2", tokens.token(request)!!.value)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }

        var attempts = 0
        var now = 10L
        val invalid = CachedPlayerTokens(object : PlayerTokenProvider {
            override fun token(request: PlayerTokenRequest) = when (++attempts) {
                1 -> PlayerToken(" ", 100)
                2 -> PlayerToken("expired", now)
                else -> PlayerToken("valid-$attempts", now + 10)
            }
        }, now = { now })
        assertNull(invalid.token(request))
        assertNull(invalid.token(request))
        assertEquals("valid-3", invalid.token(request)!!.value)
        assertEquals("valid-3", invalid.token(request)!!.value)
        now = 20
        assertEquals("valid-4", invalid.token(request)!!.value)
    }

    @Test fun groupMembershipIsDeduplicatedAndPersistenceFailureLeavesThePreviousSnapshot() {
        var rejectSave = false
        val saved = ArrayList<List<ChannelGroup>>()
        val groups = ChannelGroups(save = {
            if (rejectSave) throw IllegalStateException("storage unavailable")
            saved += it
        }, newId = { "group" })
        val created = groups.create("Subscriptions", channels = listOf(GroupChannel("UCfirst", "First", null)))
        val channels = mutableListOf(GroupChannel("UCsecond", "Second", "icon"), GroupChannel("UCsecond", "Duplicate", null))
        assertTrue(groups.setChannels(created.id, channels))
        channels.clear()
        assertEquals(listOf(GroupChannel("UCsecond", "Second", "icon")), groups.find(created.id)!!.channels)
        assertEquals("UCfirst", saved.first().single().channels.single().id)
        assertEquals(2, saved.size)
        assertFalse(groups.setChannels("missing", emptyList()))
        assertEquals(2, saved.size)

        val before = groups.read()
        rejectSave = true
        try {
            groups.setChannels(created.id, emptyList())
            fail("The host's storage error must reach the host")
        } catch (expected: IllegalStateException) {
            assertEquals("storage unavailable", expected.message)
        }
        assertEquals(before, groups.read())
        assertEquals("invalid_channel_group", groups.import("""{"version":1,"groups":[null]}""").error!!.code)
        assertEquals(before, groups.read())
    }

    @Test fun menuAuthorResolutionReadsOnlyThePublicPanelAndDoesNotForwardAccountCredentials() {
        val seen = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                seen += request
                return HttpReply(200, """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[
                    {"listItemViewModel":{"title":{"content":"Playlist"},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLplaylist"}}}},
                    {"listItemViewModel":{"title":{"content":"Author"},"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCauthor"}}}}}}}
                ]}}}}}""")
            }
        }
        val context = RequestContext(accountId = "owner", visitor = "visitor", language = "zh", region = "TW",
            onBehalfOfUser = "brand", webSession = WebSession("SAPISID=private-fixture"))
        assertEquals("UCauthor", loadMenuChannelId(MenuEndpoint("menu", "server-address"), context, http).value)
        val request = seen.single()
        assertTrue(request.url.endsWith("/get_panel"))
        assertFalse(request.headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) || it.equals("X-Goog-PageId", true) })
        val root = jsonObject(request.body)!!
        assertEquals("menu", root.text("panelId"))
        assertEquals("server-address", root.text("params"))
        assertEquals("visitor", root.path("context", "client")!!.text("visitorData"))
        assertFalse(request.body.contains("onBehalfOfUser"))
        assertFalse(request.body.contains("private-fixture"))
        assertEquals("invalid_menu_endpoint", loadMenuChannelId(MenuEndpoint("", "params"), context, http).error!!.code)
        assertEquals(1, seen.size)

        val empty = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[]}}}}}""")
        }
        assertEquals("menu_channel_missing", loadMenuChannelId(MenuEndpoint("menu", "params"), context, empty).error!!.code)
    }

    @Test fun handlesResolveThroughWebNavigationAndOrdinaryIdsNeedNoRequest() {
        val seen = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                seen += request
                return HttpReply(200, """{"endpoint":{"browseEndpoint":{"browseId":"UCresolved"}}}""")
            }
        }
        val context = RequestContext(visitor = "visitor", language = "ja", region = "JP",
            webSession = WebSession("SAPISID=fixture"))
        assertEquals("UCdirect", resolveChannelId("UCdirect", context, http).value)
        assertTrue(seen.isEmpty())
        assertEquals("UCresolved", resolveChannelId("@creator", context, http).value)
        val request = seen.single()
        assertTrue(request.url.endsWith("/navigation/resolve_url"))
        val root = jsonObject(request.body)!!
        assertEquals("https://www.youtube.com/@creator", root.text("url"))
        assertFalse(root.fields.containsKey("browseId"))
        assertEquals("WEB", root.path("context", "client")!!.text("clientName"))
        assertEquals("ja", root.path("context", "client")!!.text("hl"))
        assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        val missing = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        assertEquals("channel_not_found", resolveChannelId("@missing", context, missing).error!!.code)
        val throttled = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(429, "{}") }
        assertEquals("http_429", resolveChannelId("@creator", context, throttled).error!!.code)
    }

    @Test fun recoveryResetRestoresOnlyTheSelectedVideoAndIdentityBudget() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, """{"streamingData":{"formats":[{"url":"https://media.example/stream"}]}}""")
            }
        }
        val context = RequestContext(visitor = "visitor")
        val session = PlaybackSession(http)
        val recovery = PlaybackRecovery(session)
        val first = session.load("video", context).value!!
        val refreshed = recovery.recover(first, first.playback.videoUrl!!, 403).value!!
        assertEquals(first.client, refreshed.client)
        val fallback = recovery.recover(refreshed, refreshed.playback.videoUrl!!, 403).value!!
        assertNotEquals(refreshed.client, fallback.client)
        assertEquals("recovery_exhausted", recovery.recover(fallback, fallback.playback.videoUrl!!, 403).error!!.code)
        val exhaustedCalls = calls
        recovery.reset("other-video", context)
        assertEquals("recovery_exhausted", recovery.recover(fallback, fallback.playback.videoUrl!!, 403).error!!.code)
        assertEquals(exhaustedCalls, calls)
        recovery.reset("video", context)
        val restored = recovery.recover(fallback, fallback.playback.videoUrl!!, 403).value!!
        assertEquals(fallback.client, restored.client)
        assertEquals(exhaustedCalls + 1, calls)
    }

    @Test fun scriptCacheHonorsIdentityFreshReadsExpiryAndSameOriginFetches() {
        var now = 0L
        val seen = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                seen += request
                return if (request.url.contains("/embed/")) HttpReply(200, """{"jsUrl":"/s/player/revision/base.js"}""")
                else HttpReply(200, "signatureTimestamp:42")
            }
        }
        val scripts = JvmPlayerScripts(http, { now }, ttlMs = 10)
        val context = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=fixture"))
        val first = scripts.script("video", context, false).value!!
        assertEquals("signatureTimestamp:42", first.source)
        assertSame(first, scripts.script("video", context, false).value)
        assertEquals(2, seen.size)
        scripts.script("video", context.copy(onBehalfOfUser = "brand"), false)
        assertEquals(4, seen.size)
        scripts.script("video", context, true)
        assertEquals(6, seen.size)
        now = 10
        scripts.script("video", context, false)
        assertEquals(8, seen.size)
        assertTrue(seen.all { it.method == "GET" && it.headers.isEmpty() })
        assertEquals("https://www.youtube.com/s/player/revision/base.js", seen[1].url)
        var invalidReads = 0
        val malicious = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                invalidReads++
                return HttpReply(200, """{"jsUrl":"https://outside.example/base.js"}""")
            }
        }
        assertEquals("invalid_player_script_url", JvmPlayerScripts(malicious).script("video", context, false).error!!.code)
        assertEquals(1, invalidReads)
    }

    @Test fun postConsumersSelectExplicitVoteAndPollCommandsFromTheAuthorizedPage() {
        val id = "Ugkx_contract"
        val target = "\"target\":{\"postId\":\"$id\"}"
        fun vote(status: String) = """{"likeEndpoint":{"status":"$status",$target}}"""
        val body = """{"contents":[{"backstagePostRenderer":{"postId":"$id","voteStatus":"LIKE",
          "actionButtons":{"likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${vote("LIKE")},"toggledServiceEndpoint":${vote("INDIFFERENT")}}},
            "dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${vote("DISLIKE")},"toggledServiceEndpoint":${vote("INDIFFERENT")}}}},
          "backstageAttachment":{"pollRenderer":{"totalVotes":{"simpleText":"100 votes"},"choices":[
            {"text":{"simpleText":"First"},"selected":true,"votePercentage":{"simpleText":"40%"},"votePercentageIfSelected":{"simpleText":"60%"},
              "selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"SELECT_FIRST"}},"deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"REMOVE_FIRST"}}},
            {"text":{"simpleText":"Second"},"selected":false,"selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"SELECT_SECOND"}},"deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"REMOVE_SECOND"}}}
          ]}}}}]}"""
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        val requests = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return if (request.url.contains("/browse")) HttpReply(200, body)
                else HttpReply(200, """{"status":"STATUS_SUCCEEDED"}""")
            }
        }
        val page = loadChannelPosts("UCauthor", context, params = "server-posts-tab", http = http).value!!
        val post = page.posts.single()
        assertFalse(post.authorizationRequired)
        val commands = listOf(post.like, post.unlike, post.dislike, post.undislike)
        for (command in commands) assertTrue(performPostCommand(command, context, http).value!!.confirmed)
        assertEquals(listOf("like", "removelike", "dislike", "removelike"), requests.drop(1).map { it.url.substringAfterLast('/') })
        assertTrue(requests.drop(1).all { jsonObject(it.body)!!.obj("target")!!.text("postId") == id })

        val poll = post.poll!!
        val label = poll.total + " · " + poll.choices.single { it.selected }.selectedPercentage
        assertEquals("100 votes · 60%", label)
        assertTrue(poll.selectionKnown)
        val nextChoice = poll.choices.single { !it.selected }
        assertTrue(performPostCommand(nextChoice.select, context, http).value!!.confirmed)
        assertTrue(requests.last().body.contains("SELECT_SECOND"))

        val signedOut = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"contents":[{"backstagePostRenderer":{"postId":"$id","actionButtons":{"likeButton":{
                  "toggleButtonRenderer":{"defaultServiceEndpoint":{"signInEndpoint":{}}}}}}}]}""")
        }
        val locked = loadChannelPosts("UCauthor", RequestContext(), http = signedOut).value!!.posts.single()
        assertTrue(locked.authorizationRequired)
        val callsBefore = requests.size
        assertEquals("post_web_authorization_required", performPostCommand(locked.like, RequestContext(), http).error!!.code)
        assertEquals(callsBefore, requests.size)
    }

    @Test fun menuConsumersSkipTheSelectedStateWhenChoosingAnotherServerAction() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[
                  {"toggleButtonRenderer":{"isToggled":true,"text":{"simpleText":"Current"},"defaultServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"CURRENT"}}}},
                  {"toggleButtonRenderer":{"isToggled":false,"text":{"simpleText":"Alternative"},"defaultServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"ALTERNATIVE"}}}}
                ]}}}}}""")
        }
        val actions = loadMenu(MenuEndpoint("menu", "server-menu"), RequestContext(), http).value!!
        val alternatives = actions.filter { !it.selected }
        assertEquals("Alternative", alternatives.single().title)
        assertEquals("ALTERNATIVE", alternatives.single().feedbackToken())
        assertEquals("CURRENT", actions.single { it.selected }.feedbackToken())
    }
}
