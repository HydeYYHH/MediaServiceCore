package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ArtworkPreparationTest {
    private val first = "UC" + "a".repeat(22)
    private val second = "UC" + "b".repeat(22)
    private fun card(id: String, channel: String? = first, avatar: String? = null) =
        MediaItem("video", id, "Title", "Shared display name", "https://example.test/cover", null,
            channelId = channel, authorThumbnail = avatar)
    private fun response(id: String, suffix: String = "avatar") = """{"metadata":{"channelMetadataRenderer":{
        "externalId":"$id","avatar":{"thumbnails":[{"url":"https://example.test/$suffix","width":88,"height":88}]}}}}"""
    private fun transport(block: (HttpRequest) -> HttpReply) = object : HttpClient {
        override fun execute(request: HttpRequest) = block(request)
    }

    @Test fun embeddedVariantsRetainDimensionsAndPrepareWithoutAnyNetworkOrUrlSynthesis() {
        val item = searchPage("""{"videoRenderer":{"videoId":"video","channelId":"$first",
            "channelThumbnailSupportedRenderers":{"channelThumbnailWithLinkRenderer":{"thumbnail":{"thumbnails":[
                {"url":"https://example.test/large","width":176,"height":176},
                {"url":"//example.test/small","width":48,"height":48},
                {"url":"javascript:unsupported","width":1000,"height":1000}]}}}}}""").items.single()
        val preparation = PagePreparation(transport { error("Embedded images must not trigger HTTP") })
        val result = preparation.prepare(listOf(item)).value!!
        val author = result.items.single().author
        assertEquals(0, result.requestCount)
        assertEquals(first, author.channelId)
        assertNull(author.unavailableReason)
        assertEquals(listOf(176, 48), author.avatar!!.variants.map { it.width })
        assertEquals("https://example.test/large", author.avatar!!.best!!.url)
        assertEquals("https://example.test/small", author.avatar!!.select(40, 40)!!.url)
        assertEquals("https://example.test/large", item.authorThumbnail)
        assertEquals("https://example.test/cover", card("cover").thumbnail)
    }

    @Test fun samePageCanonicalIdentityIsReusedBeforeRequestsAndChannelCardsHaveNoAuthor() {
        val preparation = PagePreparation(transport { error("A same-page image is sufficient") })
        val channel = MediaItem("channel", second, "Channel", null, "https://example.test/channel", null, channelId = second)
        val result = preparation.prepare(listOf(card("missing"), card("embedded", avatar = "https://example.test/author"),
            card("channel-peer", second), channel, card("unknown", null), card("unlinked", null, "https://example.test/unlinked"))).value!!
        assertEquals("https://example.test/author", result.items[0].author.avatar!!.best!!.url)
        assertEquals("https://example.test/channel", result.items[2].author.avatar!!.best!!.url)
        assertNull(result.items[3].author.channelId)
        assertNull(result.items[3].author.avatar)
        assertEquals("not_applicable", result.items[3].author.unavailableReason)
        assertEquals("canonical_channel_id_unavailable", result.items[4].author.unavailableReason)
        assertNull(result.items[5].author.channelId)
        assertNotNull(result.items[5].author.avatar)
        assertEquals(0, result.requestCount)
        val nextPage = preparation.prepare(listOf(card("next"))).value!!
        assertEquals("https://example.test/author", nextPage.items.single().author.avatar!!.best!!.url)
        assertEquals(0, nextPage.requestCount)
    }

    @Test fun actualHttpBudgetDeduplicatesCanonicalChannelsAndPublicLookupDropsCredentials() {
        val requests = ArrayList<HttpRequest>()
        val preparation = PagePreparation(transport { request ->
            requests += request
            assertEquals(first, jsonObject(request.body)!!.text("browseId"))
            HttpReply(200, response(first))
        })
        val context = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=secret", 2), onBehalfOfUser = "brand",
            visitorCookie = "SID=also-secret", language = "zh", region = "TW")
        val result = preparation.prepare(listOf(card("a"), card("b"), card("c", second), card("bad", "@same-name")), context, 1).value!!
        assertEquals(1, result.requestCount)
        assertEquals(1, requests.size)
        assertEquals(result.items[0].author.avatar, result.items[1].author.avatar)
        assertEquals("request_budget_exhausted", result.items[2].author.unavailableReason)
        assertEquals("canonical_channel_id_unavailable", result.items[3].author.unavailableReason)
        val request = requests.single()
        assertTrue(request.url.endsWith("prettyPrint=false&fields=metadata%2Cheader"))
        assertTrue(request.headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) ||
            it.equals("X-Goog-AuthUser", true) || it.equals("X-Goog-PageId", true) })
        assertFalse(request.body.contains("secret"))
        assertFalse(request.body.contains("onBehalfOfUser"))
        assertEquals("zh", jsonObject(request.body)!!.path("context", "client")!!.text("hl"))
    }

    @Test fun failuresAndIdentityMismatchRemainFixedReasonsWithoutRetryOrCachePoisoning() {
        var calls = 0
        val preparation = PagePreparation(transport {
            calls++
            if (calls == 1) HttpReply(429, "opaque sensitive response") else HttpReply(200, response(second))
        })
        val failure = preparation.prepare(listOf(card("a"), card("b")), maxRequests = 4).value!!
        assertEquals(1, calls)
        assertEquals(1, failure.requestCount)
        assertTrue(failure.items.all { it.author.unavailableReason == "artwork_source_failed" })
        val mismatch = preparation.prepare(listOf(card("a")), maxRequests = 1).value!!
        assertEquals("channel_identity_mismatch", mismatch.items.single().author.unavailableReason)
        assertNull(mismatch.items.single().author.avatar)
        assertEquals("request_budget_exhausted", preparation.prepare(listOf(card("a"))).value!!.items.single().author.unavailableReason)
        assertEquals(2, calls)
    }

    @Test fun avatarWithoutCanonicalResponseIdentityCannotBeAssignedOrCachedForRequestedAuthor() {
        val unidentifiedResponses = listOf(
            """{"metadata":{"channelMetadataRenderer":{"avatar":{"thumbnails":[
                {"url":"https://example.test/unidentified","width":88,"height":88}]}}}}""",
            """{"header":{"c4TabbedHeaderRenderer":{"avatar":{"thumbnails":[
                {"url":"https://example.test/unidentified","width":88,"height":88}]}}}}"""
        )
        for (unidentified in unidentifiedResponses) {
            var calls = 0
            val preparation = PagePreparation(transport {
                calls++
                HttpReply(200, if (calls == 1) unidentified else response(first, "verified"))
            })
            val rejected = preparation.prepare(listOf(card("initial")), maxRequests = 1).value!!
            assertEquals(1, rejected.requestCount)
            assertNull(rejected.items.single().author.avatar)
            assertEquals("channel_identity_unavailable", rejected.items.single().author.unavailableReason)
            val uncached = preparation.prepare(listOf(card("next-page"))).value!!
            assertEquals(0, uncached.requestCount)
            assertNull(uncached.items.single().author.avatar)
            assertEquals("request_budget_exhausted", uncached.items.single().author.unavailableReason)
            assertEquals(1, calls)
            val verified = preparation.prepare(listOf(card("verified-page")), maxRequests = 1).value!!
            assertEquals("https://example.test/verified", verified.items.single().author.avatar!!.best!!.url)
            assertEquals(0, preparation.prepare(listOf(card("cached-page"))).value!!.requestCount)
            assertEquals(2, calls)
        }
    }

    @Test fun cacheIsBoundedExpiresAndCanBeClearedWithoutChangingTheSourcePage() {
        var now = 100L
        var calls = 0
        val preparation = PagePreparation(transport { request ->
            calls++
            HttpReply(200, response(jsonObject(request.body)!!.text("browseId")!!))
        }, maxEntries = 1, ttlMs = 10, clock = { now })
        assertNotNull(preparation.prepare(listOf(card("a")), maxRequests = 1).value!!.items.single().author.avatar)
        assertEquals(0, preparation.prepare(listOf(card("cached"))).value!!.requestCount)
        preparation.prepare(listOf(card("b", second)), maxRequests = 1)
        assertNull(preparation.prepare(listOf(card("evicted"))).value!!.items.single().author.avatar)
        now += 10
        assertNull(preparation.prepare(listOf(card("expired", second))).value!!.items.single().author.avatar)
        preparation.prepare(listOf(card("fresh", second)), maxRequests = 1)
        preparation.clear()
        assertNull(preparation.prepare(listOf(card("cleared", second))).value!!.items.single().author.avatar)
        assertEquals(3, calls)
    }

    @Test fun concurrentPreparationSharesOneReadWithoutHoldingTheCacheMonitorDuringIo() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val follower = AtomicReference<Thread>()
        val calls = AtomicInteger()
        val preparation = PagePreparation(transport {
            calls.incrementAndGet(); entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            HttpReply(200, response(first))
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<ReadResult<PreparedPage>> { preparation.prepare(listOf(card("a")), maxRequests = 1) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val b = pool.submit<ReadResult<PreparedPage>> {
                follower.set(Thread.currentThread())
                preparation.prepare(listOf(card("b")), maxRequests = 1)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (follower.get()?.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, follower.get()?.state)
            release.countDown()
            val firstResult = a.get(2, TimeUnit.SECONDS).value!!
            val secondResult = b.get(2, TimeUnit.SECONDS).value!!
            assertNotNull(firstResult.items.single().author.avatar)
            assertEquals(firstResult.items.single().author.avatar, secondResult.items.single().author.avatar)
            assertEquals(1, firstResult.requestCount + secondResult.requestCount)
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun clearingWhileAReadIsInFlightPreventsOldResultsFromRefillingTheCache() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparation = PagePreparation(transport {
            entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); HttpReply(200, response(first))
        })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit<ReadResult<PreparedPage>> { preparation.prepare(listOf(card("old")), maxRequests = 1) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            preparation.clear()
            release.countDown()
            assertNotNull(old.get(2, TimeUnit.SECONDS).value!!.items.single().author.avatar)
            assertNull(preparation.prepare(listOf(card("new"))).value!!.items.single().author.avatar)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun invalidBudgetsFailBeforeIoAndDisplayNamesNeverBecomeIdentity() {
        val preparation = PagePreparation(transport { error("No valid channel identity or request budget") })
        for (budget in listOf(-1, 65)) assertEquals(RequestFailure.INVALID,
            preparation.prepare(listOf(card("a")), maxRequests = budget).error!!.kind)
        val result = preparation.prepare(listOf(card("a", null), card("b", "@handle")), maxRequests = 64).value!!
        assertTrue(result.items.all { it.author.unavailableReason == "canonical_channel_id_unavailable" })
        assertEquals(0, result.requestCount)
    }

    @Test fun perPageBoundTransportOverridesTheDefaultWithoutDiscardingTheInstanceCache() {
        val preparation = PagePreparation(transport { error("Catalog's bound transport must be used") })
        var calls = 0
        val bound = transport { calls++; HttpReply(200, response(first)) }
        assertNotNull(preparation.prepareBound(listOf(card("a")), RequestContext(), 1, bound).value!!.items.single().author.avatar)
        assertNotNull(preparation.prepare(listOf(card("b"))).value!!.items.single().author.avatar)
        assertEquals(1, calls)
    }

    @Test fun metadataLookupCannotBorrowRecursiveOwnerIdentityOrImagesFromAnotherRole() {
        val responses = listOf(
            """{"responseContext":${response(first)}}""" to "channel_identity_unavailable",
            """{"metadata":{"channelMetadataRenderer":{"externalId":"$first"}},"responseContext":{"channelHeaderRenderer":{"avatar":{"thumbnails":[{"url":"https://example.test/shadow"}]}}}}""" to "artwork_unavailable",
            """{"metadata":{"channelMetadataRenderer":{"externalId":"$first","avatar":{"thumbnails":[{"url":"https://example.test/owner"}]}}},"header":{"c4TabbedHeaderRenderer":{"channelId":"$second"}}}""" to "channel_identity_mismatch"
        )
        for ((body, reason) in responses) {
            var calls = 0
            val preparation = PagePreparation(transport { calls++; HttpReply(200, body) })
            val result = preparation.prepare(listOf(card("first")), maxRequests = 1).value!!
            assertNull(result.items.single().author.avatar); assertEquals(reason, result.items.single().author.unavailableReason)
            assertEquals(1, result.requestCount)
            val cached = preparation.prepare(listOf(card("next"))).value!!
            assertNull(cached.items.single().author.avatar); assertEquals("request_budget_exhausted", cached.items.single().author.unavailableReason)
            assertEquals(1, calls)
        }
    }

    @Test fun directHeaderLookupKeepsItsOwnVariantsWhileIgnoringShadowMetadata() {
        var calls = 0
        val preparation = PagePreparation(transport {
            calls++
            HttpReply(200, """{"header":{"c4TabbedHeaderRenderer":{"channelId":"$first","avatar":{"thumbnails":[{"url":"https://example.test/header","width":88,"height":88}]}}},"responseContext":${response(second, "shadow")}}""")
        })
        val result = preparation.prepare(listOf(card("first")), maxRequests = 1).value!!
        assertEquals("https://example.test/header", result.items.single().author.avatar!!.best!!.url)
        assertEquals(88, result.items.single().author.avatar!!.best!!.width)
        assertEquals(0, preparation.prepare(listOf(card("next"))).value!!.requestCount)
        assertEquals(1, calls)
    }
}
