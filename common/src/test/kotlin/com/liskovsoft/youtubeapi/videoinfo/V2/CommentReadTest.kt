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
}
