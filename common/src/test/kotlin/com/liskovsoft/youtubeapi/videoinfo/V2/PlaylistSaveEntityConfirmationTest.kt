package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Save/forget use the original playlist target, independently of generic command or toast responses. */
class PlaylistSaveEntityConfirmationTest {
    private val playlist = "PLfixtureMain"
    private val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
    private fun key(target: String, kind: Int = 62): String = URLEncoder.encode(Base64.getEncoder().encodeToString(
        byteArrayOf(18, target.length.toByte()) + target.toByteArray(Charsets.UTF_8) + byteArrayOf(32, kind.toByte(), 40, 1)), "UTF-8")
    private fun mutation(state: String, target: String = playlist, kind: Int = 62,
        type: String = "ENTITY_MUTATION_TYPE_REPLACE", payloadKey: String = key(target, kind)) = """{
        "entityKey":"${key(target, kind)}","type":"$type","payload":{"likeStatusEntity":{
        "key":"$payloadKey","likeStatus":"$state"}}}"""
    private fun response(vararg mutations: String, extra: String = "") = """{"responseContext":{},
        "frameworkUpdates":{"entityBatchUpdate":{"mutations":[${mutations.joinToString(",")}]}}$extra}"""
    private class Wire(private val body: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return HttpReply(200, body) }
    }

    @Test fun saveAndForgetConfirmOnlyTheirMatchingPlaylistReplacementAndPreserveTheActualRequestTarget() {
        for ((saved, state) in listOf(true to "LIKE", false to "INDIFFERENT")) {
            val wire = Wire(response(mutation(state)))
            val result = savePlaylist(playlist, saved, context, wire)
            assertTrue(result.ok)
            val value = result.value!!
            val reply = value.action
            assertTrue(reply.confirmed); assertEquals(200, reply.httpStatus)
            assertEquals(state, reply.metadata["target_entity_state"])
            assertEquals("replace", reply.metadata["target_entity_mutation"])
            assertEquals("like_status", reply.metadata["target_entity_kind"])
            assertNull(value.playlistId)
            val request = wire.requests.single()
            assertEquals("/youtubei/v1/like/" + if (saved) "like" else "removelike", URI(request.url).path)
            assertEquals(playlist, jsonObject(request.body)!!.obj("target")!!.text("playlistId"))
            assertNull(jsonObject(request.body)!!.obj("target")!!.text("videoId"))
            assertFalse(reply.metadata.toString().contains(playlist))
            assertFalse(reply.metadata.toString().contains(key(playlist)))
        }
    }

    @Test fun foreignTargetsNamespacesMalformedPayloadsAndUnknownOrOppositeStatesNeverConfirm() {
        for (body in listOf(
            response(mutation("LIKE", "PLotherFixture")),
            response(mutation("LIKE", "VL$playlist")),
            response(mutation("LIKE", kind = 51)),
            response(mutation("LIKE", payloadKey = key("PLotherFixture"))),
            response(mutation("UNKNOWN")), response(mutation("INDIFFERENT")), response(mutation("DISLIKE")),
            response(mutation("LIKE", type = "ENTITY_MUTATION_TYPE_DELETE")),
            response(mutation("LIKE", type = "ENTITY_MUTATION_TYPE_UPDATE")),
            """{"frameworkUpdates":{"likeStatusEntity":{"likeStatus":"LIKE"}}}""",
            """{"responseContext":{},"actions":[{"addToToastAction":{"item":{"notificationTextRenderer":{"success":true}}}}]}"""
        )) {
            val wire = Wire(body)
            val result = savePlaylist(playlist, true, context, wire)
            assertTrue(result.ok); assertFalse(result.value!!.action.confirmed); assertEquals(1, wire.requests.size)
        }
        assertFalse(savePlaylist(playlist, false, context, Wire(response(mutation("LIKE")))).value!!.action.confirmed)
    }

    @Test fun theLatestTargetMutationInvalidatesOlderStateAndNeverNormalizesAPlaylistNamespace() {
        val liked = mutation("LIKE")
        val neutral = mutation("INDIFFERENT")
        val deleted = """{"entityKey":"${key(playlist)}","type":"ENTITY_MUTATION_TYPE_DELETE"}"""
        assertTrue(savePlaylist(playlist, true, context, Wire(response(neutral, liked))).value!!.action.confirmed)
        for (last in listOf(neutral, deleted)) {
            val reply = savePlaylist(playlist, true, context, Wire(response(liked, last))).value!!.action
            assertFalse(reply.confirmed)
        }
        val prefixed = "VL$playlist"
        assertTrue(savePlaylist(prefixed, true, context, Wire(response(mutation("LIKE", prefixed)))).value!!.action.confirmed)
        assertFalse(savePlaylist(prefixed, true, context, Wire(response(liked))).value!!.action.confirmed)
    }

    @Test fun actionFailuresAndPendingStatusesTakePriorityOverAMatchingSavedState() {
        for (extra in listOf(
            ",\"status\":\"PENDING\"", ",\"actionResult\":{\"status\":\"PENDING\"}",
            ",\"actionResults\":[{\"status\":\"STATUS_SUCCEEDED\"},42]"
        )) assertFalse(savePlaylist(playlist, true, context, Wire(response(mutation("LIKE"), extra = extra))).value!!.action.confirmed)
        for (extra in listOf(
            ",\"error\":{\"code\":400}", ",\"status\":\"STATUS_FAILED\"",
            ",\"actionResult\":{\"success\":false}"
        )) {
            val result = savePlaylist(playlist, true, context, Wire(response(mutation("LIKE"), extra = extra)))
            assertFalse(result.ok); assertEquals(RequestFailure.HTTP, result.error!!.kind)
        }
    }

    @Test fun createDeleteAndEditRetainTheirExistingConfirmationRulesAndAnonymousSaveDoesNoIo() {
        val unrelatedState = response(mutation("LIKE"))
        assertFalse(deletePlaylist(playlist, context, Wire(unrelatedState)).value!!.action.confirmed)
        assertFalse(createPlaylist("Fixture", null, context, http = Wire(unrelatedState)).value!!.action.confirmed)
        assertFalse(editPlaylist(playlist, listOf(PlaylistChange.Add("jNQXAC9IVRw")), context, Wire(unrelatedState)).value!!.action.confirmed)
        assertTrue(createPlaylist("Fixture", null, context, http = Wire("""{"playlistId":"PLcreated"}""")).value!!.action.confirmed)
        assertTrue(editPlaylist(playlist, listOf(PlaylistChange.Add("jNQXAC9IVRw")), context, Wire("""{
            "playlistEditVideoAddedResultData":{"playlistId":"$playlist","setVideoId":"fixture-set"}}""")).value!!.action.confirmed)
        val wire = Wire(unrelatedState)
        val result = savePlaylist(playlist, true, RequestContext(), wire)
        assertEquals(RequestFailure.AUTH, result.error!!.kind); assertTrue(wire.requests.isEmpty())
    }
}
