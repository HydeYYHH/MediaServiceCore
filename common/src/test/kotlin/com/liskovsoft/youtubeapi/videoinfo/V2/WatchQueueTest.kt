package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WatchQueueTest {
    @Test fun preservesDuplicateEntriesAndNavigationByIndex() {
        fun video(id: String, index: Int, selected: Boolean) = """{"playlistPanelVideoRenderer":{"videoId":"$id","selected":$selected,"navigationEndpoint":{"watchEndpoint":{"videoId":"$id","playlistId":"PL","index":$index,"startTimeSeconds":37}}}}"""
        val body = "{\"contents\":{\"twoColumnWatchNextResults\":{\"playlist\":{\"playlist\":{\"playlistId\":\"PL\",\"currentIndex\":1,\"contents\":[${video("same", 0, false)},${video("same", 1, true)},${video("next", 2, false)}]}}}}}"
        val page = watchPage(body)
        val queue = page.queue!!
        assertEquals(listOf("same", "same", "next"), queue.videos.map { it.videoId })
        assertEquals(1, queue.index)
        assertEquals(0, jsonObject(queue.previous!!.payload)!!.int("index"))
        assertEquals("next", jsonObject(queue.next!!.payload)!!.text("videoId"))
        assertEquals(37L, page.resumeSeconds)
        assertNull(watchPage("{}").queue)
    }

    @Test fun requestsTheSelectedQueueEntry() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val body = jsonObject(request.body)!!
                assertEquals("PL", body.text("playlistId"))
                assertEquals(2, body.int("index"))
                assertEquals("a\"b", body.text("params"))
                return HttpReply(200, "{}")
            }
        }
        assertTrue(loadWatch("v", http = http, playlistId = "PL", index = 2, params = "a\"b").ok)
        assertEquals(RequestFailure.INVALID, loadWatch("v", http = http, index = -1).error!!.kind)
    }
}
