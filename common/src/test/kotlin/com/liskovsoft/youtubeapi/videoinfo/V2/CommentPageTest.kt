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
          "header":{"commentsHeaderRenderer":{"createRenderer":{"commentSimpleboxRenderer":{"submitButton":{"buttonRenderer":{"serviceEndpoint":{"createCommentEndpoint":{"createCommentParams":"CREATE"}}}}}}}},
          "contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{
            "commentId":"abc",
            "authorText":{"simpleText":"Ada"},
            "contentText":{"runs":[
              {"text":"hello "},{"text":"world "},
              {"text":"site","navigationEndpoint":{"urlEndpoint":{"url":"/site"}}},
              {"text":" 1:23","navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=dQw4w9WgXcQ&t=83s"}},"watchEndpoint":{"videoId":"dQw4w9WgXcQ","startTimeSeconds":83},"browseEndpoint":{"browseId":"UCada"}}},
              {"emoji":{"emojiId":"smile"}},
              {"emoji":{"isCustomEmoji":true,"shortcuts":[":cat:"],"image":{"thumbnails":[{"url":"https://yt3.ggpht.com/e"}]}}},
              {"navigationEndpoint":{"watchEndpoint":{"videoId":"zero","startTimeSeconds":0}}},
              {}
            ]},
            "publishedTimeText":{"simpleText":"2 days ago"},
            "voteCount":{"simpleText":"12"},
            "isLiked":true,
            "authorThumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a"}]},
            "replies":{"commentRepliesRenderer":{"contents":[
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLY"}}}}
            ]}},
            "actionButtons":{"commentActionButtonsRenderer":{
              "likeButton":{"toggleButtonRenderer":{
                "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"LIKE"}},
                "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNLIKE"}}
              }},
              "dislikeButton":{"toggleButtonRenderer":{
                "isToggled":true,
                "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"DISLIKE"}},
                "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNDISLIKE"}}
              }}
            }},
            "actionMenu":{"menuRenderer":{"items":[
              {"menuServiceItemRenderer":{"icon":{"iconType":"FLAG"},"serviceEndpoint":{"performCommentActionEndpoint":{"action":"REPORT"}}}},
              {"menuNavigationItemRenderer":{"icon":{"iconType":"DELETE"},"navigationEndpoint":{"confirmDialogEndpoint":{"content":{"confirmDialogRenderer":{"confirmButton":{"buttonRenderer":{"serviceEndpoint":{"performCommentActionEndpoint":{"action":"DELETE"}}}}}}}}}}
            ]}},
            "createReplyParams":"REPLY_CREATE"
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
        assertEquals("hello world site 1:23smile:cat:", comment.text)
        assertEquals("2 days ago", comment.published)
        assertEquals("12", comment.likeCount)
        assertTrue(comment.liked)
        assertEquals("REPLY", comment.replyKey)
        assertEquals("https://yt3.ggpht.com/a", comment.authorPhoto)
        assertEquals("CREATE", parsed.createKey)
        assertEquals("LIKE", comment.likeKey)
        assertEquals("UNLIKE", comment.unlikeKey)
        assertEquals("DELETE", comment.deleteKey)
        assertEquals("REPLY_CREATE", comment.replyCreateKey)
        assertEquals(7, comment.runs.size)
        assertEquals("https://www.youtube.com/site", comment.runs[2].url)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=83s", comment.runs[3].url)
        assertEquals("dQw4w9WgXcQ", comment.runs[3].videoId)
        assertEquals(83, comment.runs[3].seconds)
        assertEquals("UCada", comment.runs[3].channelId)
        assertEquals("smile", comment.runs[4].text)
        assertNull(comment.runs[4].emoji)
        assertEquals(":cat:", comment.runs[5].text)
        assertEquals("https://yt3.ggpht.com/e", comment.runs[5].emoji)
        assertEquals("zero", comment.runs[6].videoId)
        assertEquals(0, comment.runs[6].seconds)
        assertTrue(comment.disliked)
        assertEquals("DISLIKE", comment.dislikeKey)
        assertEquals("UNDISLIKE", comment.undislikeKey)
    }

    @Test
    fun readsALooseCreateKey() {
        val body = """{"continuationContents":{"itemSectionContinuation":{"contents":[]}},"createCommentParams":"LOOSE"}"""
        assertEquals("LOOSE", commentPage(body).createKey)
    }

    @Test
    fun usesTheWatchCreateKeyWhenThePageOmitsIt() {
        val opened = """
            {"createCommentParams":"FROM_WATCH","engagementPanels":[{"engagementPanelSectionListRenderer":{
              "panelIdentifier":"engagement-panel-comments-section",
              "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMMENT_KEY"}}}}
              ]}}]}}
            }}]}
        """
        val bare = """{"continuationContents":{"itemSectionContinuation":{"contents":[]}}}"""
        val parsed = loadComments("dQw4w9WgXcQ", null) { query ->
            if (query.body.contains("videoId")) Pair(200, opened) else Pair(200, bare)
        }
        assertEquals("FROM_WATCH", parsed.createKey)
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
        assertEquals("hello world site 1:23smile:cat:", parsed.comments[0].text)
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
