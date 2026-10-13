package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackLockTest {
    private val sample = PlaybackLoad(PlaybackForm.VOD, PlayerClient.WEB, Playback(null, "https://example/v", null))

    @Test
    fun oneVideoSharesTheLoad() {
        val lock = PlaybackLock()
        val calls = AtomicInteger()
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val results = arrayOfNulls<PlaybackLoad>(2)
        val first = Thread {
            results[0] = lock.load("video") {
                calls.incrementAndGet()
                inside.countDown()
                finish.await(5, TimeUnit.SECONDS)
                sample
            }
        }
        first.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val second = Thread {
            results[1] = lock.load("video") {
                calls.incrementAndGet()
                sample
            }
        }
        second.start()
        Thread.sleep(200)
        assertEquals(1, calls.get())
        finish.countDown()
        first.join(5000)
        second.join(5000)
        assertEquals(1, calls.get())
        assertSame(sample, results[0])
        assertSame(sample, results[1])
        lock.load("video") { calls.incrementAndGet(); sample }
        assertEquals(2, calls.get())
    }

    @Test
    fun differentVideosLoadTogether() {
        val lock = PlaybackLock()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val threads = listOf("one", "two").map { videoId ->
            Thread {
                lock.load(videoId) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    sample
                }
            }
        }
        threads.forEach { it.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        threads.forEach { it.join(5000) }
    }

    @Test
    fun waitersSeeTheSameFailure() {
        val lock = PlaybackLock()
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val seen = arrayOfNulls<String>(2)
        val first = Thread {
            try {
                lock.load("video") {
                    inside.countDown()
                    finish.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("down")
                }
            } catch (failure: IllegalStateException) {
                seen[0] = failure.message
            }
        }
        first.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val second = Thread {
            try {
                lock.load("video") { throw IllegalStateException("other") }
            } catch (failure: IllegalStateException) {
                seen[1] = failure.message
            }
        }
        second.start()
        Thread.sleep(200)
        finish.countDown()
        first.join(5000)
        second.join(5000)
        assertEquals("down", seen[0])
        assertEquals("down", seen[1])
    }

    @Test
    fun mediaFailureMovesTheClientToTheEnd() {
        val queues = PlayerClientQueues()
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        queues.demote(vod, PlayerClient.VISIONOS)
        assertEquals(PlayerClient.WEB, queues.order(vod).first())
        assertEquals(PlayerClient.VISIONOS, queues.order(vod).last())

        val live = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.LIVE)
        val before = queues.order(live)
        queues.demote(live, PlayerClient.TV)
        assertEquals(before, queues.order(live))
    }
}
