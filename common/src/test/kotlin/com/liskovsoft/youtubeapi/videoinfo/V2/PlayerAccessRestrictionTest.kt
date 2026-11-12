package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerAccessRestrictionTest {
    @Test fun onlyExplicitPaymentMessagesEstablishAPaywall() {
        for (reason in listOf("This video requires payment to watch", "Buy or rent this movie", "此视频需要付费才能观看")) {
            assertEquals(PlayerAccessRestriction.PAYMENT_REQUIRED, playerAccessRestriction("UNPLAYABLE", reason))
        }
        for (reason in listOf("Video unavailable", "Members only", "Subscribe to this channel", "Sign in to confirm your age")) {
            assertNull(playerAccessRestriction("UNPLAYABLE", reason))
        }
        assertNull(playerAccessRestriction("OK", "Buy or rent"))
        assertEquals(PlayerAccessRestriction.LOGIN_REQUIRED,
            playerAccessRestriction("LOGIN_REQUIRED", "Sign in to watch this video"))
    }

    @Test fun accessRestrictionTravelsWithTheAttemptWithoutRawServerText() {
        val attempts = mutableListOf<PlayerLoadAttempt>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"playabilityStatus":{"status":"UNPLAYABLE","reason":{"runs":[{"text":"This video requires payment to watch"}]}}}""")
        }
        assertFalse(loadPlayer("video", RequestContext(), http, onAttempt = attempts::add).ok)
        assertTrue(attempts.isNotEmpty())
        assertTrue(attempts.all { it.restriction == PlayerAccessRestriction.PAYMENT_REQUIRED })
        assertTrue(attempts.all { it.error?.code == "playability_unplayable" })
    }

    @Test fun paymentOnOneClientDoesNotBlockAPlayableAuthorizedFallback() {
        val attempts = mutableListOf<PlayerLoadAttempt>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                if (request.headers["Authorization"] == null)
                    """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video requires payment to watch"}}"""
                else """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"url":"https://media.example.test/movie.mp4"}]}}""")
        }
        val result = loadPlayer("video", RequestContext(webSession = WebSession("SAPISID=fixture")), http,
            queues = PlayerClientQueues(PlayerClient.VISIONOS), onAttempt = attempts::add)
        assertTrue(result.ok)
        assertEquals(PlayerClient.WEB_SAFARI, result.value?.client)
        assertEquals(PlayerAccessRestriction.PAYMENT_REQUIRED, attempts.first().restriction)
        assertFalse(attempts.first().authenticated)
        assertTrue(attempts.last().authenticated)
        assertNull(attempts.last().restriction)
        assertNull(attempts.last().error)
    }
}
