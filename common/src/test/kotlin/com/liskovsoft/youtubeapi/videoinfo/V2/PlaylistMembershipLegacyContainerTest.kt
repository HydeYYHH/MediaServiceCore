package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Legacy playlist membership is read from its direct response container. */
class PlaylistMembershipLegacyContainerTest {
    private val target = "PLowned-target"
    private val wanted = "jNQXAC9IVRw"
    private val seed = "aqz-KE-bpKQ"
    private fun signed() = AccountSession(RequestContext(accountId = "fixture-owner", webSession = WebSession("SAPISID=fixture")))
    private fun choices() = """{"contents":[{"addToPlaylistRenderer":{"playlists":[
        {"playlistAddToOptionRenderer":{"playlistId":"$target","title":{"simpleText":"Owned"},"containsSelectedVideos":"NONE"}}]}}]}"""
    private fun video(id: String) = """{"playlistVideoRenderer":{"videoId":"$id","title":{"simpleText":"Fixture"}}}"""
    private fun next(token: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""
    private fun list(cards: String, ids: String = """"playlistId":"$target","targetId":"$target",""", extra: String = "") =
        """{"playlistVideoListRenderer":{$ids"contents":[$cards]$extra}}"""
    private fun page(inner: String, innerTarget: String = "", outerTarget: String = "VL$target", outerSibling: String = next("outside-list")) = """{
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{
        "sectionListRenderer":{"targetId":"$outerTarget","contents":[{"itemSectionRenderer":{
        $innerTarget"contents":[$inner]}}${if (outerSibling.isEmpty()) "" else ",$outerSibling"}]}}}}]}}}"""
    private fun continued(cards: String, targetId: String = target) = """{"onResponseReceivedActions":[{
        "appendContinuationItemsAction":{"targetId":"$targetId","continuationItems":[$cards]}}]}"""
    private class Wire(private val body: (HttpRequest) -> String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, body(request))
        }
        fun edits() = requests.count { it.url.contains("edit_playlist") }
    }
    private fun read(response: String, desired: Boolean = true): Pair<PlaylistMembershipUpdate, Wire> {
        val wire = Wire { request ->
            assertFalse("Unknown or already matching collections must never write", request.url.contains("edit_playlist"))
            if (request.url.contains("get_add_to_playlist")) choices() else response
        }
        val directory = PlaylistMembershipSession(signed(), wire).open(wanted).value!!
        return directory.setContains(directory.entries.single(), desired).value!! to wire
    }

    @Test fun matchingLegacyListDoesNotRequireAnItemSectionTargetOrFollowItsOuterSiblingCursor() {
        val (present, presentWire) = read(page(list(video(wanted))))
        assertEquals(PlaylistPresence.PRESENT, present.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, present.outcome)
        assertFalse(present.submitted); assertNull(present.readError)
        assertEquals(3, presentWire.requests.size)

        val (absent, absentWire) = read(page(list(video(seed))), desired = false)
        assertEquals(PlaylistPresence.ABSENT, absent.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, absent.outcome)
        assertFalse(absent.submitted); assertNull(absent.readError)
        assertEquals(3, absentWire.requests.size)
        assertTrue(absentWire.requests.none { jsonObject(it.body)?.text("continuation") != null })
    }

    @Test fun onlyTheLegacyListsOwnContinuationIsFollowedBeforeProvingAbsence() {
        val opening = page(list(video(seed), extra = """, "continuations":[{"nextContinuationData":{"continuation":"own-list-page"}}]"""))
        var pages = 0
        val wire = Wire { request ->
            assertFalse(request.url.contains("edit_playlist"))
            when {
                request.url.contains("get_add_to_playlist") -> choices()
                jsonObject(request.body)!!.text("continuation") == null -> opening
                else -> {
                    pages++
                    assertEquals("own-list-page", jsonObject(request.body)!!.text("continuation"))
                    continued("")
                }
            }
        }
        val directory = PlaylistMembershipSession(signed(), wire).open(wanted).value!!
        val result = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(1, pages); assertEquals(PlaylistPresence.ABSENT, result.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, result.outcome); assertFalse(result.submitted)
    }

    @Test fun addAndRemoveRequireAcknowledgementAndFreshReadsOfTheMatchingInnerList() {
        var present = false
        val wire = Wire { request -> when {
            request.url.contains("get_add_to_playlist") -> choices()
            request.url.contains("edit_playlist") -> {
                val action = (jsonObject(request.body)!!.arr("actions").single() as Json.Obj).text("action")
                assertEquals(if (present) "ACTION_REMOVE_VIDEO_BY_VIDEO_ID" else "ACTION_ADD_VIDEO", action)
                present = !present
                """{"playlistId":"$target","status":"SUCCESS"}"""
            }
            else -> page(list(listOfNotNull(video(seed), if (present) video(wanted) else null).joinToString(",")))
        } }
        val directory = PlaylistMembershipSession(signed(), wire).open(wanted).value!!
        for (desired in listOf(true, false)) {
            val update = directory.setContains(directory.entries.single(), desired).value!!
            assertEquals(PlaylistMembershipOutcome.VERIFIED, update.outcome)
            assertTrue(update.submitted); assertTrue(update.serverConfirmed)
            assertEquals(if (desired) PlaylistPresence.PRESENT else PlaylistPresence.ABSENT, update.after)
            assertNull(update.readError); assertNull(update.writeError)
        }
        assertEquals(2, wire.edits())
        assertTrue(wire.requests.none { jsonObject(it.body)?.text("continuation") != null })
    }

    @Test fun unknownParallelAndWrongTargetContainersCannotAuthorizeWritesEvenWithTheWantedVideo() {
        val goodList = list(video(wanted))
        val parallelCard = video(wanted).dropLast(1) + "," + next("hidden").drop(1)
        val malformed = listOf(
            page(goodList, outerTarget = "VLPLother"),
            page(goodList, innerTarget = """"targetId":"PLother","""),
            page(goodList, innerTarget = """"targetId":null,"""),
            page(list(video(wanted), ids = """"playlistId":"PLother","targetId":"$target",""")),
            page(list(video(wanted), ids = """"playlistId":"$target","targetId":"PLother",""")),
            page(list(video(wanted), ids = """"playlistId":"$target",""")),
            page(list(video(wanted), ids = """"targetId":"$target",""")),
            page(goodList + ", {\"futureRenderer\":{}}"),
            page(goodList.dropLast(1) + ",\"futureRenderer\":{}}"),
            page(goodList, outerSibling = """{"futureRenderer":{}}"""),
            page(goodList, outerSibling = next("outside").dropLast(1) + ",\"itemSectionRenderer\":{\"contents\":[]}}"),
            page(goodList, outerSibling = next("outside") + "," + next("duplicate")),
            page(list(video(wanted) + ", {\"futureRenderer\":{}}")),
            page(list(parallelCard)),
            page(list("""{"playlistVideoRenderer":{}}""")),
            page(list(video(wanted), extra = """, "continuations":{}""")),
            page(list(video(wanted), extra = """, "continuations":[{"nextContinuationData":{}}]""")),
            page(goodList).replace("\"sectionListRenderer\":", "\"futureRenderer\":{},\"sectionListRenderer\":")
        )
        for ((index, response) in malformed.withIndex()) {
            val (result, wire) = read(response)
            assertEquals("case $index", PlaylistPresence.UNKNOWN, result.before)
            assertEquals("case $index", PlaylistMembershipOutcome.UNKNOWN, result.outcome)
            assertFalse(result.submitted); assertNotNull(result.readError); assertEquals(0, wire.edits())
        }
    }

    @Test fun aLegacyContinuationWithAnotherTargetOrUnknownItemsDoesNotEstablishMembership() {
        val opening = page(list(video(seed), extra = """, "continuations":[{"nextContinuationData":{"continuation":"own-list-page"}}]"""))
        for (last in listOf(continued(video(wanted), "PLother"), continued(video(wanted) + ", {\"futureRenderer\":{}}"))) {
            val wire = Wire { request -> when {
                request.url.contains("get_add_to_playlist") -> choices()
                jsonObject(request.body)!!.text("continuation") == null -> opening
                else -> last
            } }
            val directory = PlaylistMembershipSession(signed(), wire).open(wanted).value!!
            val result = directory.setContains(directory.entries.single(), true).value!!
            assertEquals(PlaylistPresence.UNKNOWN, result.before); assertFalse(result.submitted)
            assertNotNull(result.readError); assertEquals(0, wire.edits())
        }
    }

    @Test fun observedEmptyUnknownWrapperAndUntargetedModernLockupsRemainUnknown() {
        val modern = """{"lockupViewModel":{"contentId":"$wanted","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"$wanted","playlistId":"$target"}}}}}}}"""
        for (response in listOf(page("""{"futureEmptyRenderer":{}}""", outerTarget = ""), page(modern))) {
            val (result, wire) = read(response)
            assertEquals(PlaylistPresence.UNKNOWN, result.before); assertFalse(result.submitted)
            assertNotNull(result.readError); assertEquals(0, wire.edits())
        }
    }
}
