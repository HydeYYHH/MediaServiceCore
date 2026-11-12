package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class LegacyWritePrivacyTest {
    @Test fun callbackPlaylistCreationExplicitlyRequestsPrivateForEmptyAndSeededLists() {
        for (video in listOf(null, "jNQXAC9IVRw")) {
            var calls = 0
            val result = createPlaylist("Temporary private list", video, "fixture-visitor") { query ->
                calls++
                assertEquals("https://www.youtube.com/youtubei/v1/playlist/create", query.url.substringBefore('?'))
                val body = jsonObject(query.body)!!
                assertEquals("PRIVATE", body.text("privacyStatus"))
                assertEquals("Temporary private list", body.text("title"))
                assertEquals("fixture-visitor", query.headers["X-Goog-Visitor-Id"])
                assertEquals(if (video == null) emptyList() else listOf(video), body.arr("videoIds").map { (it as Json.Str).value })
                200 to """{"status":"STATUS_SUCCEEDED","playlistId":"PL-owned-fixture"}"""
            }
            assertEquals(1, calls)
            assertTrue(result.ok)
            assertEquals("PL-owned-fixture", result.playlistId)
        }
    }
}
