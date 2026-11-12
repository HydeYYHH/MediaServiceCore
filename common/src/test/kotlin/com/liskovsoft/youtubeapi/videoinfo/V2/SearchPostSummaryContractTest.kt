package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchPostSummaryContractTest {
    private val author = "UC" + "a".repeat(22)
    private fun video(id: String) = """{"videoRenderer":{"videoId":"$id","title":{"simpleText":"Video"}}}"""
    private fun renderer(id: String, body: String = "\"contentText\":{\"simpleText\":\"Post body\"}", type: String = "postRenderer") = """{
        "$type":{"postId":"$id","authorText":{"simpleText":"Author"},"authorEndpoint":{"browseEndpoint":{"browseId":"$author"}},
        "authorThumbnail":{"thumbnails":[{"url":"https://yt3.example/avatar","width":88,"height":88}]},
        "publishedTimeText":{"simpleText":"2 days ago"},$body,
        "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/post/$id","apiUrl":"/youtubei/v1/browse"}},
        "browseEndpoint":{"browseId":"$author","params":"issued-post-route-$id"}}}}"""
    private val image = """"backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://img.example/content","width":640,"height":480}]}}}"""
    private val poll = """"backstageAttachment":{"pollRenderer":{"choices":[{"text":{"simpleText":"One"}},
        {"text":{"simpleText":"Two"}}],"totalVotes":{"simpleText":"20 votes"}}}"""
    private val cta = """{"buttonCardViewModel":{"title":"Show native posts","rendererContext":{"commandContext":{"onTap":{
        "innertubeCommand":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},
        "browseEndpoint":{"browseId":"$author","params":"issued-native-posts-tab"}}}}}}}"""
    private fun shelf(slots: String) = """{"shelfRenderer":{"title":{"simpleText":"Native posts"},
        "content":{"horizontalListRenderer":{"items":[$slots]}}}}"""
    private fun page(slots: String, estimate: String = "20") = """{"estimatedResults":"$estimate","contents":{
        "twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[{
        "itemSectionRenderer":{"contents":[$slots]}}]}}}}}"""
    private fun read(body: String, continuation: Boolean = false): ReadResult<SearchPage> {
        var count = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            count++
            return HttpReply(200, body)
        } }
        return loadSearch(if (continuation) null else "fixture", continuation = if (continuation) "issued-next" else null,
            http = http).also { assertEquals(1, count) }
    }
    private fun rejected(slot: String) = assertEquals("unknown_search_page", read(page(video("ordinary") + "," + slot)).error?.code)

    @Test fun observedTenPostsAndIssuedButtonRemainInTheirNativeShelfBesideVideos() {
        val postSlots = (0 until 10).joinToString(",") { renderer("Ugkx_fixture_$it", "\"contentText\":{\"simpleText\":\"Post body\"},$image") }
        val body = page(video("before") + "," + shelf(postSlots + "," + cta) + "," + video("after"))
        val loaded = read(body).value!!
        assertEquals(listOf("before") + (0 until 10).map { "Ugkx_fixture_$it" } + "after", loaded.items.map { it.id })
        val summaries = loaded.items.filter { it.kind == "post" }
        assertEquals(10, summaries.size)
        summaries.forEach { summary ->
            val post = summary.post!!
            assertEquals(summary.id, post.postId); assertEquals("Post body", summary.title)
            assertEquals(author, summary.channelId); assertEquals("Author", summary.author)
            assertEquals("https://yt3.example/avatar", summary.authorThumbnail)
            assertEquals(88, summary.authorArtwork!!.variants.single().width)
            assertEquals("https://img.example/content", summary.thumbnail)
            assertEquals("2 days ago", summary.published)
            assertEquals("https://www.youtube.com/post/${summary.id}", post.permalink)
            assertEquals("issued-post-route-${summary.id}", jsonObject(summary.endpoint!!.payload)!!.text("params"))
        }
        val action = loaded.actions.single()
        assertEquals("Show native posts", action.title)
        assertEquals("browseEndpoint", action.endpoint.name)
        assertEquals("/youtubei/v1/browse", action.endpoint.apiUrl)
        assertEquals("issued-native-posts-tab", jsonObject(action.endpoint.payload)!!.text("params"))
        assertEquals(summaries, loaded.groups.single().items)
        assertEquals(listOf(action), loaded.groups.single().actions)
    }

    @Test fun sharedPostMapperPreservesLegacySemanticsAndModernPostActionsWithoutLeakingThemIntoPageActions() {
        val actions = """"actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":{"likeEndpoint":{"status":"LIKE","target":{"postId":"Ugkx_actions"}}}}}}}"""
        val modern = renderer("Ugkx_actions", "\"contentText\":{\"simpleText\":\"Post body\"},$actions")
        val legacy = modern.replace("postRenderer", "backstagePostRenderer")
        assertEquals(posts(jsonObject(legacy)!!, null), posts(jsonObject(modern)!!, null))
        val loaded = read(page(shelf(modern + "," + cta))).value!!
        assertEquals("like/like", loaded.items.single().post!!.like!!.endpoint)
        assertEquals(1, loaded.actions.size)
        val old = posts(jsonObject("""{"backstagePostRenderer":{"postId":"short"}}""")!!, author).single()
        assertEquals(author, old.authorId) // Existing backstage fallback remains unchanged.
        assertNull(old.permalink)
    }

    @Test fun textImageAndValidPollSummariesKeepContentArtworkSeparateFromTheAuthor() {
        val loaded = read(page(shelf(listOf(renderer("Ugkx_text"), renderer("Ugkx_image", image), renderer("Ugkx_poll", poll)).joinToString(",")))).value!!
        val text = loaded.items[0]
        assertNull(text.thumbnail); assertTrue(text.post!!.images.isEmpty()); assertNotNull(text.authorThumbnail)
        val imageOnly = loaded.items[1]
        assertNull(imageOnly.title); assertEquals("https://img.example/content", imageOnly.thumbnail)
        assertEquals(listOf("https://img.example/content"), imageOnly.post!!.images)
        val pollOnly = loaded.items[2]
        assertNull(pollOnly.title); assertNull(pollOnly.thumbnail)
        assertEquals(listOf("One", "Two"), pollOnly.post!!.poll!!.choices.map { it.text })
    }

    @Test fun malformedPostsAndUnsupportedOnlyAttachmentsCannotEstablishKnownSearchContent() {
        for (slot in listOf("""{"postRenderer":{}}""", renderer("bad id"), renderer("short"),
            renderer("Ugkx_empty", "\"contentText\":{}"), renderer("Ugkx_bad_text", "\"contentText\":[]"),
            renderer("Ugkx_quiz", "\"backstageAttachment\":{\"quizRenderer\":{}}"),
            renderer("Ugkx_bad_image", "\"backstageAttachment\":{\"backstageImageRenderer\":{\"image\":{\"thumbnails\":[{\"url\":\"javascript:bad\"}]}}}"),
            renderer("Ugkx_bad_poll", "\"backstageAttachment\":{\"pollRenderer\":{\"choices\":[{\"text\":{\"simpleText\":\"One\"}},{},null]}}"),
            renderer("Ugkx_valid").dropLast(1) + ",\"futureRenderer\":{}}")) rejected(shelf(slot))
    }

    @Test fun malformedOrUnknownCtaCannotBorrowANestedButtonOrBeIgnoredBesideOrganicCards() {
        for (slot in listOf(cta.replace("\"title\":\"Show native posts\",", ""),
            cta.replace("Show native posts", " "), cta.replace("\"title\":\"Show native posts\"", "\"title\":{}"),
            cta.replace("browseEndpoint", "futureEndpoint"), cta.replace("\"browseId\":\"$author\",", ""),
            cta.replace("\"browseEndpoint\":", "\"futureCommand\":{},\"browseEndpoint\":"),
            """{"buttonCardViewModel":{"title":"No own command","content":{"buttonRenderer":{
                "text":{"simpleText":"Nested"},"navigationEndpoint":{"browseEndpoint":{"browseId":"$author"}}}}}}"""))
            rejected(shelf(renderer("Ugkx_visible") + "," + slot))
    }

    @Test fun continuationRetainsPostsAndIssuedActionsAndAdvertisingCanNeverSupplyEither() {
        val body = """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[${shelf(renderer("Ugkx_next") + "," + cta)}]}}]}"""
        val continued = read(body, true).value!!
        assertEquals("Ugkx_next", continued.items.single().post!!.postId)
        assertEquals("Show native posts", continued.actions.single().title)
        val ad = """{"searchPyvRenderer":{"content":${shelf(renderer("Ugkx_ad") + "," + cta)}}}"""
        val empty = read(page(ad, "0")).value!!
        assertTrue(empty.items.isEmpty()); assertTrue(empty.groups.isEmpty()); assertTrue(empty.actions.isEmpty()); assertNull(empty.nextKey)
        val organic = read(page(ad + "," + video("organic"))).value!!
        assertEquals(listOf("organic"), organic.items.map { it.id }); assertTrue(organic.actions.isEmpty())
    }

    @Test fun catalogPreparationDeliversTypedPostAndItsNativeGroupActionWithoutAdditionalIo() {
        var requests = 0
        val body = page(shelf(renderer("Ugkx_catalog") + "," + cta))
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { requests++; return HttpReply(200, body) } }
        val catalog = CatalogSession(AccountSession(), http, 0).search("fixture").value!!
        val card = catalog.items.single()
        assertEquals("Ugkx_catalog", card.item.post!!.postId)
        assertEquals("https://www.youtube.com/post/Ugkx_catalog", card.item.post!!.permalink)
        assertEquals(author, card.author.channelId); assertNotNull(card.author.avatar); assertNull(card.author.unavailableReason)
        assertEquals(catalog.actions.single(), catalog.groups.single().actions.single())
        assertEquals(1, requests)
    }
}
