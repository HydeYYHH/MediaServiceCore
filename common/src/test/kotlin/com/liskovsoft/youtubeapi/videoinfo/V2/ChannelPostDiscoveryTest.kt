package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Default-route behavior uses service-issued capabilities, independently of owned-fragment tests. */
class ChannelPostDiscoveryTest {
    private val channel = "UC" + "a".repeat(22)
    private val other = "UC" + "b".repeat(22)
    private val post = """{"backstagePostRenderer":{"postId":"Ugkx_owned_post","contentText":{"simpleText":"Owned"}}}"""
    private val cursor = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"ISSUED_NEXT"}}}}"""
    private fun page(items: String = post) = """{"contents":[$items]}"""
    private fun tab(kind: String, selected: Boolean = false, params: String? = "opaque-$kind", id: String = channel,
        content: String? = null, title: String = "Unrelated label") = """{"tabRenderer":{"tabIdentifier":"$kind",
        "title":"$title","selected":$selected,"endpoint":{"browseEndpoint":{"browseId":"$id"${params?.let { ",\"params\":\"$it\"" }.orEmpty()}},
        "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse","url":"/channel/$channel/$kind"}}}
        ${content?.let { ",\"content\":$it" }.orEmpty()}}}"""
    private fun landing(tabs: String, id: String = channel) = """{"metadata":{"channelMetadataRenderer":{"externalId":"$id","title":"Channel"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[$tabs]}}}"""
    private fun opening(posts: String = tab("posts"), id: String = channel) = landing(tab("home", true, null,
        content = """{"richGridRenderer":{"contents":[]}}""") + "," + posts, id)
    private class Wire(val reply: (HttpRequest, Int) -> HttpReply) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return reply(request, requests.lastIndex) }
    }
    private fun read(root: String): Pair<ReadResult<PostPage>, Wire> {
        val wire = Wire { _, _ -> HttpReply(200, root) }
        return loadChannelPosts(channel, http = wire) to wire
    }
    private fun rejected(root: String, kind: RequestFailure) {
        val (result, wire) = read(root)
        assertNull(result.value); assertEquals(kind, result.error?.kind); assertEquals(1, wire.requests.size)
    }

    @Test fun defaultDiscoversOnlyTheUniqueServerPostsRouteAndDoesNotReloadTheLanding() {
        val wire = Wire { request, index ->
            val body = jsonObject(request.body)!!
            assertEquals(channel, body.text("browseId")); assertNull(body.text("continuation"))
            if (index == 0) { assertNull(body.text("params")); HttpReply(200, opening(tab("posts", params = "SERVER_OPAQUE_ROUTE"))) }
            else { assertEquals(1, index); assertEquals("SERVER_OPAQUE_ROUTE", body.text("params")); HttpReply(200, page(post + "," + cursor)) }
        }
        val loaded = loadChannelPosts(channel, http = wire)
        assertTrue(loaded.error.toString(), loaded.ok)
        assertEquals("Ugkx_owned_post", loaded.value!!.posts.single().postId)
        assertEquals("ISSUED_NEXT", loaded.value!!.nextKey); assertEquals(2, wire.requests.size)
    }

    @Test fun selectedPostsAndLegacyDirectCollectionsRemainOneRequest() {
        for (root in listOf(page(), page(""), landing(tab("posts", true, content = page(post + "," + cursor))))) {
            val (result, wire) = read(root)
            assertTrue(result.error.toString(), result.ok); assertEquals(1, wire.requests.size)
        }
    }

    @Test fun explicitParametersAndContinuationsNeverBecomeDiscoveryRequests() {
        for (params in listOf("ISSUED", "")) {
            val wire = Wire { _, _ -> HttpReply(200, page()) }
            assertTrue(loadChannelPosts(channel, params = params, http = wire).ok); assertEquals(1, wire.requests.size)
            assertEquals(params, jsonObject(wire.requests.single().body)!!.text("params"))
        }
        val continued = Wire { request, _ ->
            val body = jsonObject(request.body)!!; assertEquals("ISSUED", body.text("continuation")); assertNull(body.text("browseId"))
            HttpReply(200, """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$post]}}]}""")
        }
        assertTrue(loadChannelPosts(channel, continuation = "ISSUED", http = continued).ok); assertEquals(1, continued.requests.size)
        for (params in listOf("ISSUED", null)) {
            val wire = Wire { _, _ -> HttpReply(200, opening()) }
            val result = loadChannelPosts(channel, params = params, continuation = if (params == null) "ISSUED" else null, http = wire)
            assertEquals(RequestFailure.PARSE, result.error?.kind); assertEquals(1, wire.requests.size)
        }
    }

    @Test fun missingAmbiguousAndNonNavigablePostsAreUnsupportedWithoutTitleGuessing() {
        for (root in listOf(landing(tab("home", true)), opening(tab("posts") + "," + tab("posts", params = "OTHER")),
            opening(tab("posts", params = null)), opening(tab("future", title = "Posts")), landing("")))
            rejected(root, RequestFailure.UNSUPPORTED)
    }

    @Test fun malformedForeignAndMetadataOnlySourcesNeverLendAChannelRoute() {
        for (root in listOf("{}", """{"responseContext":${opening()}}""", opening(id = other),
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":{}}}}""",
            opening("""{"tabRenderer":{"tabIdentifier":"posts","endpoint":{"browseEndpoint":[]}}}"""),
            opening().replace("/youtubei/v1/browse", "/youtubei/v1/like/like"),
            opening().dropLast(1) + ",\"onResponseReceivedActions\":[]}"))
            rejected(root, RequestFailure.PARSE)
    }

    @Test fun discoveryRequiresDirectCanonicalServiceIdentityInsteadOfTheCallerIdFallback() {
        val root = jsonObject(opening())!!
        val bare = root.fields - "metadata"
        val matching = jsonObject("""{"c4TabbedHeaderRenderer":{"channelId":"$channel"}}""")!!
        val conflicting = jsonObject("""{"c4TabbedHeaderRenderer":{"channelId":"$other"}}""")!!
        for (bad in listOf(Json.Obj(bare), Json.Obj(bare + ("responseContext" to root.fields.getValue("metadata"))),
            Json.Obj(root.fields + ("header" to conflicting)),
            Json.Obj(root.fields + ("metadata" to Json.Str("wrong"))),
            Json.Obj(root.fields + ("metadata" to jsonObject("""{"channelMetadataRenderer":[]}""")!!)),
            Json.Obj(root.fields + ("metadata" to jsonObject("""{"channelMetadataRenderer":{"externalId":7}}""")!!)),
            Json.Obj(bare + ("header" to jsonObject("""{"pageHeaderRenderer":{"responseContext":${matching.encode()}}}""")!!)),
            Json.Obj(root.fields + ("metadata" to jsonObject("""{"channelMetadataRenderer":{"externalId":"invalid"}}""")!!))))
            rejected(bad.encode(), RequestFailure.PARSE)
        for (good in listOf(Json.Obj(bare + ("header" to matching)), Json.Obj(root.fields + ("header" to matching)))) {
            val wire = Wire { _, index -> HttpReply(200, if (index == 0) good.encode() else page()) }
            assertTrue(loadChannelPosts(channel, http = wire).ok); assertEquals(2, wire.requests.size)
        }
    }

    @Test fun selectedUnknownPostsDoNotFallThroughToUnselectedOrMetadataContent() {
        for (content in listOf("""{"contents":[{"futurePostRenderer":{}}]}""", "null", "[]")) {
            val selected = tab("posts", true, content = content)
            rejected(landing(selected + "," + tab("home", content = page()))
                .dropLast(1) + ",\"responseContext\":${page()}}", RequestFailure.PARSE)
        }
        rejected(landing(tab("posts", true, content = page()), other), RequestFailure.PARSE)
    }

    @Test fun bothRequestsKeepTheSameExplicitCookieIndexDelegationAndVisitorContext() {
        val contexts = listOf(RequestContext(), VisitorSession("VISITOR_INFO1_LIVE=fixture", "visitor").requestContext()) +
            listOf(0, 1).flatMap { index -> listOf(false, true).map { brand -> RequestContext(accountId = "owner-$index",
                webSession = WebSession("SAPISID=fixture-$index; SID=fixture", index), onBehalfOfUser = if (brand) "page" else null,
                visitor = "visitor", visitorCookie = "VISITOR_INFO1_LIVE=visitor", language = "zh", region = "TW") } }
        for (context in contexts) {
            val wire = Wire { request, index ->
                val body = jsonObject(request.body)!!; val client = body.path("context", "client")!!
                assertEquals("WEB", client.text("clientName")); assertEquals(context.language, client.text("hl")); assertEquals(context.region, client.text("gl"))
                assertEquals(context.visitor, client.text("visitorData"))
                assertEquals(context.onBehalfOfUser, body.path("context", "user")?.text("onBehalfOfUser"))
                assertEquals(context.webSession?.cookies ?: context.visitorCookie, request.headers["Cookie"])
                assertEquals(context.webSession?.accountIndex?.toString(), request.headers["X-Goog-AuthUser"])
                assertEquals(context.onBehalfOfUser, request.headers["X-Goog-PageId"])
                assertEquals(context.signedIn, !request.headers["Authorization"].isNullOrBlank())
                HttpReply(200, if (index == 0) opening() else page())
            }
            assertTrue(loadChannelPosts(channel, context, http = wire).ok); assertEquals(2, wire.requests.size)
        }
    }

    @Test fun aDiscoveredResponseCannotSwitchToAnotherChannelTabOrRetryUnknownShapes() {
        for (response in listOf(landing(tab("videos", true, content = page())), """{"futurePostCollection":$post}""")) {
            val wire = Wire { _, index -> HttpReply(200, if (index == 0) opening() else response) }
            assertEquals(RequestFailure.PARSE, loadChannelPosts(channel, http = wire).error?.kind); assertEquals(2, wire.requests.size)
        }
    }

    @Test fun defaultOpeningOrPostsTransportFailuresAreReturnedWithoutRetry() {
        for (failureAt in 0..1) {
            val wire = Wire { _, index -> if (index == failureAt) throw IOException("synthetic") else HttpReply(200, opening()) }
            assertEquals(RequestFailure.TRANSPORT, loadChannelPosts(channel, http = wire).error?.kind)
            assertEquals(failureAt + 1, wire.requests.size)
        }
        val limited = Wire { _, _ -> HttpReply(429, "{}") }
        assertEquals(429, loadChannelPosts(channel, http = limited).error?.status); assertEquals(1, limited.requests.size)
    }

    @Test fun channelHandlesUseTheirAlreadyOwnedDirectRouteEvenWithoutParameters() {
        val wire = Wire { _, index -> HttpReply(200, if (index == 0) opening(tab("posts", params = null, id = other)) else opening(id = other)) }
        val handle = ChannelSession(AccountSession(), wire).open(channel).value!!
        assertEquals(RequestFailure.PARSE, handle.openPosts().error?.kind)
        assertEquals(2, wire.requests.size); assertEquals(other, jsonObject(wire.requests.last().body)!!.text("browseId"))
    }

    @Test fun issuedPostContinuationsStayDirectAndGenerationFenced() {
        val wire = Wire { request, index ->
            HttpReply(200, when (index) {
                0 -> opening()
                1 -> page(post + "," + cursor)
                else -> { assertEquals("ISSUED_NEXT", jsonObject(request.body)!!.text("continuation")); page() }
            })
        }
        val account = AccountSession(); val handle = ChannelSession(account, wire).open(channel).value!!
        val next = handle.openPosts().value!!.next!!
        assertTrue(handle.nextPosts(next).ok); assertEquals(3, wire.requests.size)
        account.clear(); assertEquals(RequestFailure.CANCELLED, handle.nextPosts(next).error?.kind); assertEquals(3, wire.requests.size)
    }

    @Test fun emptyDiscoveredPostsRemainAnExplicitEmptyPageWithoutFallbackReads() {
        val wire = Wire { _, index -> HttpReply(200, if (index == 0) opening() else page("")) }
        val page = loadChannelPosts(channel, http = wire).value!!
        assertTrue(page.posts.isEmpty()); assertNull(page.nextKey); assertEquals(2, wire.requests.size)
    }
}
