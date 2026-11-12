package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaylistMembershipModernTest {
    private val target = "PLmodern-target"
    private val video = "jNQXAC9IVRw"
    private fun signed() = AccountSession(RequestContext(accountId = "fixture-owner", webSession = WebSession("SAPISID=fixture")))
    private fun choices(id: String) = """{"contents":[{"addToPlaylistRenderer":{"playlists":[
        {"playlistAddToOptionRenderer":{"playlistId":"$id","title":{"simpleText":"Owned"},"containsSelectedVideos":"NONE"}}]}}]}"""
    private fun lockup(id: String, playlist: String = target, contentId: String = id) = """{"lockupViewModel":{
        "contentId":"$contentId","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","rendererContext":{"commandContext":{
        "onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"$id","playlistId":"$playlist"}}}}}}}"""
    private fun next(token: String, request: String = "CONTINUATION_REQUEST_TYPE_BROWSE") = """{"continuationItemViewModel":{
        "trigger":"CONTINUATION_TRIGGER_ON_ITEM_SHOWN","continuationCommand":{"innertubeCommand":{
        "continuationCommand":{"token":"$token","request":"$request"}}}}}"""
    private fun page(cards: String, outer: String = "VL$target", inner: String = target, siblings: String = "") = """{
        "header":{"playlistHeaderRenderer":{"title":{"simpleText":"Owned fixture"}}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{
        "sectionListRenderer":{"targetId":"$outer","contents":[{"itemSectionRenderer":{"targetId":"$inner",
        "contents":[$cards]}}]$siblings}}}}]}}}"""
    private fun continued(cards: String, id: String = target) = """{"onResponseReceivedActions":[{
        "appendContinuationItemsAction":{"targetId":"$id","continuationItems":[$cards]}}]}"""
    private class Wire(private val body: (HttpRequest) -> String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            assertFalse("An unproved collection must never issue an edit", request.url.contains("edit_playlist"))
            return HttpReply(200, body(request))
        }
    }
    private fun fixture(): String = javaClass.getResourceAsStream("/fixtures/playlist-detail.json")!!.bufferedReader().use { it.readText() }
    private fun section(body: String) = jsonObject(body)!!.path("contents", "twoColumnBrowseResultsRenderer")!!
        .arr("tabs").mapNotNull { (it as? Json.Obj)?.path("tabRenderer", "content", "sectionListRenderer") }.single()

    @Test fun observedThirteenLockupCollectionProvesPresenceDespiteStaleDirectoryFlag() {
        val body = fixture()
        val outer = section(body)
        val id = outer.text("targetId")!!.removePrefix("VL")
        val inner = outer.arr("contents").mapNotNull { (it as? Json.Obj)?.obj("itemSectionRenderer") }.single()
        val cards = inner.arr("contents").mapNotNull { (it as? Json.Obj)?.obj("lockupViewModel") }
        assertEquals(13, cards.size); assertEquals(id, inner.text("targetId"))
        val wanted = cards.first().path("rendererContext", "commandContext", "onTap", "innertubeCommand", "watchEndpoint")!!.text("videoId")!!
        val http = Wire { request -> if (request.url.contains("get_add_to_playlist")) choices(id) else body }
        val directory = PlaylistMembershipSession(signed(), http).open(wanted).value!!
        val result = directory.setContains(directory.entries.single(), true).value!!
        assertEquals(PlaylistPresence.PRESENT, result.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, result.outcome)
        assertFalse(result.submitted); assertNull(result.readError)
        assertEquals(3, http.requests.size)
    }

    @Test fun observedViewModelTokenIsFollowedAndOnlyItsCompleteTerminalPageProvesAbsence() {
        val body = fixture()
        val outer = section(body)
        val id = outer.text("targetId")!!.removePrefix("VL")
        val token = outer.arr("contents").mapNotNull { (it as? Json.Obj)?.obj("continuationItemViewModel") }.single()
            .path("continuationCommand", "innertubeCommand", "continuationCommand")!!.text("token")!!
        val wanted = "absent-membership-video"
        var continuations = 0
        val http = Wire { request ->
            val query = jsonObject(request.body)!!
            when {
                request.url.contains("get_add_to_playlist") -> choices(id)
                query.text("continuation") == null -> body
                else -> { continuations++; assertEquals(token, query.text("continuation")); continued("", id) }
            }
        }
        val directory = PlaylistMembershipSession(signed(), http).open(wanted).value!!
        val result = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(PlaylistPresence.ABSENT, result.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, result.outcome)
        assertFalse(result.submitted); assertEquals(1, continuations)
    }

    @Test fun mismatchedUnknownAndParallelRendererItemsRemainUnknownEvenBesideTheWantedVideo() {
        val continuation = next("next-page")
        val parallel = lockup(video).dropLast(1) + "," + continuation.drop(1)
        val malformed = listOf(
            page(lockup(video), outer = "VLPLother"), page(lockup(video), inner = "PLother"),
            page(lockup(video, playlist = "PLother")), page(lockup(video, contentId = "different-video")),
            page("""{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"$video"}}"""),
            page(lockup(video) + ",{\"unknownVideoRenderer\":{\"videoId\":\"other\"}}"),
            page(parallel), page(lockup(video) + "," + next("next-page", "CONTINUATION_REQUEST_TYPE_SEARCH")),
            page(lockup(video) + ",{\"continuationItemViewModel\":{}}"),
            """{"contents":{"playlistVideoListRenderer":{"contents":[{"playlistVideoRenderer":{"videoId":"$video"},
                "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"hidden-page"}}}}]}}}"""
        )
        for ((index, response) in malformed.withIndex()) {
            val http = Wire { request -> if (request.url.contains("get_add_to_playlist")) choices(target) else response }
            val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
            val result = directory.setContains(directory.entries.single(), true).value!!
            assertEquals("case $index", PlaylistPresence.UNKNOWN, result.before)
            assertEquals(PlaylistMembershipOutcome.UNKNOWN, result.outcome)
            assertFalse(result.submitted); assertNotNull(result.readError)
        }
    }

    @Test fun aContinuationFromAnotherTargetOrWithUnknownItemsCannotEstablishPresenceOrAbsence() {
        val first = page(lockup("other-video"))
        // The observed continuation lives beside the item section, not in the video list.
        val initial = jsonObject(first)!!
        val outer = section(first)
        val updated = Json.Obj(outer.fields + ("contents" to Json.Arr(outer.arr("contents") + jsonObject(next("actual-next"))!!)))
        val tab = Json.Obj(mapOf("tabRenderer" to Json.Obj(mapOf("content" to Json.Obj(mapOf("sectionListRenderer" to updated))))))
        val opening = Json.Obj(initial.fields + ("contents" to Json.Obj(mapOf("twoColumnBrowseResultsRenderer" to Json.Obj(mapOf("tabs" to Json.Arr(listOf(tab)))))))).encode()
        for (last in listOf(continued(lockup(video), "PLother"), continued(lockup(video, playlist = "PLother")),
            continued(lockup(video) + ",{\"unrecognizedRenderer\":{}}"), """{"futureContinuation":{}}""")) {
            val http = Wire { request -> when {
                request.url.contains("get_add_to_playlist") -> choices(target)
                jsonObject(request.body)!!.text("continuation") == null -> opening
                else -> last
            } }
            val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
            val result = directory.setContains(directory.entries.single(), true).value!!
            assertEquals(PlaylistPresence.UNKNOWN, result.before); assertFalse(result.submitted)
            assertNotNull(result.readError)
        }
    }
}
