package com.liskovsoft.youtubeapi.videoinfo.V2

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

/** Consumer contracts for the Java entry points, using injected HTTP fixtures. */
class PublicReadOverloadContractTest {
    private val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture", 1),
        onBehalfOfUser = "brand", visitor = "visitor")
    private val watchBody = """{"videoDetails":{"videoId":"video","title":"A video","shortDescription":"About it",
        "author":"Creator","channelId":"UCcreator"},"chapterRenderer":{"title":{"simpleText":"First chapter"},
        "timeRangeStartMillis":1000,"thumbnail":{"thumbnails":[{"url":"https://image.test/chapter"}]}}}"""
    private val commentOpening = """{"videoDetails":{"videoId":"video"},
        "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
        "content":{"continuationEndpoint":{"continuationCommand":{"token":"comments-opening"}}}},
        "commentsEntryPointRenderer":{"commentCount":{"simpleText":"25"}},
        "createCommentEndpoint":{"createCommentParams":"create-comment"}}"""
    private val commentBody = """{"continuationContents":{"itemSectionContinuation":{"contents":[
        {"commentRenderer":{"commentId":"comment","authorText":{"simpleText":"Author"},
        "contentText":{"simpleText":"Hello"},"actionMenu":{"menuRenderer":{"items":[
        {"menuServiceItemRenderer":{"text":{"simpleText":"Delete"},"icon":{"iconType":"DELETE"},
        "serviceEndpoint":{"performCommentActionEndpoint":{"action":"delete-comment"}}}}]}}}},
        {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"comments-next"}}}}
        ]}}}"""

    private fun methods(facade: String, name: String) = Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.${facade}Kt")
        .declaredMethods.filter { it.name == name && Modifier.isPublic(it.modifiers) && !it.isSynthetic }.sortedBy { it.parameterCount }

    private fun authenticatedHttp(answer: (HttpRequest, Json.Obj) -> HttpReply): HttpClient = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            assertEquals("SAPISID=fixture", request.headers["Cookie"])
            assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            assertEquals("brand", request.headers["X-Goog-PageId"])
            val body = jsonObject(request.body)!!
            assertEquals("WEB", body.path("context", "client")!!.text("clientName"))
            assertEquals("brand", body.path("context", "user")!!.text("onBehalfOfUser"))
            return answer(request, body)
        }
    }

    @Test fun javaWatchOverloadsRetainExplicitQueueInputsAndDefaultAbsentInputs() {
        val overloads = methods("WatchPage", "loadWatch")
        assertEquals((1..7).toList(), overloads.map { it.parameterCount })
        for (method in overloads.filter { it.parameterCount >= 4 }) {
            val size = method.parameterCount
            var requests = 0
            val http = authenticatedHttp { request, body ->
                requests++
                assertTrue(request.url.endsWith("/next?prettyPrint=false"))
                assertEquals("video", body.text("videoId"))
                assertNull(body.text("continuation"))
                assertEquals(if (size >= 5) "PLqueue" else null, body.text("playlistId"))
                assertEquals(if (size >= 6) 2 else null, body.int("index"))
                assertEquals(if (size >= 7) "queue-params" else null, body.text("params"))
                HttpReply(200, watchBody)
            }
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(null, *listOf("video", context, null, http, "PLqueue", 2, "queue-params")
                .take(size).toTypedArray()) as ReadResult<WatchPage>
            assertTrue(method.toGenericString(), result.ok)
            val page = result.value!!
            assertEquals("video", page.videoId)
            assertEquals("A video", page.title)
            assertEquals("About it", page.description)
            assertEquals("Creator", page.author)
            assertEquals("UCcreator", page.channelId)
            assertEquals("First chapter", page.chapters.single().title)
            assertEquals("https://image.test/chapter", page.chapters.single().thumbnail)
            assertEquals(1000L, page.chapters.single().startMs)
            assertEquals(1, requests)
        }
        // These Java signatures construct JvmHttpClient internally. This is their
        // validation contract, not evidence of successful external HTTP requests.
        for (method in overloads.filter { it.parameterCount < 4 }) {
            val result = method.invoke(null, *listOf("../invalid", RequestContext(), null)
                .take(method.parameterCount).toTypedArray()) as ReadResult<*>
            assertEquals(RequestFailure.INVALID, result.error?.kind)
        }
        val continuation = authenticatedHttp { _, body ->
            assertEquals("watch-next", body.text("continuation"))
            assertNull(body.text("videoId")); assertNull(body.text("playlistId")); assertNull(body.int("index"))
            HttpReply(200, """{"continuationContents":{"horizontalListContinuation":{"items":[{
                "compactVideoRenderer":{"videoId":"related","title":{"simpleText":"Related"}}}]}}}""")
        }
        assertTrue(loadWatch(null, context, "watch-next", continuation, "PLignored", 2, "ignored").ok)
    }

    @Test fun javaCommentOverloadsOpenOrReuseTheSameWatchCapabilities() {
        val overloads = methods("CommentRead", "loadComments")
        assertEquals(listOf(2, 3, 4, 5), overloads.map { it.parameterCount })
        val opened = watchPage(commentOpening, "video")
        for (method in overloads.filter { it.parameterCount >= 4 }) {
            var requests = 0
            val http = authenticatedHttp { _, body ->
                requests++
                if (body.text("videoId") != null) HttpReply(200, commentOpening) else {
                    assertEquals("comments-opening", body.text("continuation"))
                    HttpReply(200, commentBody)
                }
            }
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(null, *listOf("video", context, null, http, opened)
                .take(method.parameterCount).toTypedArray()) as ReadResult<CommentPage>
            assertTrue(result.ok)
            val page = result.value!!
            assertEquals("comment", page.comments.single().id)
            assertEquals("Author", page.comments.single().author)
            assertEquals("Hello", page.comments.single().text)
            assertEquals("delete-comment", page.comments.single().deleteKey)
            assertEquals("comments-next", page.nextKey)
            assertEquals("create-comment", page.createKey)
            assertEquals("25", page.total)
            assertFalse(page.turnedOff)
            assertEquals(if (method.parameterCount == 4) 2 else 1, requests)
        }
        for (method in overloads.filter { it.parameterCount < 4 }) {
            val result = method.invoke(null, *listOf("../invalid", context, null)
                .take(method.parameterCount).toTypedArray()) as ReadResult<*>
            assertEquals(RequestFailure.INVALID, result.error?.kind)
        }
        val never = object : HttpClient { override fun execute(request: HttpRequest): HttpReply = error("No comments capability") }
        val off = loadComments("video", context, null, never,
            opened.copy(commentsKey = null, commentsTurnedOff = true)).value!!
        assertTrue(off.comments.isEmpty()); assertTrue(off.turnedOff); assertEquals("25", off.total)
    }

    @Test fun searchRetainsFiltersInitiallyAndUsesOnlyTheCursorForContinuation() {
        var requests = 0
        val body = """{"contents":[{"videoRenderer":{"videoId":"found","title":{"simpleText":"Found video"}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"search-next"}}}}]}"""
        val http = authenticatedHttp { request, root ->
            assertTrue(request.url.endsWith("/search?prettyPrint=false"))
            if (requests++ == 0) {
                assertEquals("a query", root.text("query")); assertEquals("filters", root.text("params"))
            } else {
                assertEquals("search-next", root.text("continuation"))
                assertNull(root.text("query")); assertNull(root.text("params"))
            }
            HttpReply(200, if (requests == 1) searchPrimaryFixture(body) else """{"onResponseReceivedEndpoints":[{
                "appendContinuationItemsAction":{"continuationItems":${jsonObject(body)!!.fields["contents"]!!.encode()}}}]}""")
        }
        for (page in listOf(loadSearch("a query", context, "filters", null, http),
            loadSearch(null, context, "ignored", "search-next", http))) {
            assertTrue(page.ok)
            assertEquals("found", page.value!!.items.single().id)
            assertEquals("Found video", page.value!!.items.single().title)
            assertEquals("search-next", page.value!!.nextKey)
        }
        assertEquals(2, requests)
    }

    @Test fun commentDeletionUsesTheReturnedCapabilityAndRequiresExplicitAcknowledgement() {
        val comment = commentPage(commentBody).comments.single()
        var status = "STATUS_SUCCEEDED"
        var requests = 0
        val http = authenticatedHttp { request, body ->
            requests++
            assertTrue(request.url.substringBefore('?').endsWith("/comment/perform_comment_action"))
            assertEquals("delete-comment", (body.arr("actions").single() as Json.Str).value)
            HttpReply(200, """{"status":"$status"}""")
        }
        val deleted = deleteComment(comment, context, http).value!!
        assertTrue(deleted.action.confirmed)
        assertNull(deleted.comment)
        status = "STATUS_FAILED"
        assertEquals(RequestFailure.HTTP, deleteComment(comment, context, http).error?.kind)
        assertEquals(RequestFailure.AUTH, deleteComment(comment, context.anonymous(), http).error?.kind)
        assertEquals(2, requests)
    }

    @Test fun playlistSaveUnsaveAndDeleteUseDifferentAuthenticatedTargets() {
        val expected = listOf("like/like", "like/removelike", "playlist/delete")
        var requests = 0
        val http = authenticatedHttp { request, body ->
            val path = expected[requests++]
            assertEquals(YOUTUBE_API + path, request.url)
            assertEquals("PLlist", if (path == "playlist/delete") body.text("playlistId") else body.obj("target")!!.text("playlistId"))
            HttpReply(200, """{"playlistId":"PLlist","status":"STATUS_SUCCEEDED"}""")
        }
        val results = listOf(savePlaylist("PLlist", true, context, http), savePlaylist("PLlist", false, context, http),
            deletePlaylist("PLlist", context, http))
        for (result in results) {
            assertTrue(result.ok); assertEquals("PLlist", result.value!!.playlistId)
            assertTrue(result.value!!.action.confirmed); assertTrue(result.value!!.addedSetVideoIds.isEmpty())
        }
        assertEquals(RequestFailure.AUTH, deletePlaylist("PLlist", context.anonymous(), http).error?.kind)
        assertEquals(3, requests)
    }

    @Test fun playlistReadsAndEditsPreserveOccurrenceIdsAndChangeSemantics() {
        var requests = 0
        val body = """{"metadata":{"playlistMetadataRenderer":{"title":"A playlist"}},"contents":[
            {"playlistVideoRenderer":{"videoId":"video","setVideoId":"occurrence","title":{"simpleText":"A video"},
            "shortBylineText":{"simpleText":"Creator"},"lengthText":{"simpleText":"2:00"},"index":{"simpleText":"1"},
            "thumbnail":{"thumbnails":[{"url":"https://image.test/video"}]}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"playlist-next"}}}}]}"""
        val http = authenticatedHttp { _, root ->
            if (requests++ == 0) assertEquals("VLPLlist", root.text("browseId")) else {
                assertEquals("playlist-next", root.text("continuation")); assertNull(root.text("browseId"))
            }
            HttpReply(200, if (requests == 1) body else
                """{"continuationContents":{"playlistVideoListContinuation":{"contents":${jsonObject(body)!!.fields["contents"]!!.encode()}}}}""")
        }
        val first = loadPlaylist("PLlist", context, null, http).value!!
        val next = loadPlaylist(null, context, "playlist-next", http).value!!
        assertEquals("A playlist", first.title)
        assertEquals("playlist-next", first.nextKey)
        assertEquals(first.videos, next.videos)
        val video = first.videos.single()
        assertEquals("video", video.videoId); assertEquals("occurrence", video.setVideoId)
        assertEquals("A video", video.title); assertEquals("Creator", video.author)
        assertEquals("2:00", video.length); assertEquals("1", video.index)
        assertEquals("https://image.test/video", video.thumbnail)
        val changes = listOf(PlaylistChange.Add("added"), PlaylistChange.RemoveByVideoId("removed"),
            PlaylistChange.Rename("Renamed"), PlaylistChange.Order(2), PlaylistChange.Move("move", "before"))
        val edit = authenticatedHttp { _, root ->
            val actions = root.arr("actions").map { it as Json.Obj }
            assertEquals(listOf("ACTION_ADD_VIDEO", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID", "ACTION_SET_PLAYLIST_NAME",
                "ACTION_SET_PLAYLIST_VIDEO_ORDER", "ACTION_MOVE_VIDEO_AFTER"), actions.map { it.text("action") })
            assertEquals("added", actions[0].text("addedVideoId")); assertEquals("removed", actions[1].text("removedVideoId"))
            assertEquals("Renamed", actions[2].text("playlistName")); assertEquals("2", actions[3].text("playlistVideoOrder"))
            assertEquals("move", actions[4].text("setVideoId")); assertEquals("before", actions[4].text("movedSetVideoIdPredecessor"))
            HttpReply(200, """{"status":"STATUS_SUCCEEDED"}""")
        }
        assertTrue(editPlaylist("PLlist", changes, context, edit).value!!.action.confirmed)
    }

    @Test fun playerDetailsReadPreservesArchivedLiveMetadataAndAccountTracking() {
        val http = authenticatedHttp { request, body ->
            assertEquals(YOUTUBE_API + "player", request.url); assertEquals("video", body.text("videoId"))
            HttpReply(200, """{"videoDetails":{"videoId":"video","title":"An archive","author":"Creator","channelId":"UCcreator",
                "shortDescription":"Description","lengthSeconds":"120","viewCount":"456","isLiveContent":true,"isLive":false,
                "thumbnail":{"thumbnails":[{"url":"https://image.test/player"}]}},
                "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-11-01"}},
                "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback"},
                "videostatsWatchtimeUrl":{"baseUrl":"https://www.youtube.com/api/stats/watchtime"}}}""")
        }
        val details = loadPlayerDetails("video", context, http).value!!
        assertEquals("video", details.videoId); assertEquals("An archive", details.title)
        assertEquals("Creator", details.author); assertEquals("UCcreator", details.channelId)
        assertEquals("Description", details.description); assertEquals(120L, details.durationSeconds)
        assertEquals(456L, details.viewCount); assertEquals("https://image.test/player", details.thumbnail)
        assertTrue(details.liveContent); assertFalse(details.live); assertEquals("2026-11-01", details.published)
        assertEquals("https://www.youtube.com/api/stats/playback", details.tracking!!.playbackUrl)
        assertEquals("https://www.youtube.com/api/stats/watchtime", details.tracking!!.watchTimeUrl)
    }

    @Test fun deArrowReadKeepsThirdPartyRequestsAnonymousAndPreservesOriginalChoices() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("GET", request.method)
                assertEquals("https://branding.test/api/branding?videoID=video", request.url)
                assertTrue(request.headers.isEmpty())
                return HttpReply(200, """{"titles":[{"title":"Community title","votes":4,"locked":false,"original":false},
                    {"title":"Original title","votes":1,"locked":false,"original":true}],
                    "thumbnails":[{"timestamp":12,"votes":8,"locked":true,"original":false}],"videoDuration":120,"randomTime":0.25}""")
            }
        }
        val data = loadDeArrow("video", http, "https://branding.test/").value!!
        assertEquals("Community title", data.preferredTitle()!!.title)
        assertFalse(data.titles.first().original); assertTrue(data.titles.last().original)
        assertEquals(4.0, data.titles.first().votes, 0.0); assertFalse(data.titles.first().locked)
        val thumbnail = data.thumbnails.single()
        assertEquals(12.0, thumbnail.timestamp!!, 0.0); assertEquals(8.0, thumbnail.votes, 0.0)
        assertTrue(thumbnail.locked); assertFalse(thumbnail.original)
        assertEquals(120.0, data.durationSeconds!!, 0.0); assertEquals(0.25, data.randomTime!!, 0.0)
        assertEquals("https://thumb.test/api/v1/getThumbnail?videoID=video&time=12.0", deArrowThumbnailUrl("video", data, "https://thumb.test"))
    }

    private fun <T> javaConstructors(type: Class<T>, args: List<Any?>, consume: (Int, T) -> Unit) {
        val constructors = type.constructors.filter { !it.isSynthetic }.sortedBy { it.parameterCount }
        assertTrue(type.simpleName, constructors.isNotEmpty())
        for (constructor in constructors) {
            @Suppress("UNCHECKED_CAST")
            val value = constructor.newInstance(*args.take(constructor.parameterCount).toTypedArray()) as T
            consume(constructor.parameterCount, value)
        }
    }

    @Test fun javaCommentModelsKeepDefaultCapabilitiesSeparateFromExplicitViewerState() {
        javaConstructors(CommentRun::class.java, listOf("Text", "https://link.test", "video", 12, "UCauthor", "emoji", true, true, true)) { size, run ->
            assertEquals("Text", run.text); assertEquals("https://link.test", run.url); assertEquals("video", run.videoId)
            assertEquals(12, run.seconds); assertEquals("UCauthor", run.channelId); assertEquals("emoji", run.emoji)
            assertEquals(size >= 7, run.bold); assertEquals(size >= 8, run.italic); assertEquals(size >= 9, run.strikethrough)
        }
        val run = CommentRun("Text", null, null, null, null, null)
        val action = PageAction("Delete", ActionEndpoint("performCommentActionEndpoint", "{}"))
        val image = CommentImageAttachment(Artwork(listOf(ArtworkVariant("https://image.test/comment", 64, 64))), 64, 64, 1.0, null, null)
        val voice = CommentVoiceReply("Transcript", null)
        val args = listOf<Any?>("comment", "Author", "Text", "Yesterday", "12", true, "replies", "photo", "like", "unlike", "delete",
            "reply-create", listOf(run), "dislike", "undislike", false, "parent", "2", listOf(action), "UCauthor", true, true, true, true, "detail", "LIKE", image, voice)
        javaConstructors(Comment::class.java, args) { size, comment ->
            assertEquals("comment", comment.id); assertEquals("Author", comment.author); assertEquals("Text", comment.text)
            assertEquals("Yesterday", comment.published); assertEquals("12", comment.likeCount); assertTrue(comment.liked)
            assertEquals("replies", comment.replyKey); assertEquals("photo", comment.authorPhoto)
            assertEquals("like", comment.likeKey); assertEquals("unlike", comment.unlikeKey); assertEquals("delete", comment.deleteKey)
            assertEquals("reply-create", comment.replyCreateKey); assertEquals(listOf(run), comment.runs)
            assertEquals("dislike", comment.dislikeKey); assertEquals("undislike", comment.undislikeKey); assertFalse(comment.disliked)
            assertEquals(if (size >= 17) "parent" else null, comment.parentId)
            assertEquals(if (size >= 18) "2" else null, comment.replyCount)
            assertEquals(if (size >= 19) listOf(action) else emptyList<PageAction>(), comment.actions)
            assertEquals(if (size >= 20) "UCauthor" else null, comment.authorId)
            assertEquals(size >= 21, comment.isCreator); assertEquals(size >= 22, comment.isVerified); assertEquals(size >= 23, comment.isPinned)
            assertEquals(if (size >= 24) true else null, comment.isHearted)
            assertEquals(if (size >= 25) "detail" else null, comment.detailKey)
            assertEquals(if (size >= 26) "LIKE" else null, comment.viewerRating)
            assertEquals(if (size >= 27) image else null, comment.imageAttachment)
            assertEquals(if (size >= 28) voice else null, comment.voiceReply)
            assertTrue(comment.hasDisplayableContent)
        }
    }

    @Test fun javaSearchModelsPreserveRichMediaInputsWhileOlderSignaturesKeepDefaults() {
        val endpoint = ActionEndpoint("watchEndpoint", "{}", "/youtubei/v1/next")
        val action = PageAction("Open", endpoint)
        val menu = MenuEndpoint("panel", "menu-params")
        val artwork = Artwork(listOf(ArtworkVariant("https://example.test/avatar", 88, 88)))
        val post = posts(jsonObject("""{"postRenderer":{"postId":"Ugkx_constructor","contentText":{"simpleText":"Native post"}}}""")!!, null).single()
        val args = listOf<Any?>("video", "video", "Title", "Author", "thumbnail", "2:00", "UCauthor", "PLlist", "params", endpoint,
            listOf(action), 12L, 50, true, "player-params", "author-photo", "100 views", "Yesterday", "5 videos", menu, true, artwork, post)
        val invokedShapes = mutableSetOf<Int>()
        javaConstructors(MediaItem::class.java, args) { size, item ->
            invokedShapes += size
            assertEquals("video", item.kind); assertEquals("video", item.id); assertEquals("Title", item.title)
            assertEquals("Author", item.author); assertEquals("thumbnail", item.thumbnail); assertEquals("2:00", item.duration)
            assertEquals(if (size >= 7) "UCauthor" else null, item.channelId); assertEquals(if (size >= 8) "PLlist" else null, item.playlistId)
            assertEquals(if (size >= 9) "params" else null, item.params); assertEquals(if (size >= 10) endpoint else null, item.endpoint)
            assertEquals(if (size >= 11) listOf(action) else emptyList<PageAction>(), item.actions)
            assertEquals(if (size >= 12) 12L else null, item.startSeconds); assertEquals(if (size >= 13) 50 else null, item.progressPercent)
            assertEquals(size >= 14, item.live); assertEquals(if (size >= 15) "player-params" else null, item.playerParams)
            assertEquals(if (size >= 16) "author-photo" else null, item.authorThumbnail)
            assertEquals(if (size >= 17) "100 views" else null, item.viewCount); assertEquals(if (size >= 18) "Yesterday" else null, item.published)
            assertEquals(if (size >= 19) "5 videos" else null, item.videoCountText); assertEquals(if (size >= 20) menu else null, item.menuEndpoint)
            assertEquals(size >= 21, item.hasNewContent)
            assertEquals(if (size >= 22) artwork else null, item.authorArtwork)
            assertEquals(if (size >= 23) post else null, item.post)
            if (size >= 23) assertEquals("https://www.youtube.com/post/Ugkx_constructor", item.post!!.permalink)
        }
        assertTrue(invokedShapes.containsAll(listOf(22, 23)))
        val item = searchPage("""{"videoRenderer":{"videoId":"video","title":{"simpleText":"Title"}}}""").items.single()
        javaConstructors(MediaGroup::class.java, listOf("Group", listOf(item), "next", listOf(action))) { size, group ->
            assertEquals("Group", group.title); assertEquals(listOf(item), group.items); assertEquals("next", group.nextKey)
            assertEquals(if (size >= 4) listOf(action) else emptyList<PageAction>(), group.actions)
        }
        val group = MediaGroup("Group", listOf(item), "next")
        val filter = SearchFilter("Recent", "Date", "filters", true)
        javaConstructors(SearchPage::class.java, listOf(listOf(item), listOf(group), "next", listOf("Message"), listOf(action), listOf(filter))) { size, page ->
            assertEquals(listOf(item), page.items); assertEquals(listOf(group), page.groups); assertEquals("next", page.nextKey)
            assertEquals(if (size >= 4) listOf("Message") else emptyList<String>(), page.messages)
            assertEquals(if (size >= 5) listOf(action) else emptyList<PageAction>(), page.actions)
            assertEquals(if (size >= 6) listOf(filter) else emptyList<SearchFilter>(), page.filters)
            assertEquals("Recent", filter.title); assertEquals("Date", filter.group); assertTrue(filter.selected); assertEquals("filters", filter.params)
        }
    }

    @Test fun javaWatchModelsDistinguishMissingViewerMetadataFromExplicitValues() {
        val chapter = Chapter("First", 0, "chapter-photo")
        val queue = WatchQueue("PLlist", "Queue", 0, emptyList(), null, null, "queue-next")
        val storyboard = Storyboard("template", emptyList())
        val sort = CommentSort("Newest", "sort", true)
        val args = listOf<Any?>("video", "Title", "Description", "Author", "UCauthor", listOf(chapter), emptyList<MediaItem>(),
            "next", "comments", "chat", storyboard, queue, 12L, "author-photo", "LIKE", true, "100 views", "Yesterday", "5", "10",
            "create-comment", listOf(sort), true)
        javaConstructors(WatchPage::class.java, args) { size, page ->
            assertEquals("video", page.videoId); assertEquals("Title", page.title); assertEquals("Description", page.description)
            assertEquals("Author", page.author); assertEquals("UCauthor", page.channelId); assertEquals(listOf(chapter), page.chapters)
            assertTrue(page.related.isEmpty()); assertEquals("next", page.nextKey); assertEquals("comments", page.commentsKey)
            assertEquals("chat", page.chatKey); assertSame(storyboard, page.storyboard)
            assertEquals(if (size >= 12) queue else null, page.queue); assertEquals(if (size >= 13) 12L else null, page.resumeSeconds)
            assertEquals(if (size >= 14) "author-photo" else null, page.authorThumbnail); assertEquals(if (size >= 15) "LIKE" else null, page.rating)
            assertEquals(if (size >= 16) true else null, page.subscribed); assertEquals(if (size >= 17) "100 views" else null, page.viewCount)
            assertEquals(if (size >= 18) "Yesterday" else null, page.published); assertEquals(if (size >= 19) "5" else null, page.commentCount)
            assertEquals(if (size >= 20) "10" else null, page.likeCount); assertEquals(if (size >= 21) "create-comment" else null, page.commentCreateKey)
            assertEquals(if (size >= 22) listOf(sort) else emptyList<CommentSort>(), page.commentSortOptions)
            assertEquals(size >= 23, page.commentsTurnedOff)
            assertEquals("Newest", sort.title); assertEquals("sort", sort.key); assertTrue(sort.selected)
        }
    }

    @Test fun javaPlaylistChoicesDistinguishAnExplicitUnknownStateFromTheLegacyBooleanDefault() {
        javaConstructors(PlaylistChoice::class.java, listOf("PLlist", "A playlist", true, null)) { size, choice ->
            assertEquals("PLlist", choice.id); assertEquals("A playlist", choice.title); assertTrue(choice.contains)
            assertEquals(if (size == 3) true else null, choice.containsState)
        }
        val parsed = playlistChoices("""{"contents":[{"addToPlaylistRenderer":{"playlists":[
            {"playlistAddToOptionRenderer":{"playlistId":"PLlist","title":{"simpleText":"A playlist"},
            "containsSelectedVideos":"SOME"}}]}}]}""").single()
        assertFalse(parsed.contains)
        assertNull(parsed.containsState)
    }
}
