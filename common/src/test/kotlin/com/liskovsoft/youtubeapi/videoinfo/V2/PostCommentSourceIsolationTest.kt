package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Public network entry points must scope comments before calling the permissive fragment parser. */
class PostCommentSourceIsolationTest {
    private class RecordingHttp(private val body: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, body)
        }
    }

    private val metadata = """{
        "commentRenderer":{"commentId":"metadata-comment","authorText":{"simpleText":"Metadata author"},
            "contentText":{"simpleText":"Metadata text"},"createReplyParams":"METADATA_REPLY"},
        "commentsHeaderRenderer":{"countText":{"simpleText":"999 metadata comments"},
            "createRenderer":{"commentSimpleboxRenderer":{"createCommentEndpoint":{"createCommentParams":"METADATA_HEADER_CREATE"}}}},
        "createCommentParams":"METADATA_BARE_CREATE",
        "createCommentEndpoint":{"createCommentParams":"METADATA_ENDPOINT_CREATE"},
        "sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Metadata order",
            "serviceEndpoint":{"continuationCommand":{"token":"METADATA_SORT"}}}]},
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"METADATA_NEXT"}}},
        "itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"METADATA_OPEN"}}}}
        ]}
    }"""

    private fun collection(items: String, extra: String = "") = """{
        "responseContext":$metadata,
        "continuationContents":{"itemSectionContinuation":{"contents":[$items]}}$extra
    }"""
    private fun opening(contents: String, extra: String = "") = """<script>var ytInitialData = {
        "responseContext":$metadata,"contents":[$contents]$extra
    };</script>"""
    private val post = """{"backstagePostRenderer":{"postId":"Ugkx_owned_post","contentText":{"simpleText":"Owned post"}}}"""
    private val ownedComment = """{"commentThreadRenderer":{"comment":{"commentRenderer":{
        "commentId":"owned-comment","authorText":{"simpleText":"Owned author"},"contentText":{"simpleText":"Owned text"},
        "actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"OWNED_LIKE"}}
        }}}}
    }}}}"""
    private val ownedHeader = """{"commentsHeaderRenderer":{
        "countText":{"simpleText":"2 owned comments"},
        "createRenderer":{"commentSimpleboxRenderer":{"submitButton":{"buttonRenderer":{"serviceEndpoint":{
            "createCommentEndpoint":{"createCommentParams":"OWNED_CREATE"}
        }}}}},
        "sortMenu":{"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Owned order","selected":true,
            "serviceEndpoint":{"continuationCommand":{"token":"OWNED_SORT"}}
        }]}}
    }}"""
    private val ownedNext = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"OWNED_NEXT"}}}}"""

    @Test fun aPostOpeningCannotBorrowItsCommentsRouteFromMetadata() {
        val http = RecordingHttp(opening(post))
        val result = loadPost("Ugkx_owned_post", http)
        assertTrue(result.ok)
        assertEquals("Ugkx_owned_post", result.value!!.posts.single().postId)
        assertNull(result.value!!.commentsKey)
        assertNull(result.value!!.nextKey)
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests.single().method)
    }

    @Test fun aDirectMarkedPostSectionWinsOverMetadataAndUnrelatedContinuations() {
        val section = """{"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[$ownedNext]}}"""
        val unrelated = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"UNRELATED_POSTS"}}}}"""
        for (context in listOf(RequestContext(), RequestContext(webSession = WebSession("SAPISID=synthetic")))) {
            val http = RecordingHttp(opening("$post,$unrelated,$section"))
            val result = loadPost("Ugkx_owned_post", context, http)
            assertTrue(result.ok)
            assertEquals("OWNED_NEXT", result.value!!.commentsKey)
            assertNull(result.value!!.nextKey)
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun unknownBranchesAndUnmarkedSectionsCannotSupplyPostCommentRoutes() {
        val marker = """{"sectionIdentifier":"comment-item-section","contents":[$ownedNext]}"""
        val variants = listOf(
            opening("$post,$ownedNext"),
            opening(post, """, "unrelatedRenderer":{"itemSectionRenderer":$marker}"""),
            opening("""$post,{"itemSectionRenderer":{"contents":[$ownedNext]}}""")
        )
        for (html in variants) {
            val http = RecordingHttp(html)
            val result = loadPost("Ugkx_owned_post", http)
            assertTrue(result.ok)
            assertNull(result.value!!.commentsKey)
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun metadataWithoutAnOwnedCollectionCannotMintPostCommentsOrCapabilities() {
        for (field in listOf("responseContext", "rendererContext", "loggingContext")) {
            val http = RecordingHttp("""{"$field":$metadata}""")
            val result = loadPostComments("issued-post-key", http = http)
            assertFalse(result.ok)
            assertEquals(RequestFailure.PARSE, result.error?.kind)
            assertNull(result.value)
            assertEquals(1, http.requests.size)
            assertTrue(http.requests.single().url.contains("/youtubei/v1/browse"))
        }
    }

    @Test fun anOwnedEmptyCollectionDoesNotBorrowMetadataCommentCreateSortCursorOrCount() {
        val http = RecordingHttp(collection(""))
        val result = loadPostComments("issued-post-key", http = http)
        assertTrue(result.ok)
        val page = result.value!!
        assertTrue(page.comments.isEmpty())
        assertNull(page.createKey)
        assertNull(page.nextKey)
        assertTrue(page.sortOptions.isEmpty())
        assertNull(page.total)
        assertEquals(1, http.requests.size)
    }

    @Test fun ownedLegacyRecordsAndHeaderKeepTheirCapabilitiesInsteadOfMetadataValues() {
        val http = RecordingHttp(collection("$ownedHeader,$ownedComment,$ownedNext"))
        val context = RequestContext(webSession = WebSession("SAPISID=synthetic"))
        val result = loadPostComments("issued-post-key", context, http)
        assertTrue(result.ok)
        val page = result.value!!
        val comment = page.comments.single()
        assertEquals("owned-comment", comment.id)
        assertEquals("Owned author", comment.author)
        assertEquals("Owned text", comment.text)
        assertEquals("OWNED_LIKE", comment.likeKey)
        assertNull(comment.replyCreateKey)
        assertEquals("OWNED_CREATE", page.createKey)
        assertEquals("OWNED_NEXT", page.nextKey)
        assertEquals("2 owned comments", page.total)
        assertEquals(listOf("OWNED_SORT"), page.sortOptions.map { it.key })
        assertTrue(page.sortOptions.single().selected)
        assertEquals(1, http.requests.size)
        assertTrue(http.requests.single().body.contains("\"clientName\":\"WEB\""))
        assertEquals("SAPISID=synthetic", http.requests.single().headers["Cookie"])
    }

    @Test fun explicitOwnedHeaderAndBodyCommandsKeepTheHeaderCountAndBodyOrder() {
        val body = """{"responseContext":$metadata,"onResponseReceivedEndpoints":[
            {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER","targetId":"comments-section-header",
                "continuationItems":[$ownedHeader]}},
            {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","targetId":"comments-section",
                "continuationItems":[$ownedComment,$ownedNext]}}
        ]}"""
        val http = RecordingHttp(body)
        val result = loadPostComments("issued-post-key", http = http)
        assertTrue(result.ok)
        assertEquals(listOf("owned-comment"), result.value!!.comments.map { it.id })
        assertEquals("OWNED_CREATE", result.value!!.createKey)
        assertEquals("OWNED_NEXT", result.value!!.nextKey)
        assertEquals("2 owned comments", result.value!!.total)
        assertEquals(1, http.requests.size)
    }

    @Test fun anOwnedBodyWithoutAHeaderDoesNotBorrowTheMetadataCountOrCreationToken() {
        val http = RecordingHttp(collection(ownedComment))
        val result = loadPostComments("issued-post-key", http = http)
        assertTrue(result.ok)
        assertEquals("owned-comment", result.value!!.comments.single().id)
        assertNull(result.value!!.total)
        assertNull(result.value!!.createKey)
        assertNull(result.value!!.nextKey)
        assertTrue(result.value!!.sortOptions.isEmpty())
        assertEquals(1, http.requests.size)
    }

    @Test fun postCommentOpeningsKeepTheAbsentOrExplicitBrowseRoute() {
        for (path in listOf(null, "/youtubei/v1/browse")) {
            val declared = path?.let { "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"$it\"}}," }.orEmpty()
            val route = """{"continuationItemRenderer":{"continuationEndpoint":{
                $declared"continuationCommand":{"token":"POST_COMMENTS"}
            }}}"""
            val section = """{"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[$route]}}"""
            val http = RecordingHttp(opening("$post,$section"))
            val result = loadPost("Ugkx_owned_post", http)
            assertTrue(result.ok)
            assertEquals("POST_COMMENTS", result.value!!.commentsKey)
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun aDeclaredNextRouteCannotBeReinterpretedAsPostCommentsBrowse() {
        val route = """{"continuationItemRenderer":{"continuationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/next"}},
            "continuationCommand":{"token":"NEXT_ONLY_COMMENTS"}
        }}}"""
        val section = """{"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[$route]}}"""
        val http = RecordingHttp(opening("$post,$section"))
        val result = loadPost("Ugkx_owned_post", http)
        assertFalse(result.ok)
        assertNull(result.value)
        assertEquals(RequestFailure.UNSUPPORTED, result.error?.kind)
        assertEquals("unsupported_post_comment_route", result.error?.code)
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests.single().method)
    }
}
