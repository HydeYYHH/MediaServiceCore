package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class NoticeInboxTest {
    private val inbox = """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[
          {"multiPageMenuNotificationSectionRenderer":{"items":[{"notificationRenderer":{
            "shortMessage":{"simpleText":"Ada: hello"},
            "sentTimeText":{"simpleText":"2 hours ago"},
            "videoThumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/a/hqdefault.jpg"}]},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}},
            "contextualMenu":{"menuRenderer":{"items":[
              {"menuServiceItemRenderer":{"serviceEndpoint":{"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"HIDE_ME"}}}}
            ]}}
          }}]}}
        ]}}}}]}
    """
    private val bell = """
        {"subscriptionNotificationToggleButtonRenderer":{
          "currentStateId":2,
          "states":[
            {"stateId":2,"inlineMenuButton":{"buttonRenderer":{
              "text":{"simpleText":"All"},
              "serviceEndpoint":{"modifyChannelNotificationPreferenceEndpoint":{"params":"ALL"}}
            }}},
            {"stateId":3,"inlineMenuButton":{"buttonRenderer":{
              "text":{"runs":[{"text":"Personal"}]},
              "serviceEndpoint":{"modifyChannelNotificationPreferenceEndpoint":{"params":"SOME"}}
            }}},
            {"stateId":0,"inlineMenuButton":{"buttonRenderer":{"text":{"simpleText":"None"}}}}
          ]
        }}
    """

    @Test
    fun loadsTheInbox() {
        val parsed = loadNotices(RequestContext(visitor = "visitor-1", webSession = WebSession("SAPISID=fixture")), http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
            val query = PlayerQuery(request.url, request.headers, request.body)
            assertEquals("/youtubei/v1/browse", URI(query.url).path)
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            assertEquals("FEnotifications_inbox", jsonObject(query.body)!!.text("browseId"))
            assertFalse(jsonObject(query.body)!!.fields.containsKey("notificationsMenuRequestType"))
            return HttpReply(200, inbox)
            }
        }).value!!.notices
        assertEquals(1, parsed.size)
        val notice = parsed[0]
        assertEquals("dQw4w9WgXcQ", notice.videoId)
        assertEquals("Ada: hello", notice.message)
        assertEquals("Ada", notice.author)
        assertEquals("hello", notice.title)
        assertEquals("2 hours ago", notice.published)
        assertEquals("https://i.ytimg.com/vi/a/hqdefault.jpg", notice.thumbnail)
        assertEquals("HIDE_ME", notice.hideKey)
    }

    @Test fun hidesOnlyWithAnExplicitServerAcknowledgement() {
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.url.endsWith("/notification/record_interactions"))
                assertEquals("HIDE_ME", jsonObject(request.body)!!.text("serializedRecordNotificationInteractionsRequest"))
                return HttpReply(200, """{"success":true}""")
            }
        }
        assertTrue(hideNotice("HIDE_ME", context, http).value!!.confirmed)
        assertEquals("missing_notification_token", hideNotice("", context, http).error!!.code)
        assertEquals(1, calls)
    }

    @Test
    fun readsTheSelectedNoticeState() {
        val states = noticeStates(bell)
        assertEquals(2, states.size)
        assertEquals("All", states[0].title)
        assertEquals(2, states[0].stateId)
        assertEquals("ALL", states[0].params)
        assertTrue(states[0].selected)
        assertEquals("Personal", states[1].title)
        assertFalse(states[1].selected)
    }

    @Test fun setsANoticeStateAndRejectsMissingParameters() {
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.url.endsWith("/notification/modify_channel_preference"))
                assertEquals("ALL", jsonObject(request.body)!!.text("params"))
                return HttpReply(200, """{"success":true}""")
            }
        }
        assertTrue(setNoticeState("ALL", context, http).value!!.confirmed)
        assertEquals("missing_notification_params", setNoticeState(" ", context, http).error!!.code)
        assertEquals(1, calls)
    }
}
