package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerManifestChallengeTest {
    private val script = PlayerScript("https://www.youtube.com/s/player/test/base.js", "source")

    @Test fun manifestAndFormatsShareOneSolveAndPreserveSignedFields() {
        val url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/n/raw/expire/123/sig/kept?pot=kept#fragment"
        val answer = playerAnswer("""{"streamingData":{"hlsManifestUrl":"$url","adaptiveFormats":[{"url":"https://media.test/v?n=raw"}]}}""")
        var calls = 0
        val opened = openBoundFormats(answer, script, object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                calls++
                assertTrue(request.signatures.isEmpty())
                assertEquals(setOf("raw"), request.throttles)
                return PlayerChallenge(emptyMap(), mapOf("raw" to "solved"))
            }
        })
        assertEquals(1, calls)
        assertEquals(url.replace("/n/raw/", "/n/solved/"), opened.hlsUrl)
        assertEquals("https://media.test/v?n=solved", opened.adaptiveUrls.single())
        assertTrue(opened.hasHls)
    }

    @Test fun manifestOnlyAnswersAreSolvedBeforeTheyCanWinTheClientQueue() {
        val requests = mutableListOf<PlayerChallengeRequest>()
        val loaded = loadPlayer("video", RequestContext(), object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"streamingData":{"hlsManifestUrl":"https://manifest.test/n/raw/index.m3u8"}}""")
        }, script, object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                requests += request
                return PlayerChallenge(emptyMap(), mapOf("raw" to "opened"))
            }
        })
        assertTrue(loaded.ok)
        assertEquals(1, requests.size)
        assertEquals("https://manifest.test/n/opened/index.m3u8", loaded.value!!.answer!!.hlsUrl)
    }

    @Test fun anUnsolvedManifestFallsBackWithoutDiscardingAnIndependentDirectSource() {
        val answer = playerAnswer("""{"streamingData":{"hlsManifestUrl":"https://manifest.test/n/raw/index.m3u8","formats":[{"url":"https://media.test/v"}]}}""")
        val opened = openBoundFormats(answer, script, object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest) = PlayerChallenge(emptyMap(), emptyMap())
        })
        assertNull(opened.hlsUrl)
        assertFalse(opened.hasHls)
        assertEquals("https://media.test/v", selectPlayback(opened).videoUrl)
        val requests = mutableListOf<HttpRequest>()
        val loaded = loadPlayer("video", RequestContext(), object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, if (requests.size == 1)
                    """{"streamingData":{"hlsManifestUrl":"https://manifest.test/n/raw/index.m3u8"}}"""
                else """{"streamingData":{"formats":[{"url":"https://media.test/v"}]}}""")
            }
        })
        assertTrue(loaded.ok)
        assertEquals(2, requests.size)
        assertEquals(PlayerClient.WEB, loaded.value!!.client)
    }

    @Test fun aFailedBatchIsNotRetriedWithinTheSameAnswer() {
        val answer = playerAnswer("""{"streamingData":{"hlsManifestUrl":"https://manifest.test/n/manifest/index.m3u8","adaptiveFormats":[{"url":"https://media.test/v?n=format"}]}}""")
        var calls = 0
        val opened = openBoundFormats(answer, script, object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                calls++
                assertEquals(setOf("format", "manifest"), request.throttles)
                throw IllegalStateException("solver_unavailable")
            }
        })
        assertEquals(1, calls)
        assertNull(opened.hlsUrl)
        assertTrue(opened.adaptiveFormats.isEmpty())
    }

    @Test fun challengeValuesAreDecodedAndEscapedWithoutRewritingTheRestOfTheUrl() {
        val answer = playerAnswer("""{"streamingData":{"hlsManifestUrl":"https://manifest.test/n/raw%2Bvalue/other/n/untouched?x=%2F"}}""")
        val opened = openBoundFormats(answer, script, object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                assertEquals(setOf("raw+value"), request.throttles)
                return PlayerChallenge(emptyMap(), mapOf("raw+value" to "a/b+c"))
            }
        })
        assertEquals("https://manifest.test/n/a%2Fb%2Bc/other/n/untouched?x=%2F", opened.hlsUrl)
    }

    @Test fun liveManifestsAndUrlsWithoutAPathChallengeRemainUnchanged() {
        val urls = listOf("https://manifest.test/index.m3u8?next=/n/raw/", "https://manifest.test/index.m3u8#n/raw/")
        for (url in urls) {
            val answer = playerAnswer("""{"streamingData":{"hlsManifestUrl":"$url"}}""")
            assertEquals(url, openBoundFormats(answer, null, null).hlsUrl)
        }
        val live = playerAnswer("""{"videoDetails":{"isLive":true},"streamingData":{"hlsManifestUrl":"https://manifest.test/n/raw/live.m3u8"}}""")
        assertTrue(live.live)
        assertEquals(live.hlsUrl, openBoundFormats(live, null, null).hlsUrl)
    }
}
