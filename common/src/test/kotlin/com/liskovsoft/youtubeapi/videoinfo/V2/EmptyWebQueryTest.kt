package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyWebQueryTest {
    private val context = RequestContext(accountId = "account", visitor = "visitor", language = "zh-CN",
        region = "TW", webSession = WebSession("SAPISID=fixture", 1), onBehalfOfUser = "brand")

    private fun assertBoundContext(request: HttpRequest): Json.Obj {
        assertEquals("POST", request.method)
        assertEquals("SAPISID=fixture", request.headers["Cookie"])
        assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        assertEquals("1", request.headers["X-Goog-AuthUser"])
        assertEquals("brand", request.headers["X-Goog-PageId"])
        assertEquals("visitor", request.headers["X-Goog-Visitor-Id"])
        val body = jsonObject(request.body)
        assertNotNull("The actual transport body must be strict JSON", body)
        val client = body!!.path("context", "client")!!
        assertEquals("WEB", client.text("clientName"))
        assertEquals("zh-CN", client.text("hl"))
        assertEquals("TW", client.text("gl"))
        assertEquals("visitor", client.text("visitorData"))
        assertEquals("brand", body.path("context", "user")!!.text("onBehalfOfUser"))
        return body
    }

    @Test fun aContextOnlyGuideRequestRetainsTheAuthenticatedSessionAndLocale() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            assertEquals(YOUTUBE_API + "guide", request.url)
            assertNull(assertBoundContext(request).text("browseId"))
            return HttpReply(200, """{"guideEntryRenderer":{"title":{"simpleText":"Ada"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""")
        } }
        val result = loadSubscribedChannelsPage(context, http = http)
        assertTrue(result.ok)
        assertEquals("UCada", result.value!!.channels.single().id)
        assertEquals(1, calls)
    }

    @Test fun aContextOnlyNotificationCountRequestRetainsTheAuthenticatedSessionAndLocale() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            assertEquals(YOUTUBE_API + "notification/get_unseen_count", request.url)
            assertFalse(assertBoundContext(request).fields.containsKey("continuation"))
            return HttpReply(200, """{"unseenCount":3}""")
        } }
        val result = loadNoticeCount(context, http)
        assertTrue(result.ok)
        assertEquals(3L, result.value!!.unseenCount)
        assertEquals(1, calls)
    }
}
