package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Target comparisons are based on observed WEB response paths, never toast text or a later read. */
class PlaylistSaveGuideAcknowledgementTest {
    private val playlist = "PLguideFixture"
    private val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
    private val popup = """{"clickTrackingParams":"fixture","openPopupAction":{"popup":{}}}"""
    private fun add(browseId: String = "VL$playlist") = """{"clickTrackingParams":"fixture",
        "addToGuideSectionAction":{"items":[{"guideEntryRenderer":{
        "formattedTitle":{"simpleText":"Fixture"},"navigationEndpoint":{"clickTrackingParams":"fixture",
        "commandMetadata":{"webCommandMetadata":{}},"browseEndpoint":{"browseId":"$browseId"}}}}]}}"""
    private fun remove(id: String = playlist) = """{"removeFromGuideSectionAction":{"guideEntryId":"$id"}}"""
    private fun response(vararg actions: String, extra: String = "") =
        """{"responseContext":{},"actions":[${actions.joinToString(",")}]$extra}"""
    private class Wire(private val body: String, private val status: Int = 200) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return HttpReply(status, body) }
    }
    private fun unconfirmed(body: String, saved: Boolean = true) {
        val wire = Wire(body)
        val result = savePlaylist(playlist, saved, context, wire)
        assertTrue(result.error?.toString(), result.ok)
        assertFalse(result.value!!.action.confirmed)
        assertEquals(1, wire.requests.size)
    }
    private fun key(target: String = playlist) = URLEncoder.encode(Base64.getEncoder().encodeToString(
        byteArrayOf(18, target.length.toByte()) + target.toByteArray(Charsets.UTF_8) + byteArrayOf(32, 62, 40, 1)), "UTF-8")
    private fun mutation(state: String, target: String = playlist, type: String = "ENTITY_MUTATION_TYPE_REPLACE",
        payloadKey: String = key(target)) = """{"entityKey":"${key(target)}","type":"$type",
        "payload":{"likeStatusEntity":{"key":"$payloadKey","likeStatus":"$state"}}}"""
    private fun framework(vararg mutations: String) =
        """, "frameworkUpdates":{"entityBatchUpdate":{"mutations":[${mutations.joinToString(",")}]}}"""

    @Test fun theTwoObservedGuideDirectionsConfirmOnlyTheirRequestedPlaylistAndKeepRequestAttribution() {
        for (saved in listOf(true, false)) {
            val body = response(popup, if (saved) add() else remove())
            val wire = Wire(body)
            val result = savePlaylist(playlist, saved, context, wire)
            assertTrue(result.ok)
            val action = result.value!!.action
            assertTrue(action.confirmed); assertEquals(200, action.httpStatus)
            assertEquals(if (saved) "guide_add" else "guide_remove", action.metadata["playlist_save_acknowledgement"])
            assertEquals("WEB", action.metadata["request_client"])
            assertEquals("web", action.metadata["request_auth"])
            assertFalse(action.metadata.toString().contains(playlist))
            assertEquals(jsonObject(body)!!.encode(), jsonObject(action.body)!!.encode())
            val request = wire.requests.single()
            assertEquals("/youtubei/v1/like/" + if (saved) "like" else "removelike", URI(request.url).path)
            assertEquals(playlist, jsonObject(request.body)!!.obj("target")!!.text("playlistId"))
            assertNull(jsonObject(request.body)!!.obj("target")!!.text("videoId"))
        }
    }

    @Test fun theRawAndBrowseNamespacesAreExactAndNeverNormalizedOrPartiallyMatched() {
        for (target in listOf(playlist, "VL${playlist}suffix", "prefixVL$playlist", "VLPLother", "VL"))
            unconfirmed(response(add(target)))
        for (target in listOf("VL$playlist", "${playlist}suffix", "prefix$playlist", "PLother", ""))
            unconfirmed(response(remove(target)), false)
        unconfirmed(response(remove()), true)
        unconfirmed(response(add()), false)
    }

    @Test fun guideCommandsOutsideTheDirectActionsEnvelopeNeverAcknowledgeASave() {
        for (body in listOf(
            """{"unrelated":${add()}}""", """{"command":${add()}}""",
            """{"onResponseReceivedActions":[${add()}]}""",
            response("""{"commandExecutorCommand":{"commands":[${add()}]}}"""),
            response("""{"openPopupAction":{"popup":${add()}}}"""),
            """{"responseContext":{"actions":[${add()}]}}"""
        )) unconfirmed(body)
    }

    @Test fun malformedItemsEndpointsAndActionWrappersDoNotBecomeGuideEvidence() {
        val valid = add()
        for (body in listOf(
            response("""{"addToGuideSectionAction":{"items":{}}}"""),
            response("""{"addToGuideSectionAction":{"items":[]}}"""),
            response("""{"addToGuideSectionAction":{"items":[42]}}"""),
            response("""{"addToGuideSectionAction":{"items":[{"unknownRenderer":{}}]}}"""),
            response(valid.replace("\"guideEntryRenderer\":{", "\"unknownRenderer\":{},\"guideEntryRenderer\":{")),
            response(valid.replace("\"browseEndpoint\":{", "\"watchEndpoint\":{},\"browseEndpoint\":{")),
            response(valid.replace("\"browseId\":\"VL$playlist\"", "\"browseId\":42")),
            response(valid.replace("\"clickTrackingParams\":\"fixture\"", "\"clickTrackingParams\":{}")),
            response(valid.replace("\"commandMetadata\":{\"webCommandMetadata\":{}}", "\"commandMetadata\":false")),
            response("""{"addToGuideSectionAction":null}"""),
            response("""{"removeFromGuideSectionAction":{"guideEntryId":42}}"""),
            response("""{"removeFromGuideSectionAction":{"guideEntryId":null}}"""),
            response(valid, "42")
        )) {
            val parsed = jsonObject(body)
            assertNotNull("the malformed shape is still valid JSON", parsed)
            unconfirmed(body)
        }
        unconfirmed(response("""{"removeFromGuideSectionAction":{"guideEntryId":42}}"""), false)
        unconfirmed(response("""{"removeFromGuideSectionAction":{"guideEntryId":null}}"""), false)
        unconfirmed(response(valid.replace("\"items\":[", "\"guideEntryId\":\"$playlist\",\"items\":[")))
        unconfirmed(response("""{"removeFromGuideSectionAction":{"guideEntryId":"$playlist","items":[]}}"""), false)
    }

    @Test fun ambiguousOrContradictoryGuideCommandsOverrideEvenAGenericSuccess() {
        for (actions in listOf(arrayOf(add(), add()), arrayOf(add(), remove()), arrayOf(remove(), add()),
            arrayOf(add(), add("VLPLother")), arrayOf(add(), """{"unknownAction":{}}"""),
            arrayOf("""{"addToGuideSectionAction":{},"removeFromGuideSectionAction":{}}""")))
            unconfirmed(response(*actions, extra = ",\"status\":\"SUCCESS\""))
        unconfirmed(response(*(List(33) { if (it == 0) add() else popup }.toTypedArray())))
    }

    @Test fun presentMalformedControlFieldsCannotDisappearIntoMissingDefaults() {
        for (extra in listOf(
            ",\"status\":{}", ",\"status\":42", ",\"status\":null", ",\"success\":\"true\"", ",\"success\":null",
            ",\"actionResults\":{}", ",\"actionResults\":null", ",\"actionResults\":[42]",
            ",\"actionResults\":[{\"status\":42}]", ",\"actionResults\":[{\"success\":\"true\"}]",
            ",\"actionResult\":[]", ",\"actionResult\":null", ",\"actionResult\":{\"status\":false}"
        )) unconfirmed(response(add(), extra = extra))
        unconfirmed("""{"status":"SUCCESS","actions":{}}""")
    }

    @Test fun pendingAndRefusedActionsKeepTheirPriorityOverBothGuideDirections() {
        for (saved in listOf(true, false)) {
            val guide = if (saved) add() else remove()
            for (extra in listOf(",\"status\":\"PENDING\"", ",\"actionResult\":{\"status\":\"PENDING\"}",
                ",\"actionResults\":[{\"status\":\"STATUS_SUCCEEDED\"},{\"status\":\"PENDING\"}]"))
                unconfirmed(response(guide, extra = extra), saved)
            for (extra in listOf(",\"error\":{}", ",\"status\":\"STATUS_FAILED\"", ",\"success\":false",
                ",\"actionResults\":[{\"success\":false}]")) {
                val wire = Wire(response(guide, extra = extra))
                val result = savePlaylist(playlist, saved, context, wire)
                assertFalse(result.ok); assertEquals(RequestFailure.HTTP, result.error!!.kind)
                assertEquals(200, result.error!!.status); assertEquals(1, wire.requests.size)
            }
        }
    }

    @Test fun attestationAndHttpErrorsAreNeverConvertedIntoAcknowledgementOrRetried() {
        val challenge = Wire(response(add(), """{"commandExecutorCommand":{"commands":[{"runAttestationCommand":{}}]}}"""))
        val result = savePlaylist(playlist, true, context, challenge)
        assertEquals(RequestFailure.VERIFICATION, result.error!!.kind)
        assertEquals("attestation_required", result.error!!.code); assertEquals(200, result.error!!.status)
        assertEquals(1, challenge.requests.size)
        val refused = Wire(response(add()), 403)
        val failed = savePlaylist(playlist, true, context, refused)
        assertFalse(failed.ok); assertEquals(403, failed.error!!.status); assertEquals(1, refused.requests.size)
    }

    @Test fun aKnownOppositeOrInvalidatedViewerTargetCannotBeRevivedByAGuideOrGenericSuccess() {
        for (saved in listOf(true, false)) {
            val guide = if (saved) add() else remove()
            val correct = mutation(if (saved) "LIKE" else "INDIFFERENT")
            val opposite = mutation(if (saved) "INDIFFERENT" else "LIKE")
            for (last in listOf(opposite, mutation("UNKNOWN"), mutation("LIKE", type = "ENTITY_MUTATION_TYPE_UPDATE"),
                mutation("LIKE", payloadKey = key("PLother")),
                """{"entityKey":"${key()}","type":"ENTITY_MUTATION_TYPE_DELETE"}"""))
                unconfirmed(response(guide, extra = framework(correct, last) + ",\"status\":\"SUCCESS\""), saved)
            assertTrue(savePlaylist(playlist, saved, context, Wire(response(guide,
                extra = framework(mutation("DISLIKE", "PLother"))))).value!!.action.confirmed)
        }
    }

    @Test fun otherOperationsAndGenericParsingCannotBorrowPlaylistGuideAcknowledgement() {
        for (body in listOf(response(add()), response(remove()))) {
            assertFalse(voteVideo(playlist, VideoVote.LIKE, context, Wire(body)).value!!.confirmed)
            assertFalse(subscribeChannel(playlist, true, context, http = Wire(body)).value!!.confirmed)
            assertFalse(createPlaylist("Fixture", null, context, http = Wire(body)).value!!.action.confirmed)
            assertFalse(editPlaylist(playlist, listOf(PlaylistChange.Rename("Fixture")), context, Wire(body)).value!!.action.confirmed)
            assertFalse(deletePlaylist(playlist, context, Wire(body)).value!!.action.confirmed)
            assertFalse(actionReply(jsonObject(body)!!).value!!.confirmed)
        }
        val anonymous = Wire(response(add()))
        assertEquals(RequestFailure.AUTH, savePlaylist(playlist, true, RequestContext(), anonymous).error!!.kind)
        assertTrue(anonymous.requests.isEmpty())
    }
}
