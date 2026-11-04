package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerReadinessTest {
    private fun wait(body: String) = playerAnswer(body).mediaWaitMs
    private fun slot(content: String, event: String = "SLOT_TRIGGER_EVENT_BEFORE_CONTENT") =
        """{"adSlotRenderer":{"adSlotMetadata":{"triggerEvent":"$event"},"fulfillmentContent":$content}}"""
    private fun ad(value: String) = """{"playerBytesAdLayoutRenderer":{"renderingContent":{"instreamVideoAdRenderer":$value}}}"""

    @Test fun sequentialPreContentAdsCountOnceWithoutLegacyDuplicates() {
        val sequential = """{"playerBytesSequentialLayoutRenderer":{"sequentialLayouts":[${ad("""{"skipOffsetMilliseconds":5000}""")},${ad("""{"playerVars":"length_seconds=3.5"}""")}]}}"""
        val body = """{"adSlots":[${slot(sequential)}],"adPlacements":[{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_START"}},"renderer":${ad("""{"skipOffsetMilliseconds":5000}""")}}}]}"""
        assertEquals(8500L, wait(body))
    }

    @Test fun midrollAndPostrollNeverDelayInitialMedia() {
        for (event in listOf("SLOT_TRIGGER_EVENT_PLAYBACK_TIME", "SLOT_TRIGGER_EVENT_AFTER_CONTENT")) {
            assertEquals(0L, wait("""{"adSlots":[${slot(ad("""{"skipOffsetMilliseconds":5000}"""), event)}]}"""))
        }
        assertEquals(0L, wait("{}"))
    }

    @Test fun legacyPlacementAndZeroSkipOffsetAreSupported() {
        assertEquals(4000L, wait("""{"adPlacements":[{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_START"}},"renderer":${ad("""{"playerVars":"x=1&length_seconds=4"}""")}}}]}"""))
        assertEquals(0L, wait("""{"adSlots":[${slot(ad("""{"skipOffsetMilliseconds":0,"playerVars":"length_seconds=20"}"""))}]}"""))
    }

    @Test fun unknownAndMalformedDurationsHaveABoundedWindow() {
        assertEquals(15_000L, wait("""{"adSlots":[${slot("{}")}]}"""))
        assertEquals(15_000L, wait("""{"adSlots":[${slot(ad("""{"skipOffsetMilliseconds":-1,"playerVars":"length_seconds=NaN"}"""))}]}"""))
        assertEquals(30_000L, wait("""{"adSlots":[${slot(ad("""{"playerVars":"length_seconds=1e100"}"""))}]}"""))
    }

    @Test fun readinessIsAnchoredToThePlayerResponseBeforeChallengeWork() {
        var now = 1000L
        val loaded = loadPlayer("video", RequestContext(), object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"streamingData":{"hlsManifestUrl":"https://manifest.test/n/raw/index.m3u8"},"adSlots":[${slot(ad("""{"skipOffsetMilliseconds":5000}"""))}]}""")
        }, PlayerScript("url", "source"), object : PlayerSolver {
            override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
                now += 2000
                return PlayerChallenge(emptyMap(), mapOf("raw" to "solved"))
            }
        }, now = { now })
        assertTrue(loaded.ok)
        assertEquals(6000L, loaded.value!!.answer!!.mediaAvailableAtMs)
    }
}
