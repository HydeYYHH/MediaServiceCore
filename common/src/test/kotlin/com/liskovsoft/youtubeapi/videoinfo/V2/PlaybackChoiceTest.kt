package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaybackChoiceTest {
    @Test fun continuesUntilAClientMeetsThePreference() {
        val clients = ArrayList<String>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val client = jsonObject(request.body)!!.path("context", "client")!!.text("clientName")!!
                clients.add(client)
                val mime = if (client == "VISIONOS") "video/webm" else "video/mp4"
                return HttpReply(200, "{\"streamingData\":{\"formats\":[{\"url\":\"https://media/$client\",\"mimeType\":\"$mime\"}]}}")
            }
        }
        val result = PlaybackSession(http).load("v", preference = PlaybackPreference(mimeTypes = setOf("video/mp4")))
        assertTrue(result.ok)
        assertEquals(listOf("VISIONOS", "WEB"), clients)
        assertEquals(PlayerClient.WEB, result.value!!.client)
    }

    @Test fun rejectsIncompleteSourcesAndKeepsAudioMode() {
        val incomplete = playerAnswer("{\"streamingData\":{\"serverAbrStreamingUrl\":\"https://sabr\"}}")
        assertFalse(selectPlayback(incomplete).hasSource())
        val video = playerAnswer("{\"streamingData\":{\"adaptiveFormats\":[{\"url\":\"https://video\",\"mimeType\":\"video/mp4\"}]}}")
        assertFalse(selectPlayback(video).hasSource())
        val muxed = playerAnswer("{\"streamingData\":{\"formats\":[{\"url\":\"https://muxed\"}]}}")
        val audio = selectPlayback(muxed, PlaybackPreference(audioOnly = true))
        assertNull(audio.videoUrl)
        assertEquals("https://muxed", audio.audioUrl)
    }
}
