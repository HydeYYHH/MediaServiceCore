package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Anonymous WEB posts retain their video attachment metadata. */
class PostVideoAttachmentTest {
    private val channel = "UC" + "a".repeat(22)
    private val otherChannel = "UC" + "b".repeat(22)
    private val id = "Ugkx_video_post"
    private val videoId = "abcDEF123_-"
    private fun byline(owner: String = channel) = """{"runs":[{"text":"Video author","navigationEndpoint":{"browseEndpoint":{"browseId":"$owner","canonicalBaseUrl":"/@fixture"}}}]}"""
    private fun video() = """{"videoId":"$videoId","trackingParams":"TRACKING",
        "thumbnail":{"thumbnails":[{"url":"//i.example/small.jpg"},{"url":"//i.example/large.jpg"}]},
        "title":{"runs":[{"text":"Attached title"}],"accessibility":{}},
        "descriptionSnippet":{"runs":[{"text":"Description"}]},
        "longBylineText":${byline()},"shortBylineText":${byline()},"ownerText":${byline()},
        "publishedTimeText":{"simpleText":"Yesterday"},"lengthText":{"simpleText":"12:34","accessibility":{}},
        "viewCountText":{"simpleText":"42 views"},"shortViewCountText":{"simpleText":"42","accessibility":{}},
        "navigationEndpoint":{"clickTrackingParams":"CLICK","commandMetadata":{"webCommandMetadata":{"url":"/watch?v=$videoId"}},
            "watchEndpoint":{"videoId":"$videoId","watchEndpointSupportedOnesieConfig":{"html5PlaybackOnesieConfig":{}}}},
        "showActionMenu":true,"ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}],
        "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{}},{"thumbnailOverlayToggleButtonRenderer":{}},
            {"thumbnailOverlayToggleButtonRenderer":{}},{"thumbnailOverlayNowPlayingRenderer":{}}],
        "menu":{"menuRenderer":{}},"channelThumbnailSupportedRenderers":{"channelThumbnailWithLinkRenderer":{}},
        "avatar":{"decoratedAvatarViewModel":{}}}"""
    private fun post(attachment: String = """{"videoRenderer":${video()}}""", extra: String = "") =
        """{"backstagePostRenderer":{"postId":"$id","authorEndpoint":{"browseEndpoint":{"browseId":"$channel"}},
        "authorText":{"simpleText":"Post author"},"contentText":{"simpleText":"Post body"},"voteCount":{"simpleText":"8"},
        "backstageAttachment":$attachment$extra}}"""
    private fun page(slot: String = post()) = """{"contents":[$slot]}"""
    private fun tab(kind: String, selected: Boolean, content: String? = null) = """{"tabRenderer":{"title":"Tab",
        "tabIdentifier":"$kind","selected":$selected,"endpoint":{"browseEndpoint":{"browseId":"$channel","params":"ISSUED_$kind"},
        "commandMetadata":{"webCommandMetadata":{"url":"/channel/$channel/$kind","apiUrl":"/youtubei/v1/browse"}}}
        ${content?.let { ",\"content\":$it" }.orEmpty()}}}"""
    private fun landing(selectedPosts: Boolean) = """{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","title":"Fixture"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[${tab("home", !selectedPosts, "{\"contents\":[]}")},
        ${tab("posts", selectedPosts, if (selectedPosts) page() else null)}]}}}"""
    private fun read(body: String): ReadResult<PostPage> {
        var calls = 0
        val result = loadChannelPosts(channel, params = "ISSUED", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++; assertEquals("/youtubei/v1/browse", java.net.URI(request.url).path)
                return HttpReply(200, body)
            }
        })
        assertEquals(1, calls)
        return result
    }
    private fun rejected(value: String) {
        val result = read(page(post("""{"videoRenderer":$value}""")))
        assertEquals(RequestFailure.PARSE, result.error?.kind); assertEquals("unknown_post_page", result.error?.code)
        assertNull(result.value)
    }
    private fun displayOnly(item: MediaItem) {
        assertEquals("video", item.kind); assertEquals(videoId, item.id)
        assertEquals("Attached title", item.title); assertEquals("Video author", item.author)
        assertEquals("https://i.example/large.jpg", item.thumbnail); assertEquals("12:34", item.duration)
        assertEquals(channel, item.channelId); assertEquals("42 views", item.viewCount); assertEquals("Yesterday", item.published)
        assertNull(item.endpoint); assertTrue(item.actions.isEmpty()); assertNull(item.menuEndpoint)
        assertNull(item.post); assertNull(item.playlistId); assertNull(item.params); assertNull(item.playerParams)
    }

    @Test fun defaultChannelRouteDiscoversPostsAndRetainsTheDirectVideoPresentation() {
        val bodies = mutableListOf<String>()
        val result = loadChannelPosts(channel, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("POST", request.method); assertEquals("/youtubei/v1/browse", java.net.URI(request.url).path)
                val body = jsonObject(request.body)!!; bodies += request.body
                assertEquals(channel, body.text("browseId")); assertNull(body.text("continuation"))
                if (bodies.size == 1) { assertNull(body.text("params")); return HttpReply(200, landing(false)) }
                assertEquals(2, bodies.size); assertEquals("ISSUED_posts", body.text("params"))
                return HttpReply(200, landing(true))
            }
        })
        assertTrue(result.ok); assertEquals(2, bodies.size)
        val post = result.value!!.posts.single()
        assertEquals(id, post.postId); assertEquals("Post author", post.author); assertEquals(channel, post.authorId)
        assertEquals("Post body", post.text); assertEquals("8", post.likes); assertTrue(post.images.isEmpty())
        assertNull(post.poll); assertNull(post.like); assertFalse(post.authorizationRequired)
        assertNull(result.value!!.nextKey); displayOnly(post.video!!)
    }

    @Test fun directOwnedAttachmentAlsoSurvivesAnExactRequestedPostDocument() {
        var calls = 0
        val result = loadPost(id, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++; assertEquals("GET", request.method); assertEquals("/post/$id", java.net.URI(request.url).path)
                return HttpReply(200, "<script>var ytInitialData = " + page() + ";</script>")
            }
        })
        assertTrue(result.ok); assertEquals(1, calls); displayOnly(result.value!!.posts.single().video!!)
        assertNull(result.value!!.nextKey); assertNull(result.value!!.commentsKey)
    }

    @Test fun directVideoDoesNotBorrowMenuPostCommandsOrOuterContinuation() {
        val foreign = """{"backstagePostRenderer":{"postId":"Ugkx_foreign"},"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NOT_A_POST_CURSOR"}}},
            "likeButton":{"defaultServiceEndpoint":{"likeEndpoint":{"status":"LIKE","target":{"postId":"$id"}}}},
            "performCommentActionEndpoint":{"action":"NOT_A_POST_COMMAND"},"signInEndpoint":{}}"""
        val nested = video().replace("\"menuRenderer\":{}", "\"menuRenderer\":$foreign")
            .replace("\"avatar\":{\"decoratedAvatarViewModel\":{}}", "\"avatar\":{\"decoratedAvatarViewModel\":$foreign}")
        val like = """, "actionButtons":{"likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{
            "likeEndpoint":{"status":"LIKE","target":{"postId":"$id"},"likeParams":"OWN_POST_LIKE"}}}}}"""
        val result = read(page(post("""{"videoRenderer":$nested}""", like)))
        assertTrue(result.ok); assertEquals(1, result.value!!.posts.size)
        val post = result.value!!.posts.single(); displayOnly(post.video!!)
        assertEquals("like/like", post.like!!.endpoint); assertTrue(post.like!!.payload.contains("OWN_POST_LIKE"))
        assertFalse(post.like!!.payload.contains("NOT_A_POST")); assertNull(post.unlike); assertNull(post.poll)
        assertFalse(post.authorizationRequired); assertNull(result.value!!.nextKey)
    }

    @Test fun videoMustBeTheSingleDirectAttachmentRatherThanAMetadataDescendant() {
        val noVideo = """{"backstagePostRenderer":{"postId":"$id","responseContext":{"backstageAttachment":{"videoRenderer":${video()}}}}}"""
        val result = read(page(noVideo)); assertTrue(result.ok); assertNull(result.value!!.posts.single().video)
        val mixed = read(page(post("""{"videoRenderer":${video()},"pollRenderer":{"choices":[]}}""")))
        assertEquals(RequestFailure.PARSE, mixed.error?.kind)
        val unknown = read(page(post("""{"futureVideoRenderer":${video()}}""")))
        assertEquals(RequestFailure.PARSE, unknown.error?.kind)
        val foreign = """{"contents":[],"responseContext":${page()}}"""
        assertTrue(read(foreign).ok); assertTrue(read(foreign).value!!.posts.isEmpty())
    }

    @Test fun malformedUnknownAndConflictingVideoSourcesRemainTypedParseFailures() {
        val original = video()
        val bad = listOf(
            original.replace("\"videoId\":\"$videoId\"", "\"videoId\":17"),
            original.replace("\"videoId\":\"$videoId\"", "\"videoId\":\"bad\""),
            original.replace("\"watchEndpoint\":{\"videoId\":\"$videoId\"", "\"watchEndpoint\":{\"videoId\":\"xyzXYZ789_-\""),
            original.replace("\"showActionMenu\":true", "\"showActionMenu\":{}"),
            original.replace("\"menu\":{\"menuRenderer\":{}}", "\"menu\":[]"),
            original.replace("\"ownerBadges\":[{\"metadataBadgeRenderer\":{\"style\":\"BADGE_STYLE_TYPE_VERIFIED\"}}]", "\"ownerBadges\":{}"),
            original.replace("\"thumbnailOverlays\":[", "\"thumbnailOverlays\":[17,"),
            original.replace("\"watchEndpointSupportedOnesieConfig\":{\"html5PlaybackOnesieConfig\":{}}", "\"watchEndpointSupportedOnesieConfig\":[]"),
            original.replace("\"shortBylineText\":" + byline(), "\"shortBylineText\":" + byline(otherChannel)),
            original.replace("\"avatar\":", "\"futureRenderer\":{},\"avatar\":"),
            original.replace("\"avatar\":", "\"futureScalar\":true,\"avatar\":"),
            original.replace("\"url\":\"/watch?v=$videoId\"", "\"url\":\"/watch?v=$videoId\",\"apiUrl\":\"/youtubei/v1/like/like\""),
            original.replace("\"browseId\":\"$channel\"", "\"browseId\":17"),
            original.replace("\"thumbnail\":{\"thumbnails\":[{\"url\":\"//i.example/small.jpg\"},{\"url\":\"//i.example/large.jpg\"}]}", "\"thumbnail\":[]"))
        for ((index, value) in bad.withIndex()) { assertNotEquals("Fixture $index did not change", original, value); assertNotNull("Invalid fixture $index", jsonObject(value)); rejected(value) }
        rejected("[]"); rejected("null")
    }

    @Test fun pureFragmentDirectVideoCompatibilityAndDataCopyRemainExplicit() {
        val item = postItem(jsonObject(post())!!.obj("backstagePostRenderer")!!, channel)!!
        displayOnly(item.video!!)
        assertSame(item.video, item.component17())
        val copied = item.copy(text = "Updated body"); assertEquals(item.video, copied.video)
        assertNull(item.copy(video = null).video); assertEquals(item.postId, copied.postId)
        val bad = postItem(jsonObject(post("""{"videoRenderer":{"videoId":"bad"}}"""))!!.obj("backstagePostRenderer")!!, channel)!!
        assertNull(bad.video)
        val nestedOnly = postItem(jsonObject("""{"postId":"$id","backstageAttachment":{"responseContext":{"videoRenderer":${video()}}}}""")!!, channel)!!
        assertNull(nestedOnly.video)
        val old = PostItem(null, id, null, null, null, null, emptyList(), null, null, null, null, null, null, null, null, false)
        assertNull(old.video)
    }
}
