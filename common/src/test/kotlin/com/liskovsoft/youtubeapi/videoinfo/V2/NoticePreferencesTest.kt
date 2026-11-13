package com.liskovsoft.youtubeapi.videoinfo.V2

import java.lang.Boolean as JavaBoolean

import org.junit.Assert.*
import org.junit.Test

class NoticePreferencesTest {
    @Test fun modernSubscriptionSheetUsesTechnicalImagesAndServerSelectionRatherThanTitlesOrOpaqueParams() {
        val options = noticeStates(sheet(listOf(
            item("NOTIFICATIONS_ACTIVE", "关闭", "opaque-a"),
            item("NOTIFICATIONS_NONE", "すべて", "opaque-b", selected = true),
            item("NOTIFICATIONS_OFF", "Personalized", "opaque-c"),
            item("PERSON_MINUS", "All", "unsubscribe", endpoint = "unsubscribeEndpoint"))))
        assertEquals(listOf(2, 3, 0), options.map { it.stateId })
        assertEquals(listOf("关闭", "すべて", "Personalized"), options.map { it.title })
        assertEquals(listOf("opaque-a", "opaque-b", "opaque-c"), options.map { it.params })
        assertEquals(listOf(false, true, false), options.map { it.selected })
        assertTrue(options.none { it.disabled })
    }

    @Test fun disabledCommandsRemainDisabledAndDisabledOnlyPresentationDoesNotInventACapability() {
        val options = noticeStates(sheet(listOf(item("NOTIFICATIONS_ACTIVE", "All", "a", disabled = true),
            item("NOTIFICATIONS_NONE", "Personalized", "b", selected = true),
            item("NOTIFICATIONS_OFF", "None", null, disabled = true))))
        assertEquals(2, options.size)
        assertTrue(options.first().disabled)
        assertFalse(options.last().disabled)
        assertTrue(options.filter { !it.disabled }.single().selected)
        assertTrue(noticeStates(sheet(listOf(item("NOTIFICATIONS_OFF", "None", null, disabled = true)))).isEmpty())
        for (flag in listOf("disableNotificationBell", "disableSubscribeButton", "isSignedOut")) {
            assertTrue(noticeStates(sheet(listOf(item("NOTIFICATIONS_ACTIVE", "All", "a", selected = true)), flag))
                .single().let { it.selected && it.disabled })
        }
    }

    @Test fun onlyRealModifyEndpointsWithCompatibleWrapperMetadataAreAccepted() {
        val rejected = listOf(
            item("NOTIFICATIONS_ACTIVE", "All", "a", url = "/youtubei/v1/subscription/unsubscribe"),
            item("NOTIFICATIONS_ACTIVE", "All", "a", url = "https://external.test/youtubei/v1/notification/modify_channel_preference"),
            item("NOTIFICATIONS_ACTIVE", "All", "a", url = "https://user@www.youtube.com/youtubei/v1/notification/modify_channel_preference"),
            item("NOTIFICATIONS_ACTIVE", "All", "a", url = "//external.test/youtubei/v1/notification/modify_channel_preference"),
            item("NOTIFICATIONS_ACTIVE", "All", "a", outerUrl = "/youtubei/v1/feedback"),
            item("NOTIFICATIONS_ACTIVE", "All", "a", sendPost = false),
            item("NOTIFICATIONS_ACTIVE", "All", " "),
            item("NOTIFICATIONS_ACTIVE", "All", "a", endpoint = "unsubscribeEndpoint"))
        assertTrue(noticeStates(sheet(rejected)).isEmpty())
        val valid = noticeStates(sheet(listOf(item("NOTIFICATIONS_ACTIVE", "All", "a",
            url = "https://www.youtube.com/youtubei/v1/notification/modify_channel_preference?prettyPrint=false"))))
        assertEquals("a", valid.single().params)
    }

    @Test fun malformedPermissionAndSelectionFlagsNeverBecomeWritablePreferences() {
        val base = item("NOTIFICATIONS_ACTIVE", "All", "opaque")
        val renderer = base.obj("listItemViewModel")!!
        val transport = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("malformed controls must not submit")
        }
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        for (flag in listOf("isDisabled", "isSelected")) for (value in listOf(Json.Null, Json.Str("false"), Json.Num("0"))) {
            val malformed = obj("listItemViewModel" to Json.Obj(renderer.fields + (flag to value)))
            val parsed = noticeStates(sheet(listOf(malformed))).single()
            assertTrue(parsed.disabled)
            assertEquals("notification_preference_disabled", setNoticeState("UC" + "a".repeat(22), parsed, context, transport).error?.code)
        }
        for (flag in listOf("disableNotificationBell", "disableSubscribeButton", "isSignedOut")) {
            val root = jsonObject(sheet(listOf(base)))!!
            val button = root.path("header", "subscribeButtonViewModel")!!
            val malformed = Json.Obj(root.fields + ("header" to obj("subscribeButtonViewModel" to
                Json.Obj(button.fields + (flag to Json.Str("false"))))))
            assertTrue(noticeStates(malformed.encode()).single().disabled)
        }
        val malformedPost = sheet(listOf(base)).replace("\"sendPost\":true", "\"sendPost\":\"true\"")
        assertTrue(noticeStates(malformedPost).isEmpty())
        val legacy = """{"subscriptionNotificationToggleButtonRenderer":{"currentStateId":2,"states":[{
            "stateId":2,"inlineMenuButton":{"buttonRenderer":{"isDisabled":"false","serviceEndpoint":{
                "modifyChannelNotificationPreferenceEndpoint":{"params":"legacy"}}}}}]}}"""
        assertTrue(noticeStates(legacy).single().disabled)
    }

    @Test fun unknownAndContradictoryImagesKeepUnknownKindsWithoutChangingSelection() {
        val unknown = item("FUTURE_NOTIFICATION_ICON", "All", "2", selected = true)
        val contradictory = item("NOTIFICATIONS_ACTIVE", "Personalized", "3")
        val renderer = contradictory.obj("listItemViewModel")!!
        val images = renderer.obj("leadingImage")!!.arr("sources") +
            obj("clientResource" to obj("imageName" to Json.Str("NOTIFICATIONS_OFF")))
        val conflicting = obj("listItemViewModel" to Json.Obj(renderer.fields +
            ("leadingImage" to obj("sources" to Json.Arr(images)))))
        val options = noticeStates(sheet(listOf(unknown, conflicting)))
        assertTrue(options.all { it.stateId == null })
        assertTrue(options.first().selected)
        assertEquals(listOf("2", "3"), options.map { it.params })
        assertTrue(noticeStates(obj("listViewModel" to obj("listItems" to Json.Arr(listOf(unknown)))).encode()).isEmpty())
        assertTrue(noticeStates("not json").isEmpty())
    }

    @Test fun parsedPreferencesDriveToggleAndRestoreUsingOnlyTheirUnchangedServerParameters() {
        var selected = 3
        val context = RequestContext(accountId = "owner", onBehalfOfUser = "page", webSession = WebSession("SAPISID=fixture", 2))
        val used = ArrayList<String>()
        fun current() = noticeStates(sheet(listOf(item("NOTIFICATIONS_ACTIVE", "Local all", "opaque-a", selected == 2),
            item("NOTIFICATIONS_NONE", "Local personal", "opaque-b", selected == 3),
            item("NOTIFICATIONS_OFF", "Local off", "opaque-c", selected == 0))))
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals(YOUTUBE_API + "notification/modify_channel_preference", request.url)
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                assertEquals("2", request.headers["X-Goog-AuthUser"])
                assertEquals("page", request.headers["X-Goog-PageId"])
                assertEquals("page", jsonObject(request.body)!!.path("context", "user")!!.text("onBehalfOfUser"))
                val params = jsonObject(request.body)!!.text("params")!!
                used += params
                selected = mapOf("opaque-a" to 2, "opaque-b" to 3, "opaque-c" to 0).getValue(params)
                return HttpReply(200, """{"success":true}""")
            }
        }
        val original = current().single { it.selected && !it.disabled }
        val alternative = current().first { !it.selected && !it.disabled }
        assertTrue(setNoticeState(alternative.params, context, http).value!!.confirmed)
        assertEquals(alternative.stateId, current().single { it.selected }.stateId)
        val restore = current().single { it.stateId == original.stateId && !it.disabled }
        assertTrue(setNoticeState(restore.params, context, http).value!!.confirmed)
        assertEquals(original.stateId, current().single { it.selected }.stateId)
        assertEquals(listOf("opaque-a", "opaque-b"), used)
    }

    @Test fun legacyStateIdsAndTheOldJavaConstructorRetainTheirMeaning() {
        val legacy = """{"subscriptionNotificationToggleButtonRenderer":{"currentStateId":2,"states":[
            {"stateId":2,"inlineMenuButton":{"buttonRenderer":{"isDisabled":true,"text":{"simpleText":"All"},
            "serviceEndpoint":{"modifyChannelNotificationPreferenceEndpoint":{"params":"legacy"}}}}},
            {"stateId":3,"inlineMenuButton":{"buttonRenderer":{"serviceEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/feedback"}},
            "modifyChannelNotificationPreferenceEndpoint":{"params":"wrong"}}}}}]}}"""
        val parsed = noticeStates(legacy).single()
        assertTrue(parsed.selected); assertTrue(parsed.disabled); assertEquals(2, parsed.stateId)
        val old = NoticeState::class.java.getConstructor(String::class.java, Integer::class.java,
            String::class.java, JavaBoolean.TYPE).newInstance(parsed.title, parsed.stateId, parsed.params, parsed.selected)
        assertEquals(parsed.title, old.title); assertEquals(parsed.params, old.params)
        assertEquals(parsed.stateId, old.stateId); assertTrue(old.selected); assertFalse(old.disabled)
        assertEquals(NoticeState(parsed.title, parsed.stateId, parsed.params, parsed.selected, true), parsed)
    }

    private companion object {
        fun obj(vararg fields: Pair<String, Json>) = Json.Obj(linkedMapOf(*fields))
        fun metadata(url: String, sendPost: Boolean = true) = obj("webCommandMetadata" to
            obj("apiUrl" to Json.Str(url), "sendPost" to Json.Bool(sendPost)))
        fun item(image: String, title: String, params: String?, selected: Boolean = false, disabled: Boolean = false,
            url: String = "/youtubei/v1/notification/modify_channel_preference", outerUrl: String? = null,
            sendPost: Boolean = true, endpoint: String = "modifyChannelNotificationPreferenceEndpoint"): Json.Obj {
            val command = obj("commandMetadata" to metadata(url, sendPost), endpoint to
                obj("params" to (params?.let(Json::Str) ?: Json.Null)))
            val onTap = Json.Obj(mapOf("innertubeCommand" to command) +
                outerUrl?.let { mapOf("commandMetadata" to metadata(it)) }.orEmpty())
            return obj("listItemViewModel" to obj("title" to obj("content" to Json.Str(title)),
                "leadingImage" to obj("sources" to Json.Arr(listOf(obj("clientResource" to obj("imageName" to Json.Str(image)))))),
                "isSelected" to Json.Bool(selected), "isDisabled" to Json.Bool(disabled),
                "rendererContext" to obj("commandContext" to obj("onTap" to onTap))))
        }
        fun sheet(items: List<Json.Obj>, disabledFlag: String? = null): String {
            val button = mapOf("onShowSubscriptionOptions" to obj("innertubeCommand" to obj("showSheetCommand" to
                obj("panelLoadingStrategy" to obj("inlineContent" to obj("sheetViewModel" to obj("content" to
                    obj("listViewModel" to obj("listItems" to Json.Arr(items)))))))))) +
                disabledFlag?.let { mapOf(it to Json.Bool(true)) }.orEmpty()
            return obj("header" to obj("subscribeButtonViewModel" to Json.Obj(button))).encode()
        }
    }
}
