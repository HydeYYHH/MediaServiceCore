package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class JvmPlayerScriptsCacheTest {
    private val context = RequestContext(accountId = "owner", generation = 1,
        webSession = WebSession("SAPISID=fixture"))
    private val sourceUrl = "https://www.youtube.com/s/player/revision/base.js"

    @Test fun publicBytesAreSharedButEveryVideoAndSessionScopeStillResolvesItsOwnPage() {
        val pages = AtomicInteger()
        val sources = AtomicInteger()
        val http = client { request ->
            assertTrue(request.headers.isEmpty())
            assertEquals("GET", request.method)
            if (request.url.contains("/embed/")) { pages.incrementAndGet(); page() }
            else { sources.incrementAndGet(); HttpReply(200, "signatureTimestamp:42") }
        }
        val scripts = JvmPlayerScripts(http)
        val first = scripts.script("first", context, false).value!!
        assertSame(first, scripts.script("first", context, false).value)
        assertEquals(first.source, scripts.script("second", context, false).value!!.source)
        scripts.script("first", context.copy(onBehalfOfUser = "brand"), false).value!!
        scripts.script("first", context.copy(generation = 2), false).value!!
        assertEquals(4, pages.get())
        assertEquals(1, sources.get())

        // A different provider/transport must not see the first transport's script body.
        val other = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) page() else HttpReply(200, "other-client")
        })
        assertEquals("other-client", other.script("first", context, false).value!!.source)
    }

    @Test fun concurrentVideosJoinOneScriptDownloadWithoutHoldingUpTheOtherEmbedRequest() = withPool { pool ->
        val downloadStarted = CountDownLatch(1)
        val secondPage = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val sources = AtomicInteger()
        val secondThread = AtomicReference<Thread>()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) {
                if (request.url.contains("/second?")) secondPage.countDown()
                page()
            } else {
                sources.incrementAndGet()
                downloadStarted.countDown()
                assertTrue(releaseDownload.await(5, TimeUnit.SECONDS))
                HttpReply(200, "shared bytes")
            }
        })
        val first = pool.submit<ReadResult<PlayerScript>> { scripts.script("first", context, false) }
        await(downloadStarted)
        val second = pool.submit<ReadResult<PlayerScript>> {
            secondThread.set(Thread.currentThread())
            scripts.script("second", context, false)
        }
        try {
            await(secondPage) // This cannot happen while the old provider-wide HTTP lock is held.
            awaitWaiting(secondThread.get())
            assertFalse(first.isDone)
            assertFalse(second.isDone)
        } finally { releaseDownload.countDown() }
        assertEquals("shared bytes", first.get(5, TimeUnit.SECONDS).value!!.source)
        assertEquals("shared bytes", second.get(5, TimeUnit.SECONDS).value!!.source)
        assertEquals(1, sources.get())
    }

    @Test fun anUnrelatedScriptCanCompleteWhileAnotherPublicAssetIsBlocked() = withPool { pool ->
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val scripts = JvmPlayerScripts(client { request ->
            when {
                request.url.contains("/embed/slow?") -> page("/s/player/slow/base.js")
                request.url.contains("/embed/fast?") -> page("/s/player/fast/base.js")
                request.url.contains("/slow/") -> {
                    slowStarted.countDown()
                    assertTrue(releaseSlow.await(5, TimeUnit.SECONDS))
                    HttpReply(200, "slow")
                }
                else -> HttpReply(200, "fast")
            }
        })
        val slow = pool.submit<ReadResult<PlayerScript>> { scripts.script("slow", context, false) }
        await(slowStarted)
        try {
            val fast = pool.submit<ReadResult<PlayerScript>> { scripts.script("fast", context, false) }
            assertEquals("fast", fast.get(5, TimeUnit.SECONDS).value!!.source)
            assertFalse(slow.isDone)
        } finally { releaseSlow.countDown() }
        assertEquals("slow", slow.get(5, TimeUnit.SECONDS).value!!.source)
    }

    @Test fun reusingBytesDoesNotExtendTheirTtlAndBothCachesRemainBounded() {
        var now = 0L
        val pages = AtomicInteger()
        val sources = AtomicInteger()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) { pages.incrementAndGet(); page() }
            else { sources.incrementAndGet(); HttpReply(200, "version-${sources.get()}") }
        }, { now }, ttlMs = 10)
        assertEquals("version-1", scripts.script("first", context, false).value!!.source)
        now = 9
        assertEquals("version-1", scripts.script("second", context, false).value!!.source)
        now = 10
        assertEquals("version-2", scripts.script("second", context, false).value!!.source)
        assertEquals(3, pages.get())
        assertEquals(2, sources.get())

        val counts = HashMap<String, Int>()
        val bounded = JvmPlayerScripts(client { request ->
            val name = if (request.url.contains("first")) "first" else "second"
            if (request.url.contains("/embed/")) page("/s/player/$name/base.js")
            else {
                counts[name] = counts.getOrDefault(name, 0) + 1
                HttpReply(200, name)
            }
        }, maxEntries = 1)
        bounded.script("first", context, false).value!!
        bounded.script("second", context, false).value!!
        bounded.script("first", context, false).value!!
        assertEquals(mapOf("first" to 2, "second" to 1), counts)
    }

    @Test fun refreshBypassesBothCachesAndOldFlightsCannotRefillEitherOfThem() = withPool { pool ->
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val sources = AtomicInteger()
        val pages = AtomicInteger()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) { pages.incrementAndGet(); page() }
            else if (sources.incrementAndGet() == 1) {
                oldStarted.countDown()
                assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                HttpReply(200, "old")
            } else HttpReply(200, "new")
        })
        val old = pool.submit<ReadResult<PlayerScript>> { scripts.script("first", context, false) }
        await(oldStarted)
        try {
            val fresh = pool.submit<ReadResult<PlayerScript>> { scripts.script("first", context, true) }
            assertEquals("new", fresh.get(5, TimeUnit.SECONDS).value!!.source)
            assertFalse(old.isDone)
        } finally { releaseOld.countDown() }
        assertEquals("old", old.get(5, TimeUnit.SECONDS).value!!.source)
        assertEquals("new", scripts.script("first", context, false).value!!.source)
        assertEquals("new", scripts.script("second", context, false).value!!.source)
        assertEquals(3, pages.get())
        assertEquals(2, sources.get())
    }

    @Test fun interruptingAWaiterDoesNotCancelTheSharedDownload() = withPool { pool ->
        val started = CountDownLatch(1)
        val secondPage = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sources = AtomicInteger()
        val waiterThread = AtomicReference<Thread>()
        val waiterInterrupted = AtomicBoolean()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) {
                if (request.url.contains("second")) secondPage.countDown()
                page()
            } else {
                sources.incrementAndGet()
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                HttpReply(200, "shared")
            }
        })
        val owner = pool.submit<ReadResult<PlayerScript>> { scripts.script("first", context, false) }
        await(started)
        val waiter = pool.submit<ReadResult<PlayerScript>> {
            waiterThread.set(Thread.currentThread())
            scripts.script("second", context, false).also {
                waiterInterrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            await(secondPage)
            awaitWaiting(waiterThread.get())
            waiterThread.get().interrupt()
            assertEquals(RequestFailure.CANCELLED, waiter.get(5, TimeUnit.SECONDS).error!!.kind)
            assertTrue(waiterInterrupted.get())
            assertFalse(owner.isDone)
        } finally { release.countDown() }
        assertEquals("shared", owner.get(5, TimeUnit.SECONDS).value!!.source)
        assertEquals("shared", scripts.script("third", context, false).value!!.source)
        assertEquals(1, sources.get())
    }

    @Test fun aCancelledOwnerDoesNotPoisonAnUninterruptedWaitingVideo() = withPool { pool ->
        val started = CountDownLatch(1)
        val secondPage = CountDownLatch(1)
        val ownerThread = AtomicReference<Thread>()
        val waiterThread = AtomicReference<Thread>()
        val sources = AtomicInteger()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) {
                if (request.url.contains("second")) secondPage.countDown()
                page()
            } else if (sources.incrementAndGet() == 1) {
                started.countDown()
                CountDownLatch(1).await() // The test interrupts this owner; there is no successful old body.
                error("Interrupted download unexpectedly resumed")
            } else HttpReply(200, "recovered")
        })
        val owner = pool.submit<ReadResult<PlayerScript>> {
            ownerThread.set(Thread.currentThread())
            scripts.script("first", context, false)
        }
        await(started)
        val waiter = pool.submit<ReadResult<PlayerScript>> {
            waiterThread.set(Thread.currentThread())
            scripts.script("second", context, false)
        }
        await(secondPage)
        awaitWaiting(waiterThread.get())
        ownerThread.get().interrupt()
        assertEquals(RequestFailure.CANCELLED, owner.get(5, TimeUnit.SECONDS).error!!.kind)
        assertEquals("recovered", waiter.get(5, TimeUnit.SECONDS).value!!.source)
        assertEquals("recovered", scripts.script("third", context, false).value!!.source)
        assertEquals(2, sources.get())
    }

    @Test fun boundedAdmissionCanBeCancelledWhileSameVideoReadersStillJoinItsOwner() = withPool { pool ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pages = AtomicInteger()
        val sources = AtomicInteger()
        val waitingThread = AtomicReference<Thread>()
        val duplicateThread = AtomicReference<Thread>()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) { pages.incrementAndGet(); page() }
            else {
                if (sources.incrementAndGet() == 1) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                HttpReply(200, "shared")
            }
        }, maxEntries = 1)
        val owner = pool.submit<ReadResult<PlayerScript>> { scripts.script("first", context, false) }
        await(started)
        val waiting = pool.submit<ReadResult<PlayerScript>> {
            waitingThread.set(Thread.currentThread())
            scripts.script("second", context, false)
        }
        val duplicate = pool.submit<ReadResult<PlayerScript>> {
            duplicateThread.set(Thread.currentThread())
            scripts.script("first", context, false)
        }
        try {
            awaitThread(waitingThread)
            awaitThread(duplicateThread)
            awaitWaiting(waitingThread.get())
            awaitWaiting(duplicateThread.get())
            assertEquals("A distinct page exceeded the configured admission bound", 1, pages.get())
            waitingThread.get().interrupt()
            assertEquals(RequestFailure.CANCELLED, waiting.get(5, TimeUnit.SECONDS).error!!.kind)
        } finally { release.countDown() }
        val result = owner.get(5, TimeUnit.SECONDS).value!!
        assertSame(result, duplicate.get(5, TimeUnit.SECONDS).value)
        assertEquals("shared", scripts.script("second", context, false).value!!.source)
        assertEquals(2, pages.get())
        assertEquals(1, sources.get())
    }

    @Test fun failuresOversizedBodiesAndNonPublicUrlsAreNotShared() {
        val sources = AtomicInteger()
        val scripts = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) page()
            else if (sources.incrementAndGet() == 1) HttpReply(503, "busy", mapOf("Retry-After" to "2"))
            else HttpReply(200, "retry succeeded")
        })
        val failure = scripts.script("first", context, false).error!!
        assertEquals(RequestFailure.HTTP, failure.kind)
        assertEquals(503, failure.status)
        assertEquals(2_000L, failure.retryAfterMs)
        assertEquals("retry succeeded", scripts.script("first", context, false).value!!.source)
        assertEquals(2, sources.get())

        var refreshFails = false
        val refreshReads = AtomicInteger()
        val refreshable = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) page()
            else {
                refreshReads.incrementAndGet()
                if (refreshFails) HttpReply(503, "busy") else HttpReply(200, "still valid")
            }
        })
        refreshable.script("first", context, false).value!!
        refreshFails = true
        assertEquals(503, refreshable.script("first", context, true).error!!.status)
        assertEquals("still valid", refreshable.script("first", context, false).value!!.source)
        assertEquals("still valid", refreshable.script("second", context, false).value!!.source)
        assertEquals(2, refreshReads.get()) // An unsuccessful refresh retains the earlier unexpired entry.

        val oversized = "x".repeat(4 * 1024 * 1024 + 1)
        val largeReads = AtomicInteger()
        val large = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) page()
            else { largeReads.incrementAndGet(); HttpReply(200, oversized) }
        })
        listOf("first", "second", "first").forEach { id ->
            assertEquals(oversized.length, large.script(id, context, false).value!!.source.length)
        }
        assertEquals(3, largeReads.get())

        val privateReads = AtomicInteger()
        val privateAsset = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) page("/s/player/revision/base.js?session=variant")
            else { privateReads.incrementAndGet(); HttpReply(200, "unshared") }
        })
        privateAsset.script("first", context, false).value!!
        privateAsset.script("second", context, false).value!!
        assertEquals(2, privateReads.get())

        val htmlReads = AtomicInteger()
        val unexpectedHtml = JvmPlayerScripts(client { request ->
            if (request.url.contains("/embed/")) page()
            else { htmlReads.incrementAndGet(); HttpReply(200, " <!doctype html><title>Verify</title>") }
        })
        listOf("first", "second", "first").forEach { id ->
            // Keep the provider's existing response contract; never retain/share an HTML challenge body.
            assertTrue(unexpectedHtml.script(id, context, false).value!!.source.contains("<!doctype html>"))
        }
        assertEquals(3, htmlReads.get())

        val rejectedReads = AtomicInteger()
        val untrusted = JvmPlayerScripts(client {
            rejectedReads.incrementAndGet()
            page("https://credentials@www.youtube.com/s/player/revision/base.js")
        })
        assertEquals("invalid_player_script_url", untrusted.script("first", context, false).error!!.code)
        assertEquals(1, rejectedReads.get())
    }

    private fun page(path: String = sourceUrl) = HttpReply(200, """{"jsUrl":"$path"}""")
    private fun client(handler: (HttpRequest) -> HttpReply) = object : HttpClient {
        override fun execute(request: HttpRequest) = handler(request)
    }
    private fun await(latch: CountDownLatch) = assertTrue("Timed out waiting for the coordinated HTTP request",
        latch.await(5, TimeUnit.SECONDS))
    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && thread.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Caller did not reach its interruptible wait", Thread.State.WAITING, thread.state)
    }
    private fun awaitThread(reference: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (reference.get() == null && System.nanoTime() < deadline) Thread.yield()
        assertNotNull("Reader did not start", reference.get())
    }
    private fun withPool(block: (ExecutorService) -> Unit) {
        val pool = Executors.newFixedThreadPool(4)
        try { block(pool) }
        finally {
            pool.shutdownNow()
            assertTrue("Script readers did not terminate", pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
