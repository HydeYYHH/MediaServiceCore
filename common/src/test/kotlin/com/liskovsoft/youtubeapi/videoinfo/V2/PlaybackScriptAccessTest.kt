package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PlaybackScriptAccessTest {
    private class Scripts : PlayerScriptProvider {
        var revision = "one"
        val reads = AtomicInteger()
        var result: () -> ReadResult<PlayerScript> = { ReadResult(PlayerScript(revision, "signatureTimestamp:123")) }
        override fun cacheKey(videoId: String, context: RequestContext) = revision
        override fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript> {
            reads.incrementAndGet()
            return result()
        }
    }

    private fun direct() = HttpReply(200, """{"streamingData":{"formats":[{"url":"https://media.test/video"}]}}""")

    @Test fun directSourceAndCacheHitDoNotReadTheScript() {
        val scripts = Scripts().apply { result = { error("Unexpected script read") } }
        var requests = 0
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { requests++; return direct() }
        }, scripts = scripts)
        val first = session.load("video")
        assertTrue(first.ok)
        assertEquals(PlayerClient.VISIONOS, first.value?.client)
        assertEquals(first.value?.source, session.load("video").value?.source)
        assertEquals(0, scripts.reads.get())
        assertEquals(1, requests)
    }

    @Test fun scriptRevisionAndExplicitRefreshInvalidateTheResult() {
        val scripts = Scripts()
        var requests = 0
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { requests++; return direct() }
        }, scripts = scripts)
        val first = session.load("video").value?.source
        scripts.revision = "two"
        assertNotEquals(first, session.load("video").value?.source)
        assertTrue(session.load("video", fresh = true).ok)
        assertEquals(3, requests)
        assertEquals(0, scripts.reads.get())
    }

    @Test fun browserClientReceivesTheTimestampAndReadsTheScriptOnce() {
        val scripts = Scripts()
        val timestamps = mutableListOf<Int?>()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                timestamps += jsonObject(request.body)?.path("playbackContext", "contentPlaybackContext")?.int("signatureTimestamp")
                return direct()
            }
        }, scripts = scripts)
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=token"))
        assertEquals(PlayerClient.WEB_SAFARI, session.load("video", context).value?.client)
        assertTrue(session.load("video", context).ok)
        assertEquals(listOf(123), timestamps)
        assertEquals(1, scripts.reads.get())
    }

    @Test fun nativeCipherUsesTheSameLazyScriptForSignatureAndThrottle() {
        val scripts = Scripts()
        val solver = object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                assertEquals("one", request.script.url)
                assertEquals(setOf("encrypted"), request.signatures)
                assertEquals(setOf("slow"), request.throttles)
                return PlayerChallenge(mapOf("encrypted" to "signature"), mapOf("slow" to "fast"))
            }
        }
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"streamingData":{"formats":[{"signatureCipher":"s=encrypted&url=https%3A%2F%2Fmedia.test%2Fvideo%3Fn%3Dslow"}]}}""")
        }, scripts = scripts, solver = solver)
        val result = session.load("video")
        assertTrue(result.ok)
        val url = requireNotNull(result.value?.answer?.muxedFormats?.single()?.url)
        assertEquals("signature", urlParameter(url, "sig"))
        assertEquals("fast", urlParameter(url, "n"))
        assertEquals(1, scripts.reads.get())
    }

    @Test fun scriptFailureLeavesAnIndependentDirectClientAvailable() {
        val scripts = Scripts().apply { result = { ReadResult(error = RequestError(RequestFailure.TRANSPORT, "script_failed")) } }
        val attempts = mutableListOf<PlayerLoadAttempt>()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest) = direct()
        }, scripts = scripts, onAttempt = attempts::add)
        val result = session.load("video", RequestContext(accountId = "account", webSession = WebSession("SAPISID=token")))
        assertTrue(result.ok)
        assertEquals(PlayerClient.VISIONOS, result.value?.client)
        assertEquals(listOf(PlayerClient.WEB_SAFARI, PlayerClient.VISIONOS), attempts.map { it.client })
        assertEquals("script_failed", attempts.first().error?.code)
        assertEquals(1, scripts.reads.get())
    }

    @Test fun cancellationWhileReadingACipherScriptDoesNotWalkOtherClients() {
        val scripts = Scripts().apply { result = { throw InterruptedException() } }
        var requests = 0
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                return HttpReply(200, """{"streamingData":{"formats":[{"signatureCipher":"s=encrypted&url=https%3A%2F%2Fmedia.test"}]}}""")
            }
        }, scripts = scripts, solver = object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest) = error("Unexpected solve")
        })
        try {
            assertEquals(RequestFailure.CANCELLED, session.load("video").error?.kind)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, requests)
        } finally { Thread.interrupted() }
    }

    @Test fun concurrentBrowserLoadsShareScriptAndPlayerWork() {
        val scripts = Scripts()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return direct()
            }
        }, scripts = scripts)
        val pool = Executors.newFixedThreadPool(2)
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=token"))
        try {
            val first = pool.submit(Callable { session.load("video", context) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val follower = pool.submit(Callable { session.load("video", context) })
            release.countDown()
            assertEquals(first.get(2, TimeUnit.SECONDS).value?.source, follower.get(2, TimeUnit.SECONDS).value?.source)
            assertEquals(1, requests.get())
            assertEquals(1, scripts.reads.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
