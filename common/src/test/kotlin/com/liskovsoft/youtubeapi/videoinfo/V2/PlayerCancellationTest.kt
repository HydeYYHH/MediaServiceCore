package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlayerCancellationTest {
    @Test fun cancelledOwnerStopsImmediatelyWithoutChangingTheRememberedQueue() {
        val queues = PlayerClientQueues()
        val key = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        queues.commit(key, listOf(PlayerClient.VISIONOS to ClientOutcome.SOFT, PlayerClient.WEB_EMBED to ClientOutcome.PLAYED))
        val before = queues.order(key)
        var calls = 0
        val attempts = mutableListOf<PlayerLoadAttempt>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                throw IOException("SESSION_CHANGED_OR_CANCELLED")
            }
        }
        val result = loadPlayer("abcdefghijk", http = http, context = RequestContext(), queues = queues, onAttempt = attempts::add)
        assertEquals(RequestFailure.CANCELLED, result.error?.kind)
        assertEquals(1, calls)
        assertEquals(1, attempts.size)
        assertEquals(before, queues.order(key))
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun aRealForbiddenResponseStillUsesTheNextClient() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = if (++calls == 1) HttpReply(403, "denied")
                else HttpReply(200, """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
                    {"itag":137,"mimeType":"video/mp4","height":1080,"url":"https://media.test/video"},
                    {"itag":140,"mimeType":"audio/mp4","url":"https://media.test/audio"}]}}""")
        }
        assertTrue(loadPlayer("abcdefghijk", context = RequestContext(), http = http).ok)
        assertEquals(2, calls)
    }
}
