package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackFreshCacheTest {
    private fun reply(route: Int) = HttpReply(200,
        """{"streamingData":{"formats":[{"url":"https://media/route$route"}]}}""")

    @Test fun freshReplacesStartupUrlsAndKeepsOtherVideosCached() {
        val calls = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest) = reply(calls.incrementAndGet())
        })
        val old = session.load("v", includeMuxedFloor = false).value!!
        val other = session.load("other", includeMuxedFloor = false).value!!
        val fresh = session.load("v", fresh = true).value!!
        assertNotEquals(old.source, fresh.source)
        assertEquals(fresh.source, session.load("v", includeMuxedFloor = false).value!!.source)
        assertEquals(other.source, session.load("other", includeMuxedFloor = false).value!!.source)
        assertEquals(3, calls.get())
    }

    @Test fun networkNamespaceSeparatesResultsWithoutChangingAccountIdentity() {
        val calls = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest) = reply(calls.incrementAndGet())
        })
        val account = RequestContext(accountId = "account", generation = 7)
        val old = session.load("v", account, cacheNamespace = "network1").value!!
        val moved = session.load("v", account, cacheNamespace = "network2").value!!
        assertNotEquals(old.source, moved.source)
        assertEquals(old.source!!.context.ledgerScope(), moved.source!!.context.ledgerScope())
        assertEquals(moved.source, session.load("v", account, cacheNamespace = "network2").value!!.source)
        assertEquals(2, calls.get())
    }

    @Test fun refreshFencesAnOlderInFlightWrite() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val route = calls.incrementAndGet()
                if (route == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                return reply(route)
            }
        })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit(Callable { session.load("v", includeMuxedFloor = false) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val fresh = session.load("v", fresh = true, includeMuxedFloor = false).value!!
            release.countDown()
            assertTrue(old.get(2, TimeUnit.SECONDS).ok)
            assertEquals(fresh.source, session.load("v", includeMuxedFloor = false).value!!.source)
            assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun concurrentRefreshesJoinOneRequest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val route = calls.incrementAndGet()
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                return reply(route)
            }
        })
        val pool = Executors.newSingleThreadExecutor()
        var joined: ReadResult<PlaybackLoad>? = null
        val follower = Thread { joined = session.load("v", fresh = true) }
        try {
            val first = pool.submit(Callable { session.load("v", fresh = true) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            follower.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (follower.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, follower.state)
            release.countDown(); follower.join(2000)
            assertEquals(first.get(2, TimeUnit.SECONDS).value!!.source, joined!!.value!!.source)
            assertEquals(1, calls.get())
        } finally { release.countDown(); follower.interrupt(); pool.shutdownNow() }
    }

    @Test fun refreshDoesNotReuseAnotherDemand() {
        val calls = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest) = reply(calls.incrementAndGet())
        })
        session.load("v", fresh = true, includeMuxedFloor = false)
        session.load("v", excluded = setOf(PlayerClient.WEB), includeMuxedFloor = false)
        assertEquals(2, calls.get())
    }

    @Test fun evictedScopeCannotPublishAnOldTicket() {
        val cache = PlaybackResults(1) { 0L }
        val old = cache.ticket("a")
        cache.ticket("b")
        val new = cache.ticket("a")
        val value = PlaybackLoad(PlaybackForm.VOD, null, Playback(null, null, null))
        cache.put(old, "demand", value, 100)
        assertNull(cache.get(new, "demand"))
        cache.put(new, "demand", value, 100)
        assertSame(value, cache.get(new, "demand"))
    }

    @Test fun startupResultCannotSatisfyACompleteCatalogRequest() {
        val calls = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest) = reply(calls.incrementAndGet())
        })
        session.load("v", includeMuxedFloor = false)
        val catalog = session.load("v", includeMuxedFloor = true).value!!
        assertEquals(2, calls.get())
        assertEquals(catalog.source, session.load("v", includeMuxedFloor = false).value!!.source)
        assertEquals(2, calls.get())
    }

    @Test fun failedScriptRefreshAlsoInvalidatesOldMediaResults() {
        val calls = AtomicInteger()
        val session = PlaybackSession(http = object : HttpClient {
            override fun execute(request: HttpRequest) = reply(calls.incrementAndGet())
        }, scripts = object : PlayerScriptProvider {
            override fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript> =
                if (fresh) ReadResult(error = RequestError(RequestFailure.TRANSPORT, "script_failed"))
                else ReadResult(PlayerScript("https://script/base.js", "script"))
        })
        val old = session.load("v", includeMuxedFloor = false).value!!
        assertFalse(session.load("v", fresh = true).ok)
        val after = session.load("v", includeMuxedFloor = false).value!!
        assertNotEquals(old.source, after.source)
        assertEquals(2, calls.get())
    }
}
