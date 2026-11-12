package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class NoticePreferenceAcknowledgementTest {
    private val channel = "UC" + "a".repeat(22)
    private val context = RequestContext(accountId = "operator", onBehalfOfUser = "operator-page",
        webSession = WebSession("SAPISID=fixture", 2))
    private class Wire(var body: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, body)
        }
    }

    @Test fun onlyTheExpectedChannelsEnabledReplacementConfirmsEachKnownPreference() {
        val wire = Wire(ack(2))
        for (id in listOf(2, 3, 0)) {
            wire.body = ack(id)
            val state = NoticeState("Localized label", id, "opaque-$id", false)
            val reply = setNoticeState(channel, state, context, wire).value!!
            assertTrue(reply.confirmed); assertEquals(ActionState.CONFIRMED, reply.state); assertEquals(200, reply.httpStatus)
            assertEquals("notification_button_replace", reply.metadata["confirmation"])
            assertEquals("UNCONFIRMED", reply.metadata["notification_response_ack"])
            val request = wire.requests.last()
            val body = jsonObject(request.body)!!
            assertEquals("opaque-$id", body.text("params")); assertNull(body.text("channelId"))
            assertEquals("WEB", body.path("context", "client")!!.text("clientName"))
            assertEquals("SAPISID=fixture", request.headers["Cookie"]); assertEquals("2", request.headers["X-Goog-AuthUser"])
            assertEquals("operator-page", request.headers["X-Goog-PageId"])
            assertEquals("operator-page", body.path("context", "user")!!.text("onBehalfOfUser"))
        }
        assertFalse(setNoticeState("opaque-0", context, wire).value!!.confirmed)
        assertEquals(4, wire.requests.size)
    }

    @Test fun scopeUnknownStatesDisabledControlsAndContradictionsNeverBecomeAcknowledgements() {
        val valid = jsonObject(ack(2))!!
        val button = valid.path("newNotificationButton", "subscriptionNotificationToggleButtonRenderer")!!
        val states = button.arr("states")
        fun modifiedButton(vararg fields: Pair<String, Json>) = Json.Obj(valid.fields + ("newNotificationButton" to
            obj("subscriptionNotificationToggleButtonRenderer" to Json.Obj(button.fields + fields))))
        fun modifiedState(vararg fields: Pair<String, Json>) = modifiedButton("states" to
            Json.Arr(listOf(Json.Obj(state(2).fields + fields))))
        val malformed = listOf(
            Json.Obj(valid.fields + ("channelId" to Json.Str("UC" + "b".repeat(22)))),
            Json.Obj(valid.fields - "channelId"),
            jsonObject(ack(3))!!,
            modifiedButton("currentStateId" to Json.Num("9")),
            modifiedButton("currentStateId" to Json.Str("2")),
            modifiedButton("states" to Json.Arr(states + states.first())),
            modifiedButton("states" to Json.Arr(states + state(9))),
            modifiedButton("states" to Json.Arr(states.filter { (it as Json.Obj).int("stateId") != 2 })),
            modifiedButton("states" to Json.Arr(listOf(state(2, disabled = true), state(3), state(0)))),
            modifiedButton("states" to Json.Arr(listOf(state(2, selected = false), state(3), state(0)))),
            modifiedButton("states" to Json.Arr(listOf(state(2), state(3, selected = true), state(0)))),
            modifiedButton("isDisabled" to Json.Bool(true)),
            modifiedButton("isDisabled" to Json.Str("true")),
            modifiedButton("isDisabled" to Json.Null),
            modifiedState("isSelected" to Json.Str("false")),
            modifiedState("isDisabled" to Json.Num("0")),
            modifiedState("state" to obj("buttonRenderer" to obj("isDisabled" to Json.Bool(false), "isSelected" to Json.Null))),
            modifiedButton("states" to Json.Arr(emptyList())),
            modifiedButton("states" to Json.Arr(listOf(Json.Null))),
            obj("channelId" to Json.Str(channel), "actions" to Json.Arr(listOf(valid.obj("newNotificationButton")!!))),
            Json.Obj(valid.fields + ("newNotificationButton" to Json.Obj(valid.obj("newNotificationButton")!!.fields +
                ("subscribeButtonViewModel" to Json.Obj(emptyMap()))))),
            Json.Obj(valid.fields + ("success" to Json.Bool(true)) - "channelId"))
        val wire = Wire(ack(2))
        for (root in malformed) {
            wire.body = root.encode()
            val result = setNoticeState(channel, NoticeState("All", 2, "opaque", false), context, wire)
            assertTrue(result.ok)
            assertFalse(root.fields.keys.toString(), result.value!!.confirmed)
            assertEquals(ActionState.UNCONFIRMED, result.value!!.state)
        }
        assertEquals(malformed.size, wire.requests.size)
    }

    @Test fun failedAndIndeterminateStatusesTakePriorityOverTheReplacementButton() {
        val valid = jsonObject(ack(2))!!
        val wire = Wire(ack(2))
        val requested = NoticeState("All", 2, "opaque", false)
        for (fields in listOf(mapOf("success" to Json.Bool(false)), mapOf("status" to Json.Str("STATUS_FAILED")),
            mapOf("error" to obj("code" to Json.Num("403"))), mapOf("actionResults" to Json.Arr(listOf(obj("status" to Json.Str("STATUS_FAILED"))))))) {
            wire.body = Json.Obj(valid.fields + fields).encode()
            val result = setNoticeState(channel, requested, context, wire)
            assertFalse(result.ok)
            assertTrue(result.error!!.kind in setOf(RequestFailure.HTTP, RequestFailure.AUTH))
            assertTrue(result.error!!.status in setOf(200, 403))
        }
        for (fields in listOf(mapOf("status" to Json.Str("STATUS_PENDING")),
            mapOf("actionResults" to Json.Arr(listOf(Json.Num("1")))))) {
            wire.body = Json.Obj(valid.fields + fields).encode()
            assertFalse(setNoticeState(channel, requested, context, wire).value!!.confirmed)
        }
    }

    @Test fun invalidUnknownDisabledAndAnonymousRequestsNeverReachTheTransport() {
        val wire = Wire(ack(2))
        val valid = NoticeState("All", 2, "opaque", false)
        assertEquals(RequestFailure.INVALID, setNoticeState("UCshort", valid, context, wire).error!!.kind)
        assertEquals(RequestFailure.INVALID, setNoticeState(channel, valid.copy(params = " "), context, wire).error!!.kind)
        assertEquals("notification_preference_disabled", setNoticeState(channel, valid.copy(disabled = true), context, wire).error!!.code)
        for (id in listOf<Int?>(null, 1, 9)) assertEquals("unknown_notification_preference",
            setNoticeState(channel, valid.copy(stateId = id), context, wire).error!!.code)
        assertEquals(RequestFailure.AUTH, setNoticeState(channel, valid, context.anonymous(), wire).error!!.kind)
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun theJavaOverloadExecutesTheTypedContractAndLeavesTheRawSignatureUnchanged() {
        val owner = Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.NoticeReadKt")
        val typed = owner.getMethod("setNoticeState", String::class.java, NoticeState::class.java, RequestContext::class.java, HttpClient::class.java)
        val wire = Wire(ack(2))
        val result = typed.invoke(null, channel, NoticeState("All", 2, "opaque", false), context, wire) as ReadResult<*>
        assertTrue((result.value as ActionReply).confirmed)
        val raw = owner.getMethod("setNoticeState", String::class.java, RequestContext::class.java, HttpClient::class.java)
        val rawResult = raw.invoke(null, "opaque", context, wire) as ReadResult<*>
        assertFalse((rawResult.value as ActionReply).confirmed)
        assertEquals(2, wire.requests.size)
    }

    private fun ack(current: Int) = obj("responseContext" to Json.Obj(emptyMap()), "channelId" to Json.Str(channel),
        "actions" to Json.Arr(listOf(obj("openPopupAction" to Json.Obj(emptyMap())))),
        "newNotificationButton" to obj("subscriptionNotificationToggleButtonRenderer" to obj(
            "currentStateId" to Json.Num(current.toString()), "states" to Json.Arr(listOf(state(2), state(3), state(0)))))).encode()
    private fun state(id: Int, disabled: Boolean = false, selected: Boolean? = null): Json.Obj =
        Json.Obj(mapOf("stateId" to Json.Num(id.toString()), "state" to obj("buttonRenderer" to obj("isDisabled" to Json.Bool(disabled)))) +
            selected?.let { mapOf("isSelected" to Json.Bool(it)) }.orEmpty())
    private fun obj(vararg fields: Pair<String, Json>) = Json.Obj(linkedMapOf(*fields))
}
