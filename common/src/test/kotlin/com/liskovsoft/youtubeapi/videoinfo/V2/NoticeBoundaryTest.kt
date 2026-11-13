package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class NoticeBoundaryTest {
    private val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
    private fun read(body: String, key: String? = null): ReadResult<NoticePage> = loadNotices(context, key, object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, body)
    })
    private fun rejected(body: String) {
        val result = read(body)
        assertFalse(body, result.ok)
        assertEquals(RequestFailure.PARSE, result.error!!.kind)
        assertEquals("unknown_notification_page", result.error!!.code)
    }

    private val emptyPromo = """{"backgroundPromoRenderer":{"title":{"simpleText":"Notifications"},
        "bodyText":{"runs":[{"text":"Notifications appear here"}]},"icon":{"iconType":"NOTIFICATIONS"},"trackingParams":"fixture"}}"""
    private fun promoMenu(section: String = emptyPromo, parentFields: String = "") =
        """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[$section]$parentFields}}}}]}"""

    @Test fun ownedSoleNotificationBannerIsAnEmptyMenuWithoutItemCapabilities() {
        for (section in listOf(emptyPromo, emptyPromo.replace("Notifications", "通知").replace("appear here", "会显示在这里"))) {
            val response = read(promoMenu(section))
            assertTrue(response.error.toString(), response.ok)
            assertTrue(response.value!!.notices.isEmpty())
            assertNull(response.value!!.nextKey)
            // The recognized banner creates no synthetic notice, read/hide token or continuation.
            val next = read(promoMenu(section), "issued-next")
            assertTrue(next.ok)
            assertTrue(next.value!!.notices.isEmpty())
            assertNull(next.value!!.nextKey)
        }
    }

    @Test fun genericPromotionAndMalformedBannerCannotManufactureAnEmptyInbox() {
        for (section in listOf(
            emptyPromo.replace("\"NOTIFICATIONS\"", "\"NOTIFICATIONS_NONE\""),
            emptyPromo.replace("\"NOTIFICATIONS\"", "\"SHOPPING\""),
            emptyPromo.replace("\"NOTIFICATIONS\"", "true"),
            emptyPromo.replace("\"iconType\":\"NOTIFICATIONS\"", "\"iconType\":\"NOTIFICATIONS\",\"futureField\":true"),
            emptyPromo.replace("\"trackingParams\":\"fixture\"", "\"trackingParams\":false"),
            emptyPromo.replace("\"trackingParams\":\"fixture\"", "\"futureField\":{}"),
            emptyPromo.replace("\"trackingParams\":\"fixture\"", "\"ctaButton\":null"),
            emptyPromo.replace("\"trackingParams\":\"fixture\"", "\"ctaButton\":{\"buttonRenderer\":{}}"),
            emptyPromo.replace("\"title\":{\"simpleText\":\"Notifications\"}", "\"title\":\"Notifications\""),
            emptyPromo.replace("\"title\":{\"simpleText\":\"Notifications\"}", "\"title\":{}"),
            emptyPromo.replace("\"simpleText\":\"Notifications\"", "\"simpleText\":\" \""),
            emptyPromo.replace("\"bodyText\":{\"runs\":[{\"text\":\"Notifications appear here\"}]}", "\"bodyText\":null"),
            emptyPromo.replace("\"runs\":[{\"text\":\"Notifications appear here\"}]", "\"runs\":[]"),
            emptyPromo.replace("\"runs\":[{\"text\":\"Notifications appear here\"}]", "\"runs\":[{\"text\":\" \"}]"),
            emptyPromo.replace("\"runs\":[{\"text\":\"Notifications appear here\"}]", "\"runs\":[{\"text\":\"Readable\"},123]"),
            emptyPromo.replace("\"runs\":[{\"text\":\"Notifications appear here\"}]", "\"runs\":[{\"text\":\"Readable\",\"navigationEndpoint\":{}}]")
        )) rejected(promoMenu(section))
    }

    @Test fun notificationBannerHasNoMeaningOutsideItsSoleOwnedMenuSection() {
        val notification = """{"notificationRenderer":{"notificationId":"own","shortMessage":{"simpleText":"Own notification"}}}"""
        for (body in listOf(
            emptyPromo,
            """{"sections":[$emptyPromo]}""",
            """{"notificationSectionRenderer":{"items":[$emptyPromo]}}""",
            """{"actions":[{"appendContinuationItemsAction":{"continuationItems":[$emptyPromo]}}]}""",
            """{"diagnostic":{"multiPageMenuRenderer":{"sections":[$emptyPromo]}}}""",
            promoMenu("$emptyPromo,$notification"),
            promoMenu("$notification,$emptyPromo"),
            promoMenu("$emptyPromo,$emptyPromo"),
            promoMenu("""{"backgroundPromoRenderer":{},"messageRenderer":{"text":{"simpleText":"Other"}}}""")
        )) rejected(body)
        val owned = promoMenu().dropLast(1) + """, "diagnostic":{"notificationRenderer":{"notificationId":"foreign","shortMessage":{"simpleText":"Foreign"}},
          "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"foreign-next"}}}}}"""
        assertTrue(read(owned).ok)
        assertTrue(read(owned).value!!.notices.isEmpty())
        assertNull(read(owned).value!!.nextKey)
    }

    @Test fun observedEmptyMenuPresentationIsOptionalButDoesNotSupplyItemCapabilities() {
        for (parent in listOf(
            "",
            """, "style":"MULTI_PAGE_MENU_STYLE_TYPE_NOTIFICATIONS"""",
            """, "header":{"simpleMenuHeaderRenderer":{"title":{"simpleText":"Notifications"}}}""",
            """, "header":{"simpleMenuHeaderRenderer":{"title":{"simpleText":"Notifications"}},"clickTrackingParams":"header"},
                "style":"MULTI_PAGE_MENU_STYLE_TYPE_NOTIFICATIONS","trackingParams":"menu","commandMetadata":{}"""
        )) {
            val result = read(promoMenu(parentFields = parent))
            assertTrue(result.error.toString(), result.ok)
            assertTrue(result.value!!.notices.isEmpty())
            assertNull(result.value!!.nextKey)
        }
    }

    @Test fun emptyBannerCannotHideUnknownMenuContentsOrMalformedPresentation() {
        for (parent in listOf(
            """, "contents":[{"notificationRenderer":{"notificationId":"extra","shortMessage":{"simpleText":"Owned extra content"}}}]""",
            """, "items":[]""",
            """, "continuations":[]""",
            """, "futureMenuField":{}""",
            """, "style":"OTHER_MENU_STYLE"""",
            """, "style":true""",
            """, "style":null""",
            """, "header":null""",
            """, "header":"Notifications"""",
            """, "header":{}""",
            """, "header":{"simpleMenuHeaderRenderer":null}""",
            """, "header":{"simpleMenuHeaderRenderer":123}""",
            """, "header":{"multiPageMenuHeaderRenderer":{}}""",
            """, "header":{"simpleMenuHeaderRenderer":{},"futureHeaderRenderer":{}}""",
            """, "header":{"simpleMenuHeaderRenderer":{},"contents":[]}""",
            """, "header":{"simpleMenuHeaderRenderer":{},"trackingParams":false}""",
            """, "trackingParams":{}""",
            """, "clickTrackingParams":[]""",
            """, "commandMetadata":"invalid""""
        )) rejected(promoMenu(parentFields = parent))
    }

    @Test fun nestedKnownNamesCannotManufactureAnInboxOrContinuation() {
        for (body in listOf(
            """{"diagnostic":{"appendContinuationItemsAction":{"continuationItems":[]}}}""",
            """{"diagnostic":{"messageRenderer":{"text":{"simpleText":"Empty"}}}}""",
            """{"diagnostic":{"notificationRenderer":{"notificationId":"foreign","shortMessage":{"simpleText":"Foreign"}}}}""",
            """{"diagnostic":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"foreign"}}}}}"""
        )) rejected(body)
    }

    @Test fun malformedAndUnknownContentsNeverBecomeAnEmptyInbox() {
        for (body in listOf(
            """{"notificationSectionRenderer":{"items":123}}""",
            """{"multiPageMenuRenderer":{"sections":null}}""",
            """{"multiPageMenuNotificationSectionRenderer":{"items":[{"futureRenderer":{}}]}}""",
            """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[123]}}}}]}""",
            """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":"wrong"}}]}""",
            """{"sections":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":""}}}}]}""",
            """{"messageRenderer":{}}"""
        )) rejected(body)
    }

    @Test fun genuineEmptyMenusAndExplicitContinuationEndsRemainSuccessful() {
        for (body in listOf(
            """{"multiPageMenuRenderer":{"sections":[]}}""",
            """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[{"multiPageMenuNotificationSectionRenderer":{"items":[]}}]}}}}]}""",
            """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""",
            """{"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{"continuationItems":[]}}]}""",
            """{"messageRenderer":{"text":{"simpleText":"No notifications"}}}"""
        )) {
            val result = read(body, "own-next")
            assertTrue(body, result.ok)
            assertTrue(result.value!!.notices.isEmpty())
            assertNull(result.value!!.nextKey)
        }
    }

    @Test fun validMenuConsumesOnlyItsOwnEntriesAndCursor() {
        val body = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[
          {"multiPageMenuNotificationSectionRenderer":{"items":[{"notificationRenderer":{"notificationId":"own","shortMessage":{"simpleText":"Author: content"}}},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"own-next"}}}}]}}
        ]}}}}],"diagnostic":{"notificationRenderer":{"notificationId":"foreign","shortMessage":{"simpleText":"Foreign"}},
          "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"foreign-next"}}}}}"""
        val page = read(body).value!!
        assertEquals(listOf("own"), page.notices.map { it.id })
        assertEquals("own-next", page.nextKey)
    }

    @Test fun unseenCounterAcceptsOnlyOwnedNoncontradictoryResponseCommands() {
        for (body in listOf(
            """{"diagnostic":{"updateNotificationsUnseenCountAction":{"unseenCount":7}}}""",
            """{"actions":[{"updateNotificationsUnseenCountAction":123}]}""",
            """{"unseenCount":2,"actions":[{"updateNotificationsUnseenCountAction":{"unseenCount":3}}]}""",
            """{"actions":[{"updateNotificationsUnseenCountAction":{"unseenCount":-1}}]}"""
        )) assertEquals(body, "missing_notification_count", noticeCount(body).error!!.code)
        val zero = noticeCount("""{"unseenCount":0,"actions":[{"updateNotificationsUnseenCountAction":{"unseenCount":0}}]}""")
        assertEquals(0L, zero.value!!.unseenCount)
    }

    @Test fun publicTotalParserStillConsumesAnExplicitOfflineDocument() {
        val document = """{"sections":[{"notificationRenderer":{"notificationId":"notice","read":false,
          "shortMessage":{"simpleText":"Author: new upload"}}},{"continuationItemRenderer":{
          "continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}"""
        val page = noticePage(document)
        assertEquals("next", page.nextKey)
        val notice = page.notices.single()
        assertEquals("notice", notice.id)
        assertEquals("Author", notice.author)
        assertEquals("new upload", notice.title)
        assertEquals(false, notice.read)
        val unknown = noticePage("{}")
        assertTrue(unknown.notices.isEmpty())
        assertNull(unknown.nextKey)
        // Total document parsing remains separate from the network boundary's explicit PARSE failure.
        rejected("{}")
    }

    private fun sectionCursor(kind: String, token: String) =
        """{"$kind":{"continuation":"$token"}}"""

    private fun noticeSection(cursors: String, kind: String = "notificationSectionRenderer",
        items: String = """{"notificationRenderer":{"notificationId":"own","shortMessage":{"simpleText":"Own notice"}}}""") =
        """{"$kind":{"items":[$items],"continuations":$cursors}}"""

    @Test fun ownedSectionContinuationsReachEveryNextNetworkRequest() {
        for (sectionKind in listOf("notificationSectionRenderer", "multiPageMenuNotificationSectionRenderer")) {
            for (continuationKind in listOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData")) {
                val section = noticeSection("[${sectionCursor(continuationKind, "first-next")}]", sectionKind)
                val firstBody = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[$section]}}}}]}"""
                val secondSection = noticeSection("[${sectionCursor(continuationKind, "second-next")}]", sectionKind,
                    """{"notificationRenderer":{"notificationId":"second","shortMessage":{"simpleText":"Second notice"}}}""")
                val secondBody = """{"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{"continuationItems":[$secondSection]}}]}"""
                var calls = 0
                val http = object : HttpClient {
                    override fun execute(request: HttpRequest): HttpReply {
                        val uri = URI(request.url)
                        assertEquals("https", uri.scheme)
                        assertEquals("www.youtube.com", uri.host)
                        assertEquals("/youtubei/v1/notification/get_notification_menu", uri.path)
                        assertEquals(if (calls == 0) "prettyPrint=false" else null, uri.rawQuery)
                        assertEquals("SAPISID=fixture", request.headers["Cookie"])
                        val body = jsonObject(request.body)!!
                        assertEquals("WEB", body.path("context", "client")!!.text("clientName"))
                        return when (calls++) {
                            0 -> { assertNull(body.text("continuation")); HttpReply(200, firstBody) }
                            1 -> { assertEquals("first-next", body.text("continuation")); HttpReply(200, secondBody) }
                            2 -> { assertEquals("second-next", body.text("continuation")); HttpReply(200, noticeSection("[]", sectionKind, "")) }
                            else -> error("Unexpected request")
                        }
                    }
                }
                val first = loadNotices(context, http = http)
                assertTrue(first.error.toString(), first.ok)
                assertEquals(listOf("own"), first.value!!.notices.map { it.id })
                assertEquals("first-next", first.value!!.nextKey)
                assertEquals(noticePage(firstBody).nextKey, first.value!!.nextKey)
                val second = loadNotices(context, first.value!!.nextKey, http)
                assertTrue(second.error.toString(), second.ok)
                assertEquals(listOf("second"), second.value!!.notices.map { it.id })
                assertEquals("second-next", second.value!!.nextKey)
                val end = loadNotices(context, second.value!!.nextKey, http)
                assertTrue(end.error.toString(), end.ok)
                assertTrue(end.value!!.notices.isEmpty())
                assertNull(end.value!!.nextKey)
                assertEquals(3, calls)
            }
        }
    }

    @Test fun ownedSectionCursorsMustAgreeWithEveryOtherOwnedCursor() {
        val item = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"same"}}}}"""
        for (kind in listOf("notificationSectionRenderer", "multiPageMenuNotificationSectionRenderer")) {
            val cursor = sectionCursor("nextContinuationData", "same")
            val repeated = read(noticeSection("[$cursor,$cursor]", kind, item))
            assertTrue(repeated.error.toString(), repeated.ok)
            assertEquals("same", repeated.value!!.nextKey)
            val refresh = sectionCursor("timedContinuationData", "same")
            assertEquals("same", read(noticeSection("[$cursor,$refresh]", kind)).value!!.nextKey)
            rejected(noticeSection("[${sectionCursor("nextContinuationData", "other")}]", kind, item))
            rejected(noticeSection("[$cursor,${sectionCursor("timedContinuationData", "other")}]", kind))
            rejected(noticeSection("[$cursor]", kind, """{"futureRenderer":{}}"""))
        }
    }

    @Test fun malformedAndUnownedSectionCursorsNeverManufacturePagination() {
        for (cursors in listOf(
            "null", "{}", "123", "[null]", "[123]", "[{}]",
            """[{"futureContinuationData":{"continuation":"foreign"}}]""",
            """[{"nextContinuationData":null}]""",
            """[{"nextContinuationData":{"continuation":123}}]""",
            """[{"nextContinuationData":{"continuation":" "}}]""",
            """[{"nextContinuationData":{"continuation":"same"},"timedContinuationData":{"continuation":"same"}}]""",
            """[{"nextContinuationData":{"continuation":"same"},"futureField":{}}]""",
            """[{"nextContinuationData":{"continuation":"same"}},{"futureContinuationData":{"continuation":"foreign"}}]"""
        )) rejected(noticeSection(cursors))
        val owned = noticeSection("[]").dropLast(1) +
            ""","header":{"continuations":[{"nextContinuationData":{"continuation":"header-next"}}]},"diagnostic":{"continuations":[{"nextContinuationData":{"continuation":"foreign-next"}}]}}"""
        val page = read(owned)
        assertTrue(page.error.toString(), page.ok)
        assertEquals(listOf("own"), page.value!!.notices.map { it.id })
        assertNull(page.value!!.nextKey)
    }

}
