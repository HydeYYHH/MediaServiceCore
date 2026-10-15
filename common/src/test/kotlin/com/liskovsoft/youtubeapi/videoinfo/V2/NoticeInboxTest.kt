package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val parsed = loadNotices("visitor-1") { query ->
            assertTrue(query.url.contains("/notification/get_notification_menu"))
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            assertTrue(query.body.contains("\"notificationsMenuRequestType\":\"NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX\""))
            Pair(200, inbox)
        }
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

    @Test
    fun hidesANotice() {
        val ok = hideNotice("HIDE_ME", null) { query ->
            assertTrue(query.url.contains("/notification/record_interactions"))
            assertTrue(query.body.contains("\"serializedRecordNotificationInteractionsRequest\":\"HIDE_ME\""))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun emptyHideKeySkipsPost() {
        var called = false
        val ok = hideNotice("", null) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(ok)
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

    @Test
    fun setsANoticeState() {
        val ok = setNoticeState("ALL", null) { query ->
            assertTrue(query.url.contains("/notification/modify_channel_preference"))
            assertTrue(query.body.contains("\"params\":\"ALL\""))
            Pair(200, "")
        }
        assertTrue(ok)
    }

    @Test
    fun blankParamsSkipPost() {
        var called = false
        val ok = setNoticeState(" \n", null) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(ok)
        assertNull(notices("{}").firstOrNull())
    }
}
