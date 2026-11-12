package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Synthetic equivalents of the observed direct WEB quiz attachment; no live requests. */
class PostQuizAttachmentTest {
    private val channel = "UC" + "a".repeat(22)
    private val id = "Ugkx_quiz_fixture"
    private fun choice(text: String = "Option", correct: Boolean = false) =
        """{"text":{"runs":[{"text":"$text"}]},"isCorrect":$correct,"opaqueDisplay":{},"opaqueState":17}"""
    private fun quiz(choices: String = listOf(choice("A"), choice("B", true), choice("C"), choice("D")).joinToString(","), votes: String = """, "totalVotes":{"simpleText":"1,234 votes"}""") =
        """{"choices":[$choices]$votes,"accessibilityData":{},"display1":{},"display2":{},"display3":[],"display4":17,"display5":true,"display6":"Label","display7":{}}"""
    private fun post(attachment: String = """{"quizRenderer":${quiz()}}""", postId: String = id, extra: String = "") =
        """{"backstagePostRenderer":{"postId":"$postId","authorText":{"simpleText":"Author"},"authorEndpoint":{"browseEndpoint":{"browseId":"$channel"}},
            "contentText":{"simpleText":"Body"},"backstageAttachment":$attachment$extra}}"""
    private fun page(vararg slots: String) = """{"contents":[${slots.joinToString(",") }]}"""
    private fun tab(kind: String, selected: Boolean, content: String? = null) = """{"tabRenderer":{"title":"Tab","tabIdentifier":"$kind","selected":$selected,
        "endpoint":{"browseEndpoint":{"browseId":"$channel","params":"ISSUED_$kind"},"commandMetadata":{"webCommandMetadata":{"url":"/channel/$channel/$kind","apiUrl":"/youtubei/v1/browse"}}}
        ${content?.let { ",\"content\":$it" }.orEmpty()}}}"""
    private fun landing(selected: Boolean, content: String) = """{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","title":"Channel"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[${tab("home", !selected, "{\"contents\":[]}")},${tab("posts", selected, if (selected) content else null)}]}}}"""
    private fun read(body: String): ReadResult<PostPage> {
        var count = 0
        val result = loadChannelPosts(channel, params = "ISSUED", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                count++; assertEquals("POST", request.method); assertEquals("/youtubei/v1/browse", java.net.URI(request.url).path)
                return HttpReply(200, body)
            }
        })
        assertEquals(1, count); return result
    }
    private fun rejected(attachment: String) {
        val result = read(page(post(attachment)))
        assertEquals(RequestFailure.PARSE, result.error?.kind); assertEquals("unknown_post_page", result.error?.code); assertNull(result.value)
    }
    private fun assertQuiz(post: PostItem) {
        val value = post.quiz!!
        assertEquals(listOf("A", "B", "C", "D"), value.choices.map { it.text })
        assertEquals(listOf(false, true, false, false), value.choices.map { it.isCorrect })
        assertEquals("1,234 votes", value.totalVotes); assertNull(post.poll); assertNull(post.video); assertTrue(post.images.isEmpty())
    }

    @Test fun defaultIssuedPostsRouteRetainsTheObservedMixedTenPostPage() {
        val image = """{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://i.example/image.jpg"}]}}}"""
        val video = """{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Video"}}}"""
        val poll = """{"pollRenderer":{"choices":[{"text":{"simpleText":"Poll"},"selected":false}],"totalVotes":{"simpleText":"3 votes"}}}"""
        val attachments = listOf(video, video, image, image, """{"quizRenderer":${quiz()}}""", video, poll, image, video, video)
        val content = page(*attachments.mapIndexed { index, value -> post(value, "Ugkx_mixed_$index") }.toTypedArray())
        var count = 0
        val result = loadChannelPosts(channel, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                count++; val body = jsonObject(request.body)!!
                assertEquals(channel, body.text("browseId")); assertNull(body.text("continuation"))
                if (count == 1) { assertNull(body.text("params")); return HttpReply(200, landing(false, content)) }
                assertEquals(2, count); assertEquals("ISSUED_posts", body.text("params")); return HttpReply(200, landing(true, content))
            }
        })
        assertTrue(result.ok); assertEquals(2, count)
        val posts = result.value!!.posts
        assertEquals(10, posts.size); assertEquals(attachments.indices.map { "Ugkx_mixed_$it" }, posts.map { it.postId })
        assertEquals(5, posts.count { it.video != null }); assertEquals(3, posts.count { it.images.isNotEmpty() }); assertEquals(1, posts.count { it.poll != null })
        assertEquals(1, posts.count { it.quiz != null }); assertQuiz(posts[4]); assertNull(result.value!!.nextKey)
        assertTrue(posts.all { it.authorId == channel && it.like == null && it.unlike == null && !it.authorizationRequired })
    }

    @Test fun exactPostDocumentAndItsReadOnlyQuizKeepRequestedIdentity() {
        var count = 0
        val result = loadPost(id, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                count++; assertEquals("GET", request.method); assertEquals("/post/$id", java.net.URI(request.url).path)
                return HttpReply(200, "<script>var ytInitialData = " + page(post()) + ";</script>")
            }
        })
        assertEquals(1, count); assertTrue(result.ok); assertEquals(id, result.value!!.posts.single().postId)
        assertQuiz(result.value!!.posts.single()); assertNull(result.value!!.nextKey); assertNull(result.value!!.commentsKey)
    }

    @Test fun continuationAndExplicitEndRemainOwnedByThePostCollection() {
        var count = 0
        val body = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"targetId":"backstage-item-section","continuationItems":[${post()}]}}]}"""
        val result = loadChannelPosts(channel, params = "ISSUED", continuation = "ISSUED_PAGE_2", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                count++; assertEquals("ISSUED_PAGE_2", jsonObject(request.body)!!.text("continuation")); return HttpReply(200, body)
            }
        })
        assertTrue(result.ok); assertEquals(1, count); assertQuiz(result.value!!.posts.single()); assertNull(result.value!!.nextKey)
        val wrong = loadChannelPosts(channel, continuation = "ISSUED_PAGE_2", http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, body.replace("backstage-item-section", "foreign-target"))
        })
        assertEquals(RequestFailure.PARSE, wrong.error?.kind); assertNull(wrong.value)
        val cursor = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"OWN_CURSOR"}}}}"""
        assertEquals("OWN_CURSOR", read(page(post(), cursor)).value!!.nextKey)
    }

    @Test fun malformedChoicesAndDirectFieldTypesFailTheWholeNetworkPage() {
        val bad = listOf(
            "{}", """{"choices":null}""", """{"choices":{}}""", """{"choices":[]}""", """{"choices":[17]}""",
            """{"choices":[{"text":{"simpleText":"A"}}]}""", """{"choices":[{"text":{"simpleText":"A"},"isCorrect":"true"}]}""",
            """{"choices":[{"text":{"simpleText":"A"},"isCorrect":null}]}""", """{"choices":[{"text":"A","isCorrect":true}]}""",
            """{"choices":[{"text":{},"isCorrect":true}]}""", """{"choices":[{"text":{"simpleText":""},"isCorrect":true}]}""",
            """{"choices":[{"text":{"runs":[{}]},"isCorrect":true}]}""", """{"choices":[{"text":{"runs":{}},"isCorrect":true}]}""",
            """{"choices":[{"text":{"simpleText":"A","runs":[]},"isCorrect":true}]}""")
        bad.forEach { rejected("""{"quizRenderer":$it}""") }
        rejected("""{"quizRenderer":null}"""); rejected("""{"quizRenderer":[]}""")
    }

    @Test fun totalVoteTextIsOptionalButDeclaredWrongShapesDoNotFallBack() {
        for (votes in listOf("17", "null", "\"4 votes\"", "{}", """{"simpleText":17}""", """{"runs":[{}]}"""))
            rejected("""{"quizRenderer":{"choices":[${choice()}],"totalVotes":$votes}}""")
        val missing = read(page(post("""{"quizRenderer":${quiz(votes = "")}}""")))
        assertTrue(missing.ok); assertNull(missing.value!!.posts.single().quiz!!.totalVotes)
        val content = read(page(post("""{"quizRenderer":{"choices":[{"text":{"content":"Content label"},"isCorrect":false}],"totalVotes":{"runs":[{"text":"0 votes"}]}}}""")))
        assertEquals("Content label", content.value!!.posts.single().quiz!!.choices.single().text)
        assertEquals("0 votes", content.value!!.posts.single().quiz!!.totalVotes)
    }

    @Test fun quizMustBeTheUniqueDirectAttachmentWithoutMetadataRescue() {
        rejected("""{"quizRenderer":${quiz()},"videoRenderer":{"videoId":"abcdefghijk"}}""")
        rejected("""{"quizRenderer":${quiz()},"pollRenderer":{"choices":[]}}""")
        rejected("""{"futureQuizRenderer":${quiz()}}""")
        val result = read("""{"contents":[{"backstagePostRenderer":{"postId":"$id","responseContext":{"backstageAttachment":{"quizRenderer":${quiz()}}}}}]}""")
        assertTrue(result.ok); assertNull(result.value!!.posts.single().quiz)
        val absent = read("""{"contents":[],"responseContext":${page(post())}}""")
        assertTrue(absent.ok); assertTrue(absent.value!!.posts.isEmpty())
    }

    @Test fun opaqueQuizPresentationCannotLendAnyIdentityCommandOrContinuation() {
        val foreign = """{"backstagePostRenderer":{"postId":"Ugkx_foreign"},"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"FOREIGN_CURSOR"}}},
            "pollRenderer":{"choices":[{"text":{"simpleText":"Foreign poll"},"selectServiceEndpoint":{"performCommentActionEndpoint":{"action":"FOREIGN_ACTION"}}}]},
            "backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://i.example/foreign.jpg"}]}},"signInEndpoint":{},"likeEndpoint":{"target":{"postId":"Ugkx_foreign"}}}"""
        val nested = quiz().replace("\"display1\":{}", "\"display1\":$foreign").replace("\"opaqueDisplay\":{}", "\"opaqueDisplay\":$foreign")
        val ownLike = """, "actionButtons":{"likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"likeEndpoint":{"status":"LIKE","target":{"postId":"$id"},"likeParams":"OWN_LIKE"}}}}}"""
        val result = read(page(post("""{"quizRenderer":$nested}""", extra = ownLike)))
        assertTrue(result.ok); assertEquals(1, result.value!!.posts.size); assertNull(result.value!!.nextKey)
        val item = result.value!!.posts.single(); assertQuiz(item); assertEquals(id, item.postId); assertEquals(channel, item.authorId)
        assertFalse(item.authorizationRequired); assertEquals("like/like", item.like!!.endpoint); assertFalse(item.like!!.payload.contains("FOREIGN"))
        assertNull(item.unlike); assertNull(item.dislike); assertNull(item.undislike)
        val pure = postItem(jsonObject(post("""{"quizRenderer":$nested}"""))!!.obj("backstagePostRenderer")!!, channel)!!
        assertQuiz(pure); assertNull(pure.like); assertFalse(pure.authorizationRequired)
    }

    @Test fun nestedKnownFieldNamesCannotRepairMissingDirectQuizData() {
        rejected("""{"quizRenderer":{"opaque":{"choices":[${choice()}]}}}""")
        rejected("""{"quizRenderer":{"choices":[{"text":{"opaque":{"simpleText":"Borrowed"}},"isCorrect":true}]}}""")
        rejected("""{"quizRenderer":{"choices":[{"text":{"simpleText":"A"},"opaque":{"isCorrect":true}}]}}""")
        rejected("""{"quizRenderer":{"choices":[${choice()}],"totalVotes":{"opaque":{"simpleText":"Borrowed"}}}}""")
    }

    @Test fun quizChoiceTextAndCollectionBudgetsRejectOversizedOrDeepSources() {
        rejected("""{"quizRenderer":${quiz(List(101) { choice() }.joinToString(","))}}""")
        rejected("""{"quizRenderer":${quiz(choice("X".repeat(16_385)))}}""")
        var tree = "{}"; repeat(100) { tree = """{"opaque":$tree}""" }
        rejected("""{"quizRenderer":{"choices":[${choice()}],"presentation":$tree}}""")
    }

    @Test fun allNewDataMembersAndCopyContractsAreDirectlyConsumed() {
        val choice = PostQuizChoice("A", true)
        assertEquals("A", choice.component1()); assertTrue(choice.component2()); assertEquals("A", choice.text); assertTrue(choice.isCorrect)
        assertEquals(choice, choice.copy()); assertEquals(PostQuizChoice("B", false), choice.copy("B", false))
        assertEquals(choice.hashCode(), choice.copy().hashCode()); assertTrue(choice.toString().contains("A")); assertNotEquals(choice, null); assertNotEquals(choice as Any, "A")
        val quiz = PostQuiz(listOf(choice), "4 votes")
        assertEquals(listOf(choice), quiz.component1()); assertEquals("4 votes", quiz.component2())
        assertEquals(quiz, quiz.copy()); assertEquals(quiz.hashCode(), quiz.copy().hashCode()); assertTrue(quiz.toString().contains("4 votes"))
        assertNotEquals(quiz, null); assertNotEquals(quiz as Any, choice)
        assertEquals(PostQuiz(emptyList(), null), quiz.copy(emptyList(), null))
        val item = read(page(post())).value!!.posts.single()
        assertSame(item.quiz, item.component18()); assertNull(item.component17())
        assertEquals(item.quiz, item.copy(text = "Changed").quiz); assertNull(item.copy(quiz = null).quiz)
        val pure = postItem(jsonObject(post())!!.obj("backstagePostRenderer")!!, channel)!!
        assertQuiz(pure); assertEquals(item.quiz, pure.quiz)
        val invalid = postItem(jsonObject(post("""{"quizRenderer":{}}"""))!!.obj("backstagePostRenderer")!!, channel)!!
        assertNull(invalid.quiz)
    }
}
