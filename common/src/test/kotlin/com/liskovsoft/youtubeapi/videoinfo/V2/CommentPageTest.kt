package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPageTest {
    private val watch = """
        {"engagementPanels":[{"engagementPanelSectionListRenderer":{
          "panelIdentifier":"engagement-panel-comments-section",
          "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMMENT_KEY"}}}}
          ]}}]}}
        }}]}
    """
    private val page = """
        {"continuationContents":{"itemSectionContinuation":{
          "contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{
            "commentId":"abc",
            "authorText":{"simpleText":"Ada"},
            "contentText":{"runs":[{"text":"hello "},{"text":"world"}]},
            "publishedTimeText":{"simpleText":"2 days ago"},
            "voteCount":{"simpleText":"12"},
            "isLiked":true,
            "authorThumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a"}]},
            "replies":{"commentRepliesRenderer":{"contents":[
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLY"}}}}
            ]}}
          }}}}],
          "continuations":[{"nextContinuationData":{"continuation":"NEXT"}}]
        }}}
    """

    @Test
    fun readsTheCommentToken() {
        assertEquals("COMMENT_KEY", commentKey(watch))
        assertNull(commentKey("{}"))
    }

    @Test
    fun readsACommentPage() {
        val parsed = commentPage(page)
        assertEquals("NEXT", parsed.nextKey)
        assertEquals(1, parsed.comments.size)
        val comment = parsed.comments[0]
        assertEquals("abc", comment.id)
        assertEquals("Ada", comment.author)
        assertEquals("hello world", comment.text)
        assertEquals("2 days ago", comment.published)
        assertEquals("12", comment.likeCount)
        assertTrue(comment.liked)
        assertEquals("REPLY", comment.replyKey)
        assertEquals("https://yt3.ggpht.com/a", comment.authorPhoto)
    }

    @Test
    fun loadsTheWatchPageThenTheCommentPage() {
        val bodies = ArrayList<String>()
        val parsed = loadComments("dQw4w9WgXcQ", "visitor-1") { query ->
            bodies.add(query.body)
            assertEquals("1", query.headers["X-Youtube-Client-Name"])
            assertEquals("2.20260907.06.00", query.headers["X-Youtube-Client-Version"])
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            assertTrue(query.url.contains("/youtubei/v1/next"))
            if (bodies.size == 1) Pair(200, watch) else Pair(200, page)
        }
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contains("\"videoId\":\"dQw4w9WgXcQ\""))
        assertFalse(bodies[0].contains("continuation"))
        assertTrue(bodies[1].contains("\"continuation\":\"COMMENT_KEY\""))
        assertEquals("hello world", parsed.comments[0].text)
        assertEquals("NEXT", parsed.nextKey)
    }

    @Test
    fun missingPanelDoesNotAskForComments() {
        var calls = 0
        val parsed = loadComments("dQw4w9WgXcQ", null) {
            calls++
            Pair(200, "{}")
        }
        assertEquals(1, calls)
        assertTrue(parsed.comments.isEmpty())
        assertNull(parsed.nextKey)
    }

    @Test
    fun badVideoIdSkipsPost() {
        var called = false
        val parsed = loadComments("bad id", null) {
            called = true
            Pair(200, watch)
        }
        assertFalse(called)
        assertTrue(parsed.comments.isEmpty())
    }
}
