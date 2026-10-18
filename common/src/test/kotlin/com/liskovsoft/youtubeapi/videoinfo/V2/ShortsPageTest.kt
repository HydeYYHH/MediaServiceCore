package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ShortsPageTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun readsTheRecordedStartAndSequencePages() {
        for (name in listOf("shorts-start.json", "shorts-next.json", "shorts-next2.json")) {
            val page = shortsPage(fixture(name))
            assertTrue(name, page.items.isNotEmpty())
            assertTrue(page.items.all { it.kind == "short" && it.thumbnail != null })
            assertEquals("fixture-sequence", page.nextKey)
        }
    }

    @Test fun loadsTheShortsProtocolAndDistinguishesAnUnknownShape() {
        var body = fixture("shorts-next.json")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("sequence", jsonObject(request.body)!!.text("sequenceParams"))
                return HttpReply(200, body)
            }
        }
        assertTrue(loadHome(HomeSection.SHORTS, continuation = "sequence", http = http).value!!.items.isNotEmpty())
        body = "{\"entries\":[]}"
        assertTrue(loadHome(HomeSection.SHORTS, continuation = "sequence", http = http).ok)
        body = "{}"
        assertEquals(RequestFailure.PARSE, loadHome(HomeSection.SHORTS, continuation = "sequence", http = http).error!!.kind)
    }
}
