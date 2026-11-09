package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class CommentReadTest {
    @Test fun preservesTheCreationTokenFromTheInitialPage() {
        var requests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                return HttpReply(200, if (requests == 1)
                    """{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}},"createCommentEndpoint":{"createCommentParams":"create-key"}}"""
                else """{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentRenderer":{"commentId":"c","contentText":{"simpleText":"Hello"}}}]}}}""")
            }
        }
        val page = loadComments("abcdefghijk", RequestContext(accessToken = "access"), http = http).value!!
        assertEquals(2, requests)
        assertEquals("create-key", page.createKey)
        assertEquals("c", page.comments.single().id)
    }
    @Test fun readsRepliesAndTheDirectPageKey() {
        val body = """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[{"commentRenderer":{"commentId":"reply","contentText":{"simpleText":"Hi"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}}]}"""
        val page = commentPage(body); assertEquals("reply", page.comments.single().id); assertEquals("next", page.nextKey)
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertEquals("reply-key", jsonObject(request.body)!!.text("continuation")); return HttpReply(200, body)
        }}
        assertTrue(loadCommentReplies("reply-key", http = http).ok)
    }
    @Test fun readsEntityCommentsAndSiblingReplyTokens() {
        val old = commentPage("""{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"c"}},"replies":{"commentRepliesRenderer":{"continuationCommand":{"token":"replies"}}}}}]}}}""")
        assertEquals("replies", old.comments.single().replyKey)
        val page = commentPage("""{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{"commentEntityPayload":{"properties":{"commentId":"new","content":{"content":"New text"}},"author":{"displayName":"Ada"}}}}]}}}""")
        assertEquals("New text", page.comments.single().text)
    }

    @Test fun readsTheButtonReplyContinuationFromTheDirectContinuationRow() {
        val body = replyItems("""
            {"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"parent","replyCount":"45"}},"replies":{"commentRepliesRenderer":{"continuationCommand":{"token":"opening-key"}}}}},
            {"commentRenderer":{"commentId":"reply","contentText":{"simpleText":"Hi"}}},
            {"continuationItemRenderer":{"button":{"buttonRenderer":{"command":{"commandMetadata":{"webCommandMetadata":{"sendPost":true,"apiUrl":"/youtubei/v1/next"}},"continuationCommand":{"token":"reply-page-2","request":"CONTINUATION_REQUEST_TYPE_WATCH_NEXT"}}}}}}
        """.trimIndent())
        var requests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                assertEquals("opening-key", jsonObject(request.body)!!.text("continuation"))
                return HttpReply(200, body)
            }
        }

        val page = loadCommentReplies("opening-key", http = http).value!!
        assertEquals(1, requests)
        assertEquals(listOf("parent", "reply"), page.comments.map { it.id })
        assertEquals("opening-key", page.comments.first().replyKey)
        assertEquals("reply-page-2", page.nextKey)
    }

    @Test fun nestedReplyButtonsAndOtherThreadEntriesCannotBecomeThePageCursor() {
        val body = replyItems("""
            {"commentRenderer":{"commentId":"parent","replies":{"commentRepliesRenderer":{"contents":[
              {"continuationItemRenderer":{"button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"other-thread-key"}}}}}}
            ]}}}},
            {"buttonRenderer":{"command":{"continuationCommand":{"token":"unrelated-button-key"}}}}
        """.trimIndent())
        assertEquals("parent", commentPage(body).comments.single().id)
        assertNull(commentPage(body).nextKey)
    }

    @Test fun theExistingDirectEndpointWinsOverTheButtonContinuation() {
        val body = replyItems("""
            {"continuationItemRenderer":{
              "continuationEndpoint":{"continuationCommand":{"token":"direct-next"}},
              "button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"button-next"}}}}
            }}
        """.trimIndent())
        assertEquals("direct-next", commentPage(body).nextKey)
    }

    @Test fun emptyOrIncompleteButtonContinuationsAreIgnored() {
        for (button in listOf("{}", """{"command":{}}""", """{"command":{"continuationCommand":{}}}""",
            """{"command":{"continuationCommand":{"token":""}}}""", """{"command":{"continuationCommand":{"token":"   "}}}""")) {
            val body = """{"continuationContents":{"commentRepliesContinuation":{"contents":[{"continuationItemRenderer":{"button":{"buttonRenderer":$button}}}]}}}"""
            assertNull(commentPage(body).nextKey)
        }
    }

    private fun replyItems(items: String) = """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[$items]}}]}"""
}
