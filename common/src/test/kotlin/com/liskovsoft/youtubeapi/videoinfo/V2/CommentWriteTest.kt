package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentWriteTest {
    @Test
    fun likesWithTheLikeKey() {
        var called = false
        val ok = likeComment(comment(false), "visitor-1") { query ->
            called = true
            assertEquals("1", query.headers["X-Youtube-Client-Name"])
            assertEquals("2.20260907.06.00", query.headers["X-Youtube-Client-Version"])
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            assertTrue(query.url.contains("/comment/perform_comment_action"))
            assertTrue(query.body.contains("\"actions\":[\"LIKE\"]"))
            Pair(200, "")
        }
        assertTrue(called)
        assertTrue(ok)
    }

    @Test
    fun likedCommentSendsTheUnlikeKey() {
        val ok = likeComment(comment(true), null) { query ->
            assertTrue(query.body.contains("\"actions\":[\"UNLIKE\"]"))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun deletesWithTheDeleteKey() {
        val ok = deleteComment(comment(false), null) { query ->
            assertTrue(query.url.contains("/comment/perform_comment_action"))
            assertTrue(query.body.contains("\"actions\":[\"DELETE\"]"))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun createsACommentAndEscapesQuotes() {
        val ok = createComment("say \"hi\"", "CREATE", null) { query ->
            assertTrue(query.url.contains("/comment/create_comment?"))
            assertTrue(query.body.contains("\"commentText\":\"say \\\"hi\\\"\""))
            assertTrue(query.body.contains("\"createCommentParams\":\"CREATE\""))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun replies() {
        val ok = replyComment("answer", "REPLY_CREATE", null) { query ->
            assertTrue(query.url.contains("/comment/create_comment_reply"))
            assertTrue(query.body.contains("\"commentText\":\"answer\""))
            assertTrue(query.body.contains("\"createReplyParams\":\"REPLY_CREATE\""))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun dislikesWithTheDislikeKey() {
        val ok = dislikeComment(comment(false), null) { query ->
            assertTrue(query.url.contains("/comment/perform_comment_action"))
            assertTrue(query.body.contains("\"actions\":[\"DISLIKE\"]"))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun dislikedCommentSendsTheUndislikeKey() {
        val ok = dislikeComment(comment(false, true), null) { query ->
            assertTrue(query.body.contains("\"actions\":[\"UNDISLIKE\"]"))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun emptyKeySkipsPost() {
        var called = false
        val ok = deleteComment(comment(false).copy(deleteKey = null), null) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(ok)
        called = false
        val missingDislike = dislikeComment(comment(false).copy(dislikeKey = null), null) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(missingDislike)
    }

    @Test
    fun blankTextSkipsPost() {
        var called = false
        val ok = createComment(" \n", "CREATE", null) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(ok)
        called = false
        val missing = replyComment("answer", null, null) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(missing)
    }

    private fun comment(liked: Boolean, disliked: Boolean = false) = Comment(
        "abc",
        "Ada",
        "hello",
        "2 days ago",
        "12",
        liked,
        "REPLY",
        "https://yt3.ggpht.com/a",
        "LIKE",
        "UNLIKE",
        "DELETE",
        "REPLY_CREATE",
        emptyList(),
        "DISLIKE",
        "UNDISLIKE",
        disliked
    )
}
