package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CommentEntityReadTest {
    @Test fun joinsEntityViewsStateRepliesAndAttributedText() {
        val root = """{
          "contents":[{"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"parent","toolbarStateKey":"state","toolbarSurfaceKey":"surface"}},"replies":{"commentRepliesRenderer":{"contents":[{"commentViewModel":{"commentKey":"reply"}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"replies-next"}}}}]}}}}],
          "mutations":[
            {"commentEntityPayload":{"key":"reply","properties":{"commentId":"child","content":{"content":"Reply"}},"author":{"displayName":"Child"}}},
            {"commentEntityPayload":{"key":"parent","properties":{"commentId":"parent-id","content":{"content":"Hi link 😀","commandRuns":[{"startIndex":3,"length":4,"onTap":{"innertubeCommand":{"urlEndpoint":{"url":"https://example.test"}}}}],"styleRuns":[{"startIndex":0,"length":2,"fontWeight":"FONT_WEIGHT_BOLD"}],"attachmentRuns":[{"startIndex":8,"length":2,"emoji":{"emojiId":"😀","image":{"thumbnails":[{"url":"https://emoji"}]}}}]}},"author":{"displayName":"Parent","avatarThumbnailUrl":"https://avatar"},"toolbar":{"likeCountNotliked":"11","likeCountLiked":"12","replyCount":"1"}}},
            {"commentToolbarStateEntityPayload":{"key":"state","likeState":"TOOLBAR_LIKE_STATE_LIKED"}},
            {"commentToolbarSurfaceEntityPayload":{"key":"surface","likeButton":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"like"}},"toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"unlike"}}}}}
          ]} """
        val page = commentPage(root)
        assertEquals(listOf("parent-id", "child"), page.comments.map { it.id })
        val parent = page.comments.first()
        assertEquals("Hi link 😀", parent.text)
        assertEquals("https://avatar", parent.authorPhoto)
        assertTrue(parent.liked)
        assertEquals("12", parent.likeCount)
        assertEquals("unlike", parent.unlikeKey)
        assertEquals("replies-next", parent.replyKey)
        assertTrue(parent.runs.first().bold)
        assertEquals("https://example.test", parent.runs.first { it.text == "link" }.url)
        assertEquals("https://emoji", parent.runs.last().emoji)
        assertEquals("parent-id", page.comments.last().parentId)
        assertNull(page.nextKey)
    }

    @Test fun ignoresMalformedRunOffsetsWithoutLosingText() {
        val body = """{"commentEntityPayload":{"properties":{"commentId":"c","content":{"content":"😀 text","commandRuns":[{"startIndex":1,"length":999}]}}}}"""
        assertEquals("😀 text", commentPage(body).comments.single().text)
    }
}
