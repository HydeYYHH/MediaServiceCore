package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class NoticeReadTest {
    private val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
    private fun http(body: String, status: Int = 200) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(status, body)
    }

    @Test fun readsAllSectionsAndContinuesWithTheSameWebIdentity() {
        val body = """{"sections":[{"notificationRenderer":{"shortMessage":{"simpleText":"Ada: One"}}},{"notificationRenderer":{"shortMessage":{"simpleText":"Bob: Two"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}"""
        val page = loadNotices(context, http = http(body)).value!!
        assertEquals(2, page.notices.size)
        assertEquals("Bob", page.notices.last().author)
        assertEquals("next", page.nextKey)
        val result = loadNotices(context, page.nextKey, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("next", jsonObject(request.body)!!.text("continuation"))
                assertEquals("WEB", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                return HttpReply(200, """{"multiPageMenuNotificationSectionRenderer":{"items":[]}}""")
            }
        })
        assertTrue(result.ok)
        assertTrue(result.value!!.notices.isEmpty())
        assertNull(result.value!!.nextKey)
    }

    @Test fun unknownResponsesAndFailuresAreNeverAnEmptyInbox() {
        for (body in listOf("{}", """{"responseContext":{}}""", """{"unknown":[]}"""))
            assertEquals("unknown_notification_page", loadNotices(context, http = http(body)).error!!.code)
        assertEquals(RequestFailure.AUTH, loadNotices(context, http = http("{}", 403)).error!!.kind)
        assertEquals(RequestFailure.TRANSPORT, loadNotices(context, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = throw java.io.IOException("failure")
        }).error!!.kind)
        assertEquals(RequestFailure.AUTH, loadNotices(RequestContext(), http = http("{}")).error!!.kind)
        for (empty in listOf("""{"multiPageMenuRenderer":{"sections":[]}}""",
            """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""")) {
            val result = loadNotices(context, http = http(empty))
            assertTrue(result.ok)
            assertTrue(result.value!!.notices.isEmpty())
            assertNull(result.value!!.nextKey)
        }
    }

    @Test fun counterHasExplicitUnknownAndSupportsBothObservedResponseEnvelopes() {
        assertEquals(3L, noticeCount("""{"unseenCount":3}""").value!!.unseenCount)
        assertEquals(0L, noticeCount("""{"actions":[{"updateNotificationsUnseenCountAction":{"unseenCount":0}}]}""").value!!.unseenCount)
        for (body in listOf("{}", """{"unseenCount":-1}""", """{"unseenCount":"invalid"}"""))
            assertEquals("missing_notification_count", noticeCount(body).error!!.code)
        val command = javaClass.getResourceAsStream("/fixtures/web-notification-counter.json")!!.bufferedReader().use { it.readText() }
        val endpoint = jsonObject(command)!!.path("updateUnseenCountEndpoint", "commandMetadata", "webCommandMetadata")!!.text("apiUrl")!!
        val count = loadNoticeCount(context, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertTrue(request.url.endsWith(endpoint.removePrefix("/youtubei/v1/")))
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                return HttpReply(200, """{"unseenCount":4}""")
            }
        })
        assertEquals(4L, count.value!!.unseenCount)
    }

    @Test fun readAndHideUseTheirOwnServerIssuedTokens() {
        // Contract fixture: endpoint payloads are opaque; no notification-ID-to-token synthesis.
        val notice = notices("""{"notificationRenderer":{"notificationId":"notice-id","read":false,
          "recordClickEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/notification/record_interactions"}},
            "recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"READ"}},
          "contextualMenu":{"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"HIDE"}}}}""").single()
        assertEquals("notice-id", notice.id)
        assertEquals(false, notice.read)
        assertEquals("NoticeCommand(record_interactions)", notice.readCommand.toString())
        assertFalse(notice.readCommand.toString().contains("READ"))
        val tokens = ArrayList<String>()
        val transport = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                tokens += jsonObject(request.body)!!.text("serializedRecordNotificationInteractionsRequest")!!
                return HttpReply(200, """{"success":true}""")
            }
        }
        assertTrue(markNoticeRead(notice, context, transport).value!!.confirmed)
        assertTrue(hideNotice(notice.hideKey!!, context, transport).value!!.confirmed)
        assertEquals(listOf("READ", "HIDE"), tokens)
        assertFalse(markNoticeRead(notice, context, http("{}")).value!!.confirmed)
        assertFalse(markNoticeRead(notice, context, http("""{"playlistId":"unrelated"}""")).value!!.confirmed)
        assertFalse(markNoticeRead(notice, context, http("""{"success":false}""")).ok)
    }

    @Test fun missingReadStateIsUnknownAndUntrustedReadEndpointsCannotExecute() {
        for (command in listOf("{}", """{"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":""}}""",
            """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://evil.test/notification/record_interactions"}},"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"token"}}""",
            """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/account/delete"}},"innertubeCommand":{"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"token"}}}""",
            """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/account/delete"}},"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"token"}}""")) {
            val notice = notices("""{"notificationRenderer":{"notificationId":"id","recordClickEndpoint":$command}}""").single()
            assertNull(notice.read)
            assertNull(notice.readCommand)
            assertEquals("missing_notification_read_command", markNoticeRead(notice, context, object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply = error("No request expected")
            }).error!!.code)
        }
    }

    @Test fun aContextualHideTokenNeverSubstitutesForTheReadCommand() {
        val notice = notices("""{"notificationRenderer":{"notificationId":"id","read":false,
          "contextualMenu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{
            "recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"HIDE_ONLY"}}}}]}}}}""").single()
        assertEquals("HIDE_ONLY", notice.hideKey)
        assertNull(notice.readCommand)
        assertEquals("missing_notification_read_command", markNoticeRead(notice, context, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("A hide token cannot mark read")
        }).error!!.code)
    }

    @Test fun feedbackBusinessFailureIsNotAnEmptySuccess() {
        assertFalse(sendFeedback("key", context, http("""{"feedbackResponses":[{"isProcessed":false}]}""")).ok)
    }
}
