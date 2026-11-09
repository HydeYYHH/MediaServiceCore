package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ParsedWatchCommentsRootTest {
    @Test fun parsedRootsAndSingleResponseLoadsPreserveTheCompleteModelsAndOpeningCapabilities() {
        val watchRoot = jsonObject(watch)!!
        val commentsRoot = jsonObject(comments)!!
        val watchEncoded = watchRoot.encode()
        val commentsEncoded = commentsRoot.encode()
        val opened = watchPage(watch, VIDEO)
        val page = commentPage(comments)
        assertEquals(opened, watchPage(watchRoot, VIDEO))
        assertEquals(page, commentPage(commentsRoot))
        assertEquals(commentKey(watch), commentKey(watchRoot))
        assertEquals("opening-comments", opened.commentsKey)
        assertEquals("opening-create", opened.commentCreateKey)
        assertEquals(listOf("newest"), opened.commentSortOptions.map { it.key })
        assertEquals("42", opened.commentCount)
        assertEquals("Name \"世界\"", opened.title)
        assertEquals("Line one\nLine two", opened.description)
        assertEquals("chat", opened.chatKey)
        assertEquals(listOf("related"), opened.related.map { it.id })
        assertEquals(1000L, opened.chapters.single().startMs)
        assertEquals("reply-page-2", page.nextKey)
        assertEquals("reply-opening", page.comments.single().replyKey)
        assertEquals("UCauthor", page.comments.single().authorId)
        assertTrue(page.comments.single().runs.first().bold)

        var response = watch
        var requests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                assertEquals(YOUTUBE_API + "next?prettyPrint=false", request.url)
                return HttpReply(200, response)
            }
        }
        assertEquals(opened, loadWatch(VIDEO, http = http).value)
        assertEquals(1, requests)
        response = comments
        // Reusing the opening must keep its metadata without making another opening read.
        assertEquals(page.copy(createKey = opened.commentCreateKey, sortOptions = opened.commentSortOptions,
            total = opened.commentCount), loadComments(VIDEO, RequestContext(), http = http, opened = opened).value)
        assertEquals(2, requests)
        assertEquals(page, loadCommentReplies("reply-opening", http = http).value)
        assertEquals(3, requests)
        assertEquals(watchEncoded, watchRoot.encode())
        assertEquals(commentsEncoded, commentsRoot.encode())
    }

    @Test fun publicStringParsersKeepTheirEmptyAndMalformedInputBehavior() {
        val emptyWatch = watchPage("{}", VIDEO)
        val emptyComments = CommentPage(emptyList(), null, null)
        for (body in listOf("", " ", "{", "null", "[]", "42", "\"string\"")) {
            assertEquals(emptyWatch, watchPage(body, VIDEO))
            assertEquals(VIDEO, watchPage(body, VIDEO).videoId)
            assertEquals(emptyComments, commentPage(body))
            assertNull(commentKey(body))
        }
        assertEquals(emptyWatch, watchPage(null as Json.Obj?, VIDEO))
        assertEquals(emptyComments, commentPage(jsonObject("{}")!!))
    }

    @Test fun invalidInputsMakeNoRequestAndContinuationReadsKeepTheSameBodyAndContext() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, "{}")
            }
        }
        assertEquals("invalid_video_id", loadWatch("bad id", http = http).error!!.code)
        assertEquals("invalid_queue_index", loadWatch(VIDEO, http = http, index = -1).error!!.code)
        assertEquals("invalid_video_id", loadComments("bad id", RequestContext(), http = http).error!!.code)
        assertEquals("comment_video_mismatch", loadComments(VIDEO, RequestContext(), http = http,
            opened = watchPage("{}", "different00")).error!!.code)
        assertEquals("missing_reply_key", loadCommentReplies(" ", http = http).error!!.code)
        assertTrue(requests.isEmpty())

        for (context in listOf(RequestContext(visitor = "visitor", language = "fr", region = "CA", visitorCookie = "VISITOR_INFO1_LIVE=fixture"),
            RequestContext(accountId = "account", accessToken = "test-token", visitor = "visitor", onBehalfOfUser = "profile"))) {
            val expected = youtubeQuery(YOUTUBE_API + "next", payload("continuation" to "cursor"), context).request()
            val before = requests.size
            // A continuation does not require a video id or an opening request.
            assertTrue(loadWatch(null, context, continuation = "cursor", http = http).ok)
            assertTrue(loadComments(VIDEO, context, continuation = "cursor", http = http).ok)
            assertTrue(loadCommentReplies("cursor", context, http).ok)
            for (request in requests.drop(before)) {
                assertEquals(expected.copy(url = YOUTUBE_API + "next?prettyPrint=false"), request)
                assertEquals("cursor", jsonObject(request.body)!!.text("continuation"))
                assertNull(jsonObject(request.body)!!.text("videoId"))
            }
            assertEquals(before + 3, requests.size)
        }
        // The generic transport and non-page endpoints retain their caller's URL.
        requestObject(youtubeQuery(YOUTUBE_API + "next", payload("videoId" to VIDEO), RequestContext()), http)
        assertEquals(YOUTUBE_API + "next", requests.last().url)
    }

    @Test fun responseAndTransportFailuresKeepTheirTypedErrorsWithoutExtraRequests() {
        var status = 200
        var body = "{"
        var throwsIo = false
        var requests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                if (throwsIo) throw IOException("fixture failure")
                return HttpReply(status, body)
            }
        }
        fun errors(): List<RequestError> {
            val before = requests
            val errors = listOf(loadWatch(VIDEO, http = http).error!!,
                loadComments(VIDEO, RequestContext(), continuation = "cursor", http = http).error!!,
                loadCommentReplies("cursor", http = http).error!!)
            assertEquals(before + 3, requests)
            return errors
        }
        assertTrue(errors().all { it.kind == RequestFailure.PARSE && it.code == "invalid_json" })
        body = "{}"
        status = 403
        assertTrue(errors().all { it.kind == RequestFailure.AUTH && it.status == 403 && it.code == "http_403" })
        status = 503
        assertTrue(errors().all { it.kind == RequestFailure.HTTP && it.status == 503 })
        status = 200
        body = """{"error":{"code":403,"status":"PERMISSION_DENIED"}}"""
        assertTrue(errors().all { it.kind == RequestFailure.AUTH && it.code == "PERMISSION_DENIED" })
        throwsIo = true
        assertTrue(errors().all { it.kind == RequestFailure.TRANSPORT && it.code == "transport_io" })
    }

    private companion object {
        const val VIDEO = "abcdefghijk"
        val watch = """
            {
              "videoDetails":{"videoId":"abcdefghijk","title":"Fallback","author":"Author","channelId":"UCauthor"},
              "videoPrimaryInfoRenderer":{"title":{"simpleText":"Name \"世界\""},"dateText":{"simpleText":"Today"}},
              "videoSecondaryInfoRenderer":{"description":{"simpleText":"Line one\nLine two"}},
              "chapterRenderer":{"title":{"simpleText":"Chapter"},"timeRangeStartMillis":1000},
              "contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[
                {"compactVideoRenderer":{"videoId":"related","title":{"simpleText":"Related"}}}
              ]}}}},
              "liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"chat"}}]},
              "commentsEntryPointRenderer":{"commentCount":{"simpleText":"42"}},
              "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{
                "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"opening-comments"}}}}
              },
              "createCommentEndpoint":{"createCommentParams":"opening-create"},
              "sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Newest first","serviceEndpoint":{"continuationCommand":{"token":"newest"}}}]}
            }
        """.trimIndent()
        val comments = """
            {"continuationContents":{"itemSectionContinuation":{"contents":[
              {"commentThreadRenderer":{"comment":{"commentRenderer":{
                "commentId":"parent","authorText":{"simpleText":"Author"},
                "authorEndpoint":{"browseEndpoint":{"browseId":"UCauthor"}},
                "contentText":{"runs":[{"text":"Text with \"quotes\" 世界","bold":true}]},
                "publishedTimeText":{"simpleText":"Now"},"voteCount":{"simpleText":"10"},"isLiked":true,
                "replyCount":45,"authorThumbnail":{"thumbnails":[{"url":"https://image.invalid/avatar"}]}
              }},"replies":{"commentRepliesRenderer":{"continuationCommand":{"token":"reply-opening"}}}}},
              {"continuationItemRenderer":{"button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"reply-page-2"}}}}}}
            ]}}}
        """.trimIndent()
    }
}
