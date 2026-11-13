package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

import org.junit.Assert.*
import org.junit.Test

/** Video attachments retain player parameters from the posts response. */
class PostVideoPlayerParamsTest {
    private val channel = "UC" + "a".repeat(22)
    private fun obj(vararg fields: Pair<String, Json>) = Json.Obj(mapOf(*fields))
    private fun str(value: String) = Json.Str(value)
    private fun Json.Obj.with(vararg values: Pair<String, Json>) = Json.Obj(fields + values)
    private fun videoId(index: Int = 0) = "abcdefghij$index"
    private fun video(index: Int = 0): Json.Obj = obj(
        "videoId" to str(videoId(index)),
        "title" to obj("simpleText" to str("Attached video")),
        "navigationEndpoint" to obj("watchEndpoint" to obj(
            "videoId" to str(videoId(index)),
            "watchEndpointSupportedOnesieConfig" to obj("html5PlaybackOnesieConfig" to obj()))))
    private fun withWatch(video: Json.Obj, update: (Json.Obj) -> Json.Obj): Json.Obj {
        val navigation = video.obj("navigationEndpoint")!!
        return video.with("navigationEndpoint" to navigation.with("watchEndpoint" to update(navigation.obj("watchEndpoint")!!)))
    }
    private fun post(video: Json.Obj, index: Int = 0): Json.Obj = obj("backstagePostThreadRenderer" to
        obj("post" to obj("backstagePostRenderer" to obj(
            "postId" to str("Ugkx_player_params_$index"),
            "contentText" to obj("simpleText" to str("Fixture post")),
            "backstageAttachment" to obj("videoRenderer" to video)))))
    private fun page(videos: List<Json.Obj>): Json.Obj {
        val section = obj("itemSectionRenderer" to obj(
            "targetId" to str("backstage-item-section"), "sectionIdentifier" to str("backstage-item-section"),
            "contents" to Json.Arr(videos.mapIndexed { index, video -> post(video, index) })))
        val tab = obj("tabIdentifier" to str("FEposts"), "selected" to Json.Bool(true),
            "endpoint" to obj(
                "browseEndpoint" to obj("browseId" to str(channel), "params" to str("ISSUED_FIXTURE_POSTS")),
                "commandMetadata" to obj("webCommandMetadata" to obj(
                    "url" to str("/channel/$channel/posts"), "apiUrl" to str("/youtubei/v1/browse")))),
            "content" to obj("sectionListRenderer" to obj("contents" to Json.Arr(listOf(section)))))
        return obj(
            "metadata" to obj("channelMetadataRenderer" to obj("externalId" to str(channel), "title" to str("Fixture"))),
            "contents" to obj("twoColumnBrowseResultsRenderer" to obj("tabs" to Json.Arr(listOf(obj("tabRenderer" to tab))))))
    }
    private fun read(body: Json.Obj): ReadResult<PostPage> {
        var calls = 0
        val result = loadChannelPosts(channel, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertEquals(1, calls)
                assertEquals("POST", request.method)
                assertEquals("/youtubei/v1/browse", URI(request.url).path)
                val query = jsonObject(request.body)!!
                assertEquals(channel, query.text("browseId"))
                assertNull(query.text("params")); assertNull(query.text("continuation"))
                return HttpReply(200, body.encode())
            }
        })
        assertEquals(1, calls)
        return result
    }
    private fun rejected(video: Json.Obj) {
        val result = read(page(listOf(video)))
        assertEquals(RequestFailure.PARSE, result.error?.kind)
        assertEquals("unknown_post_page", result.error?.code)
        assertNull(result.value)
    }
    private fun displayOnly(post: PostItem, expectedId: String) {
        val item = post.video!!
        assertEquals("video", item.kind); assertEquals(expectedId, item.id)
        assertEquals("Attached video", item.title)
        assertNull(item.channelId); assertNull(item.author)
        assertNull(item.params); assertNull(item.playerParams); assertNull(item.playlistId)
        assertNull(item.endpoint); assertTrue(item.actions.isEmpty()); assertNull(item.menuEndpoint)
        assertNull(item.startSeconds); assertNull(item.progressPercent); assertNull(item.post)
        assertNull(post.like); assertNull(post.unlike); assertNull(post.dislike); assertNull(post.undislike)
        assertNull(post.poll); assertNull(post.quiz); assertFalse(post.authorizationRequired)
    }

    @Test fun anIssuedSelectedPostsReplyRetainsAllFiveVideosWithOnlyOnePlayerParamsDeclaration() {
        val videos = (0..4).map { index ->
            if (index == 3) withWatch(video(index)) { it.with("playerParams" to str("OPAQUE_FIXTURE_PRESENTATION")) }
            else video(index)
        }
        val result = read(page(videos))
        assertTrue(result.ok); assertNull(result.error)
        assertEquals((0..4).map(::videoId), result.value!!.posts.map { it.video!!.id })
        result.value!!.posts.forEachIndexed { index, post -> displayOnly(post, videoId(index)) }
        assertNull(result.value!!.nextKey); assertNull(result.value!!.commentsKey)
    }

    @Test fun opaquePlayerParamsAndOnesieDescendantsCannotSupplyIdsCommandsOrPlaybackState() {
        val shadow = """{"videoId":"otherVideo0","params":"SHADOW","playerParams":"SHADOW","playlistId":"SHADOW","startTimeSeconds":99,"navigationEndpoint":{"likeEndpoint":{"status":"LIKE"}},"performCommentActionEndpoint":{"action":"SHADOW"},"continuationCommand":{"token":"SHADOW"},"url":"https://foreign.invalid/"}"""
        val video = withWatch(video()) { it.with(
            "playerParams" to str(shadow),
            "watchEndpointSupportedOnesieConfig" to obj(
                "videoId" to str("otherVideo0"),
                "backstagePostRenderer" to obj("postId" to str("Ugkx_shadow")),
                "performCommentActionEndpoint" to obj("action" to str("SHADOW")))) }
        val result = read(page(listOf(video)))
        assertTrue(result.ok); assertEquals(1, result.value!!.posts.size)
        displayOnly(result.value!!.posts.single(), videoId())
        assertEquals("Ugkx_player_params_0", result.value!!.posts.single().postId)
        assertNull(result.value!!.nextKey)
    }

    @Test fun everyNonStringPlayerParamsDeclarationRemainsATypedParseFailure() {
        for (value in listOf(Json.Null, Json.Bool(true), Json.Num("17"), Json.Arr(emptyList()), obj())) {
            rejected(withWatch(video()) { it.with("playerParams" to value) })
        }
    }

    @Test fun absentEmptyAndWhitespacePlayerParamsAreDiscardedWithoutAddingAParameterCapability() {
        val absent = video()
        val empty = withWatch(video(1)) { it.with("playerParams" to str("")) }
        val whitespace = withWatch(video(2)) { it.with("playerParams" to str(" \t\n")) }
        val result = read(page(listOf(absent, empty, whitespace)))
        assertTrue(result.ok)
        result.value!!.posts.forEachIndexed { index, post -> displayOnly(post, videoId(index)) }
    }

    @Test fun otherUnprovenWatchFieldsStayClosedEvenAlongsideAValidPlayerParamsString() {
        for (name in listOf("params", "playlistId", "index", "startTimeSeconds", "loggingContext",
            "watchEndpointSupportedPrefetchConfig", "playerExtraUrlParams", "futureField")) {
            rejected(withWatch(video()) { it.with("playerParams" to str("OPAQUE"), name to str("UNPROVEN")) })
        }
    }

    @Test fun directTypeAndIdentityFailuresCannotBeRepairedByOpaqueOrMetadataShadows() {
        val wrongType = withWatch(video()) { it.with(
            "playerParams" to obj("playerParams" to str("SHADOW_VALID_STRING")),
            "responseContext" to obj("playerParams" to str("SHADOW_VALID_STRING"))) }
        rejected(wrongType)
        val mismatched = withWatch(video()) { it.with(
            "videoId" to str("otherVideo0"),
            "playerParams" to str("{\"videoId\":\"${videoId()}\"}"),
            "watchEndpointSupportedOnesieConfig" to obj("videoId" to str(videoId()))) }
        rejected(mismatched)
        val invalidOnesie = withWatch(video()) { it.with(
            "playerParams" to str("OPAQUE"), "watchEndpointSupportedOnesieConfig" to Json.Arr(emptyList())) }
        rejected(invalidOnesie)
    }

    @Test fun aDeclaredForeignWatchAncestorRouteIsStillRejectedBeforeReturningTheDisplayItem() {
        val video = withWatch(video()) { it.with("playerParams" to str("OPAQUE")) }
        val foreign = video.with("navigationEndpoint" to video.obj("navigationEndpoint")!!.with(
            "commandMetadata" to obj("webCommandMetadata" to obj("apiUrl" to str("/youtubei/v1/like/like")))))
        rejected(foreign)
    }

    @Test fun exactRequestedPostDocumentUsesTheSameGuardWithoutReturningPlaybackParameters() {
        val video = withWatch(video()) { it.with("playerParams" to str("OPAQUE")) }
        val postId = "Ugkx_player_params_0"
        var calls = 0
        val result = loadPost(postId, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++; assertEquals(1, calls)
                assertEquals("GET", request.method)
                assertEquals("/post/$postId", URI(request.url).path)
                val body = obj("contents" to Json.Arr(listOf(post(video)))).encode()
                return HttpReply(200, "<script>var ytInitialData = $body;</script>")
            }
        })
        assertEquals(1, calls); assertTrue(result.ok)
        displayOnly(result.value!!.posts.single(), videoId())
        assertNull(result.value!!.nextKey); assertNull(result.value!!.commentsKey)
    }
}
