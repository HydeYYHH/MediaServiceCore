package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeBrowseReadTest {
    private val context = RequestContext(accountId = "fixture-account", visitor = "fixture-visitor",
        language = "zh-TW", region = "TW", generation = 7, onBehalfOfUser = "fixture-page",
        webSession = WebSession("SAPISID=fixture", 1), visitorCookie = "VISITOR_INFO1_LIVE=guest")
    private val entries = """{"notificationRenderer":{"notificationId":"first","read":false,
        "shortMessage":{"simpleText":"First upload"},"navigationEndpoint":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}}}},
        {"notificationRenderer":{"notificationId":"second","read":true,"shortMessage":{"simpleText":"Second upload"}}}"""
    private fun popup(items: String, identity: String? = null): String {
        val response = identity?.let { "\"responseContext\":$it," }.orEmpty()
        return """{$response"trackingParams":"fixture","onResponseReceivedEndpoints":[{"openPopupAction":{
            "popup":{"multiPageMenuRenderer":{"sections":[{"multiPageMenuNotificationSectionRenderer":{"items":[$items]}}]}}}}]}"""
    }
    private fun assertIdentity(request: HttpRequest) {
        val uri = URI(request.url)
        assertEquals("https", uri.scheme)
        assertEquals("www.youtube.com", uri.host)
        assertEquals("/youtubei/v1/browse", uri.path)
        assertEquals("SAPISID=fixture", request.headers["Cookie"])
        assertEquals("1", request.headers["X-Goog-AuthUser"])
        assertEquals("fixture-page", request.headers["X-Goog-PageId"])
        assertEquals("fixture-visitor", request.headers["X-Goog-Visitor-Id"])
        assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        val body = jsonObject(request.body)!!
        val client = body.path("context", "client")!!
        assertEquals("WEB", client.text("clientName"))
        assertEquals("zh-TW", client.text("hl"))
        assertEquals("TW", client.text("gl"))
        assertEquals("fixture-visitor", client.text("visitorData"))
        assertEquals("fixture-page", body.path("context", "user")!!.text("onBehalfOfUser"))
        assertFalse(body.fields.containsKey("notificationsMenuRequestType"))
    }

    @Test fun browserInboxUsesBrowseAndReadsItsOwnedPopup() {
        var calls = 0
        val response = loadNotices(context, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertIdentity(request)
                assertEquals("prettyPrint=false", URI(request.url).rawQuery)
                val body = jsonObject(request.body)!!
                assertEquals("FEnotifications_inbox", body.text("browseId"))
                assertNull(body.text("continuation"))
                return HttpReply(200, popup(entries, """{"mainAppWebResponseContext":{"loggedOut":false}}"""))
            }
        })
        assertTrue(response.error.toString(), response.ok)
        assertEquals(listOf("first", "second"), response.value!!.notices.map { it.id })
        assertEquals(listOf(false, true), response.value!!.notices.map { it.read })
        assertEquals(NoticeDestination.Video("dQw4w9WgXcQ"), response.value!!.notices.first().destination)
        assertNull(response.value!!.nextKey)
        assertEquals(1, calls)
    }

    @Test fun browseContinuationKeepsTheAccountAndItsOpaqueCursor() {
        val cursor = """{"continuationItemRenderer":{"continuationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},
            "continuationCommand":{"request":"CONTINUATION_REQUEST_TYPE_BROWSE","token":"opaque-next"}}}}"""
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertIdentity(request)
                val body = jsonObject(request.body)!!
                return when (calls++) {
                    0 -> {
                        assertEquals("FEnotifications_inbox", body.text("browseId"))
                        assertNull(body.text("continuation"))
                        HttpReply(200, popup("$entries,$cursor"))
                    }
                    1 -> {
                        assertEquals("opaque-next", body.text("continuation"))
                        assertNull(body.text("browseId"))
                        HttpReply(200, """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{
                            "continuationItems":[{"notificationRenderer":{"notificationId":"third",
                            "shortMessage":{"simpleText":"Third upload"}}}]}}]}""")
                    }
                    else -> error("Unexpected request")
                }
            }
        }
        val first = loadNotices(context, http = http)
        assertTrue(first.error.toString(), first.ok)
        assertEquals("opaque-next", first.value!!.nextKey)
        val next = loadNotices(context, first.value!!.nextKey, http)
        assertTrue(next.error.toString(), next.ok)
        assertEquals(listOf("third"), next.value!!.notices.map { it.id })
        assertNull(next.value!!.nextKey)
        assertEquals(2, calls)
    }

    @Test fun explicitlySignedOutResponsesCannotBecomeSuccessfulEmptyInboxes() {
        for (identity in listOf("""{"mainAppWebResponseContext":{"loggedOut":true}}""",
            """{"serviceTrackingParams":[{"params":[{"key":"logged_in","value":"0"}]}]}""")) {
            for (cursor in listOf(null, "issued-next")) {
                var calls = 0
                val response = loadNotices(context, cursor, object : HttpClient {
                    override fun execute(request: HttpRequest): HttpReply {
                        calls++
                        assertIdentity(request)
                        return HttpReply(200, popup("", identity))
                    }
                })
                assertEquals(RequestFailure.AUTH, response.error!!.kind)
                assertEquals("sign_in_required", response.error!!.code)
                assertNull(response.value)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun signedInAndUnspecifiedResponseIdentityPreserveGenuineEmptyInboxes() {
        for (identity in listOf(null, """{"mainAppWebResponseContext":{"loggedOut":false}}""",
            """{"serviceTrackingParams":[{"params":[{"key":"logged_in","value":"1"}]}]}""")) {
            val response = loadNotices(context, http = object : HttpClient {
                override fun execute(request: HttpRequest) = HttpReply(200, popup("", identity))
            })
            assertTrue(response.error.toString(), response.ok)
            assertTrue(response.value!!.notices.isEmpty())
            assertNull(response.value!!.nextKey)
        }
    }

    @Test fun unknownBrowseResponsesDoNotFallBackToTheLegacyEmptyMenu() {
        var calls = 0
        val response = loadNotices(context, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertIdentity(request)
                return HttpReply(200, """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}}}""")
            }
        })
        assertEquals(RequestFailure.PARSE, response.error!!.kind)
        assertEquals("unknown_notification_page", response.error!!.code)
        assertNull(response.value)
        assertEquals(1, calls)
    }
}
