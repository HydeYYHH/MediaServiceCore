package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaylistMembershipTest {
    private val video = "jNQXAC9IVRw"
    private fun signed() = AccountSession(RequestContext(accountId = "fixture-owner", webSession = WebSession("SAPISID=fixture"), generation = 8))
    private fun choice(state: String?, flags: String = "", id: String = "PLlessons") =
        """{"playlistAddToOptionRenderer":{"playlistId":"$id","title":{"simpleText":"课程"}${state?.let { ",\"containsSelectedVideos\":\"$it\"" }.orEmpty()}$flags}}"""
    private fun choices(vararg values: String) = """{"contents":[{"addToPlaylistRenderer":{"playlists":[${values.joinToString(",")}]}}]}"""
    private fun listPage(ids: List<String>, next: String? = null, continued: Boolean = false): String {
        val cards = ids.map { """{"playlistVideoRenderer":{"videoId":"$it","title":{"simpleText":"Lesson"},"setVideoId":"set-$it"}}""" } +
            listOfNotNull(next?.let { """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}}""" })
        return if (continued) """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${cards.joinToString(",")}]}}]}"""
        else """{"contents":{"playlistVideoListRenderer":{"contents":[${cards.joinToString(",")}]}}}"""
    }
    private class Transport(private val reply: (HttpRequest, Int) -> HttpReply) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return reply(request, requests.lastIndex) }
        fun edits() = requests.count { it.url.contains("browse/edit_playlist") }
    }
    private fun edited(request: HttpRequest, action: String) {
        val body = jsonObject(request.body)!!
        assertEquals("PLlessons", body.text("playlistId"))
        val change = body.arr("actions").single() as Json.Obj
        assertEquals(action, change.text("action"))
        assertEquals(video, change.text(if (action == "ACTION_ADD_VIDEO") "addedVideoId" else "removedVideoId"))
        assertNull(change.text("setVideoId"))
    }

    @Test fun openedDirectoryPreservesUnknownStateAndHonorsAllExplicitEditingRestrictions() {
        val options = listOf(choice("ALL"), choice("NONE", id = "PLabsent"), choice("SOME", id = "PLunknown"),
            choice(null, ",\"canEdit\":false", "PLreadonly"), choice("NONE", ",\"isDisabled\":true", "PLdisabled"),
            choice("NONE", ",\"disabled\":true", "PLdisabled2"), choice("NONE", ",\"isSupported\":false", "PLunsupported"),
            choice("NONE", ",\"canEdit\":\"true\"", "PLmalformed"))
        val http = Transport { _, _ -> HttpReply(200, choices(*options.toTypedArray())) }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        assertEquals(listOf(PlaylistPresence.PRESENT, PlaylistPresence.ABSENT, PlaylistPresence.UNKNOWN), directory.entries.take(3).map { it.presence })
        assertEquals("PLlessons", directory.entries.first().playlistId); assertEquals("课程", directory.entries.first().title)
        assertTrue(directory.entries.take(3).all { it.editable }); assertTrue(directory.entries.drop(3).none { it.editable })
        for (entry in directory.entries.drop(3)) assertEquals("playlist_not_editable", directory.setContains(entry, true).error?.code)
        assertEquals(1, http.requests.size)
        assertEquals("PlaylistMembershipDirectory(entryCount=8)", directory.toString())
        assertEquals("PlaylistMembershipEntry(presence=PRESENT, editable=true)", directory.entries.first().toString())
        try { (directory.entries as MutableList<PlaylistMembershipEntry>).clear(); fail("directory is read-only") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun desiredStateUsesFreshReadAndAddRemoveAreEachSubmittedOnceWithBothConfirmationKinds() {
        var contains = false
        val http = Transport { request, _ ->
            assertTrue(request.headers.getValue("Authorization").startsWith("SAPISIDHASH "))
            if (request.url.contains("edit_playlist")) {
                edited(request, if (contains) "ACTION_REMOVE_VIDEO_BY_VIDEO_ID" else "ACTION_ADD_VIDEO")
                contains = !contains; HttpReply(200, """{"playlistId":"PLlessons","status":"SUCCESS"}""")
            } else if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice(if (contains) "ALL" else "NONE")))
            else HttpReply(200, listPage(if (contains) listOf(video) else emptyList()))
        }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val entry = directory.entries.single()
        val already = directory.setContains(entry, false).value!!
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, already.outcome)
        assertFalse(already.desired); assertFalse(already.submitted); assertFalse(already.serverConfirmed)
        assertNull(already.action); assertNull(already.writeError); assertNull(already.readError)
        assertEquals(PlaylistPresence.ABSENT, already.before); assertEquals(PlaylistPresence.ABSENT, already.after)
        val add = directory.setContains(entry, true).value!!
        assertTrue(add.desired); assertTrue(add.submitted); assertTrue(add.serverConfirmed)
        assertEquals(PlaylistPresence.ABSENT, add.before); assertEquals(PlaylistPresence.PRESENT, add.after)
        assertEquals(PlaylistMembershipOutcome.VERIFIED, add.outcome)
        assertEquals("PLlessons", add.action!!.playlistId); assertTrue(add.action!!.action.confirmed)
        assertNull(add.writeError); assertNull(add.readError)
        assertEquals("PlaylistMembershipUpdate(outcome=VERIFIED, submitted=true, serverConfirmed=true)", add.toString())
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, directory.setContains(entry, true).value!!.outcome)
        val remove = directory.setContains(entry, false).value!!
        assertEquals(PlaylistMembershipOutcome.VERIFIED, remove.outcome); assertEquals(PlaylistPresence.ABSENT, remove.after)
        assertEquals(2, http.edits())
    }

    @Test fun missingAcknowledgementNeverBecomesVerifiedEvenWithMatchingFreshReadback() {
        for (lostResponse in listOf(false, true)) {
            var contains = false
            val http = Transport { request, _ -> if (request.url.contains("edit_playlist")) {
                contains = true
                if (lostResponse) throw IOException("simulated lost response") else HttpReply(200, "{}")
            } else if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice(if (contains) "ALL" else "NONE")))
            else HttpReply(200, listPage(if (contains) listOf(video) else emptyList())) }
            val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
            val entry = directory.entries.single()
            val result = directory.setContains(entry, true).value!!
            assertTrue(result.submitted); assertFalse(result.serverConfirmed); assertEquals(PlaylistPresence.PRESENT, result.after)
            assertEquals(PlaylistMembershipOutcome.UNKNOWN, result.outcome); assertNull(result.readError)
            if (lostResponse) { assertNull(result.action); assertEquals(RequestFailure.TRANSPORT, result.writeError?.kind) }
            else { assertNotNull(result.action); assertNull(result.writeError) }
            assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, directory.setContains(entry, true).value!!.outcome)
            assertEquals(1, http.edits())
        }
    }

    @Test fun anUnresolvedWriteDoesNotAppendAgainWhenReadbackHasNotCaughtUp() {
        val http = Transport { request, _ -> if (request.url.contains("edit_playlist")) throw IOException("lost response")
            else if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice("NONE")))
            else HttpReply(200, listPage(emptyList())) }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val entry = directory.entries.single()
        val first = directory.setContains(entry, true).value!!
        assertEquals(PlaylistMembershipOutcome.UNKNOWN, first.outcome); assertTrue(first.submitted)
        for (desired in listOf(true, false)) {
            val next = directory.setContains(entry, desired).value!!
            assertEquals(PlaylistMembershipOutcome.UNKNOWN, next.outcome); assertFalse(next.submitted)
            assertEquals("membership_write_unresolved", next.readError?.code)
        }
        assertEquals(1, http.edits())
    }

    @Test fun unknownFlagsScanOnlyTheDiscoveredPlaylistAndFindTheVideoOnAContinuation() {
        var removed = false
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice(if (removed) "NONE" else "SOME")))
            request.url.contains("edit_playlist") -> { edited(request, "ACTION_REMOVE_VIDEO_BY_VIDEO_ID"); removed = true; HttpReply(200, """{"status":"SUCCESS"}""") }
            removed -> HttpReply(200, listPage(emptyList()))
            jsonObject(request.body)!!.text("continuation") == null -> {
                assertEquals("VLPLlessons", jsonObject(request.body)!!.text("browseId"))
                HttpReply(200, listPage(listOf("other-video"), "private-membership-next"))
            }
            else -> { assertEquals("private-membership-next", jsonObject(request.body)!!.text("continuation")); HttpReply(200, listPage(listOf(video), continued = true)) }
        } }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        assertEquals(PlaylistPresence.UNKNOWN, directory.entries.single().presence)
        val result = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(PlaylistPresence.PRESENT, result.before); assertEquals(PlaylistPresence.ABSENT, result.after)
        assertEquals(PlaylistMembershipOutcome.VERIFIED, result.outcome); assertEquals(1, http.edits())
        assertFalse(directory.toString().contains("private-membership"))
    }

    @Test fun unknownEmptyHeaderLoopOrBoundedScanNeverProvesAbsenceOrTriggersAnAppend() {
        for (mode in listOf("header", "loop", "limit", "error")) {
            val http = Transport { request, index -> when {
                request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice(null)))
                mode == "header" -> HttpReply(200, """{"header":{"playlistHeaderRenderer":{"title":{"simpleText":"Known title"}}}}""")
                mode == "error" -> HttpReply(503, "")
                else -> HttpReply(200, listPage(listOf("other-video"), if (mode == "loop") "repeated" else "page-$index", index > 2))
            } }
            val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
            val result = directory.setContains(directory.entries.single(), true).value!!
            assertEquals(mode, PlaylistPresence.UNKNOWN, result.before); assertEquals(PlaylistMembershipOutcome.UNKNOWN, result.outcome)
            assertFalse(result.submitted); assertNotNull(result.readError); assertEquals(0, http.edits())
            if (mode == "limit") { assertEquals("membership_scan_limit", result.readError?.code); assertEquals(10, http.requests.size) }
            if (mode == "loop") assertEquals("membership_cursor_loop", result.readError?.code)
        }
    }

    @Test fun missingChoiceStillRequiresACompleteExplicitCollectionBeforeAbsenceIsKnown() {
        var written = false
        val http = Transport { request, index -> when {
            index == 0 -> HttpReply(200, choices(choice("NONE")))
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices())
            request.url.contains("edit_playlist") -> { edited(request, "ACTION_ADD_VIDEO"); written = true; HttpReply(200, """{"status":"SUCCESS"}""") }
            else -> { assertEquals("VLPLlessons", jsonObject(request.body)!!.text("browseId")); HttpReply(200, listPage(if (written) listOf(video) else emptyList())) }
        } }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val result = directory.setContains(directory.entries.single(), true).value!!
        assertEquals(PlaylistMembershipOutcome.VERIFIED, result.outcome); assertEquals(1, http.edits())
    }

    @Test fun changedPermissionsForeignEntriesAndStaleDirectoriesFailClosed() {
        val session = signed()
        val http = Transport { _, index -> HttpReply(200, choices(choice("NONE", if (index >= 2) ",\"canEdit\":false" else ""))) }
        val service = PlaylistMembershipSession(session, http)
        val one = service.open(video).value!!; val two = service.open(video).value!!
        assertEquals("foreign_playlist_membership_entry", two.setContains(one.entries.single(), true).error?.code)
        assertEquals(2, http.requests.size)
        assertEquals("playlist_not_editable", one.setContains(one.entries.single(), true).error?.code)
        assertEquals(3, http.requests.size); assertEquals(0, http.edits())
        session.clear()
        assertEquals("session_changed", one.setContains(one.entries.single(), true).error?.code)
        assertEquals(3, http.requests.size)
    }

    @Test fun accountSwitchAtAnyIoBoundaryCancelsDeliveryAndStopsFurtherRequests() {
        for (switchAt in 1..5) {
            val session = signed()
            val http = Transport { request, index ->
                if (index == switchAt) session.clear()
                if (request.url.contains("edit_playlist")) HttpReply(200, """{"status":"SUCCESS"}""")
                else if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice("NONE")))
                else HttpReply(200, listPage(if (index > 3) listOf(video) else emptyList()))
            }
            val directory = PlaylistMembershipSession(session, http).open(video).value!!
            val result = directory.setContains(directory.entries.single(), true)
            assertNull(result.value); assertEquals("session_changed", result.error?.code)
            assertEquals(switchAt + 1, http.requests.size)
        }
    }

    @Test fun missingCredentialsInvalidInputAndMalformedDirectoriesCannotCreateCapabilities() {
        val http = Transport { _, _ -> fail("must reject before HTTP"); HttpReply(500, "") }
        assertEquals(RequestFailure.AUTH, PlaylistMembershipSession(AccountSession(), http).open(video).error?.kind)
        assertEquals(RequestFailure.INVALID, PlaylistMembershipSession(signed(), http).open("invalid/id").error?.kind)
        for (body in listOf("{}", choices(choice("NONE", id = "https://external.example/list")))) {
            val malformed = Transport { _, _ -> HttpReply(200, body) }
            assertEquals(RequestFailure.PARSE, PlaylistMembershipSession(signed(), malformed).open(video).error?.kind)
        }
    }

    @Test fun acknowledgedWriteWithFailedReadbackRemainsUnknown() {
        var written = false
        val http = Transport { request, _ -> when {
            request.url.contains("edit_playlist") -> { written = true; HttpReply(200, """{"status":"SUCCESS"}""") }
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
            written -> HttpReply(503, "")
            else -> HttpReply(200, listPage(emptyList()))
        } }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val result = directory.setContains(directory.entries.single(), true).value!!
        assertTrue(result.submitted); assertTrue(result.serverConfirmed); assertNull(result.writeError)
        assertEquals(RequestFailure.HTTP, result.readError?.kind); assertEquals(PlaylistPresence.UNKNOWN, result.after)
        assertEquals(PlaylistMembershipOutcome.UNKNOWN, result.outcome); assertEquals(1, http.edits())
    }

    @Test fun staleSaveDirectoryNeverOverridesAuthoritativePlaylistOrRepeatsAnAppend() {
        var contains = false
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
            request.url.contains("edit_playlist") -> {
                edited(request, if (contains) "ACTION_REMOVE_VIDEO_BY_VIDEO_ID" else "ACTION_ADD_VIDEO")
                contains = !contains
                HttpReply(200, """{"status":"SUCCESS"}""")
            }
            else -> HttpReply(200, listPage(if (contains) listOf(video) else emptyList()))
        } }
        val service = PlaylistMembershipSession(signed(), http)
        val first = service.open(video).value!!
        val add = first.setContains(first.entries.single(), true).value!!
        assertEquals(PlaylistMembershipOutcome.VERIFIED, add.outcome)
        assertEquals(PlaylistPresence.PRESENT, add.after)
        val second = service.open(video).value!!
        assertEquals(PlaylistPresence.ABSENT, second.entries.single().presence) // Display snapshot is stale.
        val noOp = second.setContains(second.entries.single(), true).value!!
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, noOp.outcome)
        assertFalse(noOp.submitted); assertEquals(1, http.edits())
        val remove = second.setContains(second.entries.single(), false).value!!
        assertEquals(PlaylistMembershipOutcome.VERIFIED, remove.outcome)
        assertEquals(PlaylistPresence.ABSENT, remove.after); assertEquals(2, http.edits())
    }

    @Test fun staleAllFlagCannotCauseRemovalOrDefeatACompleteEmptyPlaylistRead() {
        val http = Transport { request, _ ->
            if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice("ALL")))
            else { assertFalse(request.url.contains("edit_playlist")); HttpReply(200, listPage(emptyList())) }
        }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val result = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, result.outcome)
        assertEquals(PlaylistPresence.ABSENT, result.before); assertFalse(result.submitted)
        assertEquals(0, http.edits())
    }

    @Test fun aMissingOrUnknownContinuationCollectionCannotProveAbsence() {
        for (last in listOf("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"unknownVideoRenderer":{"videoId":"hidden"}}]}}]}""",
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"continuationItemRenderer":{}}]}}]}""")) {
            val http = Transport { request, _ -> when {
                request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
                jsonObject(request.body)!!.text("continuation") == null -> HttpReply(200, listPage(listOf("other-video"), "next-page"))
                else -> HttpReply(200, last)
            } }
            val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
            val result = directory.setContains(directory.entries.single(), true).value!!
            assertEquals(PlaylistPresence.UNKNOWN, result.before); assertFalse(result.submitted)
            assertNotNull(result.readError); assertEquals(0, http.edits())
        }
    }

    @Test fun absenceRequiresTheExplicitContinuationToEndEvenWhenSaveDirectorySaysAll() {
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("ALL")))
            jsonObject(request.body)!!.text("continuation") == null -> HttpReply(200, listPage(listOf("other-video"), "final-page"))
            else -> {
                assertEquals("final-page", jsonObject(request.body)!!.text("continuation"))
                HttpReply(200, listPage(emptyList(), continued = true))
            }
        } }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val update = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(PlaylistPresence.ABSENT, update.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, update.outcome)
        assertFalse(update.submitted); assertEquals(4, http.requests.size); assertEquals(0, http.edits())
    }

    @Test fun aCollectionExplicitlyNamingAnotherPlaylistCannotEstablishMembership() {
        val response = """{"contents":{"playlistVideoListRenderer":{"playlistId":"PLoutside","contents":[{"playlistVideoRenderer":{"videoId":"$video"}}]}}}"""
        val http = Transport { request, _ -> if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice("ALL")))
            else HttpReply(200, response) }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val update = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(PlaylistPresence.UNKNOWN, update.before); assertFalse(update.submitted)
        // The typed playlist reader now rejects the explicitly foreign collection before membership assessment.
        assertEquals(RequestFailure.PARSE, update.readError?.kind)
        assertEquals("unknown_playlist_page", update.readError?.code); assertEquals(0, http.edits())
    }

    @Test fun recommendationCardsOutsideTheCanonicalCollectionCannotEstablishPresence() {
        val response = """{"contents":{"playlistVideoListRenderer":{"contents":[]}},"secondaryContents":{"playlistVideoListRenderer":{"contents":[{"playlistVideoRenderer":{"videoId":"$video"}}]}}}"""
        val http = Transport { request, _ -> if (request.url.contains("get_add_to_playlist")) HttpReply(200, choices(choice("ALL")))
            else HttpReply(200, response) }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val result = directory.setContains(directory.entries.single(), false).value!!
        assertEquals(PlaylistPresence.ABSENT, result.before)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, result.outcome); assertEquals(0, http.edits())
    }

    @Test fun separateDirectoriesShareUnresolvedWriteProtectionWithoutHoldingTheAccountLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = signed()
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
            request.url.contains("edit_playlist") -> {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                throw IOException("response lost before state becomes observable")
            }
            else -> HttpReply(200, listPage(emptyList()))
        } }
        val service = PlaylistMembershipSession(session, http)
        val first = service.open(video).value!!; val second = service.open(video).value!!
        val pool = Executors.newFixedThreadPool(2)
        try {
            val one = pool.submit<PlaylistMembershipUpdate> { first.setContains(first.entries.single(), true).value!! }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val two = pool.submit<PlaylistMembershipUpdate> { second.setContains(second.entries.single(), true).value!! }
            // Snapshot must remain usable while the first remote operation is in flight.
            assertTrue(session.snapshot().signedIn)
            release.countDown()
            assertTrue(one.get(5, TimeUnit.SECONDS).submitted)
            val blocked = two.get(5, TimeUnit.SECONDS)
            assertFalse(blocked.submitted); assertEquals("membership_write_unresolved", blocked.readError?.code)
            assertEquals(1, http.edits())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun concurrentDirectoriesReadTheCommittedTargetBeforeTheirOwnDecision() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var contains = false
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
            request.url.contains("edit_playlist") -> {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                contains = true
                HttpReply(200, """{"status":"SUCCESS"}""")
            }
            else -> HttpReply(200, listPage(if (contains) listOf(video) else emptyList()))
        } }
        val service = PlaylistMembershipSession(signed(), http)
        val first = service.open(video).value!!; val second = service.open(video).value!!
        val pool = Executors.newFixedThreadPool(2)
        try {
            val one = pool.submit<PlaylistMembershipUpdate> { first.setContains(first.entries.single(), true).value!! }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val two = pool.submit<PlaylistMembershipUpdate> { second.setContains(second.entries.single(), true).value!! }
            release.countDown()
            assertEquals(PlaylistMembershipOutcome.VERIFIED, one.get(5, TimeUnit.SECONDS).outcome)
            val noOp = two.get(5, TimeUnit.SECONDS)
            assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, noOp.outcome)
            assertFalse(noOp.submitted); assertEquals(1, http.edits())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun fullPendingLedgerRefusesNewWritesWithoutEvictionAndRotatesOnlyForAnAcceptedGeneration() {
        val profiles = """{"items":[{"accountItem":{"accountId":"owner","accountName":{"simpleText":"Owner"},"isSelected":true}},{"accountItem":{"accountId":"brand","accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"brand"}}]}}}}]}"""
        val accountHttp = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, profiles) }
        val session = AccountSession().also { assertTrue(it.install(WebSession("SAPISID=fixture"), accountHttp).ok) }
        val options = (0..256).map { choice("NONE", id = "PLpending$it") }.toTypedArray()
        var directoryReads = 0
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, if (directoryReads++ == 0) choices(*options) else choices())
            request.url.contains("edit_playlist") -> throw IOException("submitted operation remains unobservable")
            else -> HttpReply(200, listPage(emptyList()))
        } }
        val service = PlaylistMembershipSession(session, http)
        val directory = service.open(video).value!!
        for (entry in directory.entries.take(256)) assertTrue(directory.setContains(entry, true).value!!.submitted)
        assertEquals(256, http.edits())
        val full = directory.setContains(directory.entries.last(), true)
        assertEquals(RequestFailure.UNSUPPORTED, full.error?.kind); assertEquals("membership_pending_limit", full.error?.code)
        val retained = directory.setContains(directory.entries.first(), true).value!!
        assertFalse(retained.submitted); assertEquals("membership_write_unresolved", retained.readError?.code)
        assertEquals(256, http.edits())
        assertTrue(session.select("brand"))
        directoryReads = 0
        val changed = service.open(video).value!!
        assertTrue(changed.setContains(changed.entries.last(), true).value!!.submitted)
        val requestsBeforeStaleCall = http.requests.size
        assertEquals(RequestFailure.CANCELLED, directory.setContains(directory.entries.first(), true).error?.kind)
        assertEquals(requestsBeforeStaleCall, http.requests.size)
        val retainedNewGeneration = changed.setContains(changed.entries.last(), true).value!!
        assertFalse(retainedNewGeneration.submitted)
        assertEquals("membership_write_unresolved", retainedNewGeneration.readError?.code)
        assertEquals(257, http.edits())
    }

    @Test fun verifiedIntentSurvivesRegressedReadbackAndReopenedDirectoriesWithoutDuplicateOrFalseNoop() {
        var remoteContains = false
        var stale = false
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
            request.url.contains("edit_playlist") -> {
                edited(request, if (remoteContains) "ACTION_REMOVE_VIDEO_BY_VIDEO_ID" else "ACTION_ADD_VIDEO")
                remoteContains = !remoteContains
                HttpReply(200, """{"status":"SUCCESS"}""")
            }
            else -> HttpReply(200, listPage(if (remoteContains != stale) listOf(video) else emptyList()))
        } }
        val service = PlaylistMembershipSession(signed(), http)
        val first = service.open(video).value!!
        assertEquals(PlaylistMembershipOutcome.VERIFIED, first.setContains(first.entries.single(), true).value!!.outcome)
        stale = true
        val reopened = service.open(video).value!!
        for (directory in listOf(first, reopened)) for (desired in listOf(true, false)) {
            val blocked = directory.setContains(directory.entries.single(), desired).value!!
            assertEquals(PlaylistPresence.ABSENT, blocked.before)
            assertEquals(PlaylistMembershipOutcome.UNKNOWN, blocked.outcome)
            assertFalse(blocked.submitted)
            assertEquals("membership_readback_regressed", blocked.readError?.code)
        }
        assertEquals(1, http.edits())

        stale = false
        val removed = reopened.setContains(reopened.entries.single(), false).value!!
        assertEquals(PlaylistMembershipOutcome.VERIFIED, removed.outcome)
        assertEquals(PlaylistPresence.PRESENT, removed.before)
        assertEquals(PlaylistPresence.ABSENT, removed.after)
        assertTrue(removed.submitted)
        assertEquals(2, http.edits())
        stale = true
        for (desired in listOf(false, true)) {
            val blocked = first.setContains(first.entries.single(), desired).value!!
            assertEquals(PlaylistPresence.PRESENT, blocked.before)
            assertEquals(PlaylistMembershipOutcome.UNKNOWN, blocked.outcome)
            assertEquals("membership_readback_regressed", blocked.readError?.code)
            assertFalse(blocked.submitted)
        }
        stale = false
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, first.setContains(first.entries.single(), false).value!!.outcome)
        assertEquals(2, http.edits())
    }

    @Test fun unconfirmedIntentIsRetainedAfterAnInterveningMatchingRead() {
        var visible = false
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, choices(choice("NONE")))
            request.url.contains("edit_playlist") -> { visible = true; HttpReply(200, "{}") }
            else -> HttpReply(200, listPage(if (visible) listOf(video) else emptyList()))
        } }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        val entry = directory.entries.single()
        val unconfirmed = directory.setContains(entry, true).value!!
        assertEquals(PlaylistMembershipOutcome.UNKNOWN, unconfirmed.outcome)
        assertFalse(unconfirmed.serverConfirmed)
        assertEquals(PlaylistPresence.PRESENT, unconfirmed.after)
        assertEquals(PlaylistMembershipOutcome.ALREADY_MATCHED, directory.setContains(entry, true).value!!.outcome)
        visible = false
        for (desired in listOf(true, false)) {
            val blocked = directory.setContains(entry, desired).value!!
            assertEquals(PlaylistMembershipOutcome.UNKNOWN, blocked.outcome)
            assertEquals("membership_write_unresolved", blocked.readError?.code)
            assertFalse(blocked.submitted)
        }
        assertEquals(1, http.edits())
    }

    @Test fun verifiedTargetsRemainBoundedButAnExistingIntentCanChangeAtTheLimit() {
        val options = (0..256).map { choice("NONE", id = "PLintent$it") }.toTypedArray()
        val present = mutableSetOf<String>()
        var directoryReads = 0
        val http = Transport { request, _ -> when {
            request.url.contains("get_add_to_playlist") -> HttpReply(200, if (directoryReads++ == 0) choices(*options) else choices())
            request.url.contains("edit_playlist") -> {
                val body = jsonObject(request.body)!!
                val id = body.text("playlistId")!!
                val change = body.arr("actions").single() as Json.Obj
                if (change.text("action") == "ACTION_ADD_VIDEO") present.add(id) else present.remove(id)
                HttpReply(200, """{"status":"SUCCESS"}""")
            }
            else -> {
                val id = jsonObject(request.body)!!.text("browseId")!!.removePrefix("VL")
                HttpReply(200, listPage(if (id in present) listOf(video) else emptyList()))
            }
        } }
        val directory = PlaylistMembershipSession(signed(), http).open(video).value!!
        for (entry in directory.entries.take(256))
            assertEquals(PlaylistMembershipOutcome.VERIFIED, directory.setContains(entry, true).value!!.outcome)
        assertEquals("membership_pending_limit", directory.setContains(directory.entries.last(), true).error?.code)
        val first = directory.entries.first()
        assertEquals(PlaylistMembershipOutcome.VERIFIED, directory.setContains(first, false).value!!.outcome)
        assertEquals(PlaylistMembershipOutcome.VERIFIED, directory.setContains(first, true).value!!.outcome)
        assertEquals("membership_pending_limit", directory.setContains(directory.entries.last(), true).error?.code)
        assertEquals(258, http.edits())
    }
}
