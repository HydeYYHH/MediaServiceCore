package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlaylistActionTest {
    @Test fun removesOneDuplicateByItsSetVideoIdAndKeepsEditResults() {
        val body = """{"contents":[{"playlistVideoRenderer":{"videoId":"v","setVideoId":"first"}},{"playlistVideoRenderer":{"videoId":"v","setVideoId":"second"}}]}"""
        assertEquals(listOf("first","second"), playlistPage(body).videos.map { it.setVideoId })
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            val action = jsonObject(request.body)!!.arr("actions").single() as Json.Obj
            assertEquals("second", action.text("setVideoId")); assertNull(action.text("removedVideoId"))
            return HttpReply(200, """{"playlistId":"PL1","playlistEditResults":[{"playlistEditVideoAddedResultData":{"setVideoId":"new"}}]}""")
        }}
        val reply = editPlaylist("PL1", listOf(PlaylistChange.Remove("second")), RequestContext("a","t"), http).value!!
        assertEquals(listOf("new"), reply.addedSetVideoIds); assertEquals("PL1",reply.playlistId)
    }
    @Test fun rejectsSuccessfulHttpWithAFailedAction() {
        assertFalse(addVideo("PL","v",null) { 200 to "{\"status\":\"STATUS_FAILED\"}" }.ok)
    }
    @Test fun carriesTheDescriptionAndOmitsItWhenAbsent() {
        var body = ""
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            body = request.body
            return HttpReply(200, """{"playlistId":"PLnew"}""")
        }}
        createPlaylist("My list", null, RequestContext("a","t"), description = "about it", http = http)
        assertEquals("about it", jsonObject(body)!!.text("descriptionText"))
        assertNull(jsonObject(body)!!.text("videoIds"))
        createPlaylist("My list", "v", RequestContext("a","t"), http = http)
        val without = jsonObject(body)!!
        assertNull(without.text("descriptionText"))
        assertEquals("v", (without.arr("videoIds").single() as Json.Str).value)
    }
}
