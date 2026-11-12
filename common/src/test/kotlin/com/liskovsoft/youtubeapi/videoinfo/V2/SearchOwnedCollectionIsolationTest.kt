package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchOwnedCollectionIsolationTest {
    private fun video(id: String, extra: String = "") = """{"videoRenderer":{"videoId":"$id"$extra}}"""
    private fun cursor(token: String, extra: String = "", endpointExtra: String = "") = """{"continuationItemRenderer":{
        "continuationEndpoint":{$endpointExtra"continuationCommand":{"token":"$token"$extra}}}}"""
    private fun message(text: String) = """{"messageRenderer":{"text":{"simpleText":"$text"}}}"""
    private fun shelf(id: String, token: String) = """{"shelfRenderer":{"title":"Owned shelf","content":{
        "horizontalListRenderer":{"items":[${video(id)},${cursor(token)}]}}}}"""
    private fun page(slots: String, extra: String = "", sectionExtra: String = "") = """{
        "contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
        "contents":[{"itemSectionRenderer":{"contents":[$slots]$sectionExtra}}]}}}}$extra}"""
    private fun read(body: String, continuation: Boolean = false): ReadResult<SearchPage> {
        var count = 0
        return loadSearch(if (continuation) null else "fixture", continuation = if (continuation) "issued" else null,
            http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { count++; return HttpReply(200, body) } })
            .also { assertEquals(1, count) }
    }
    private fun body(slots: String, target: String? = "\"search-feed\"", kind: String = "appendContinuationItemsAction",
        payloadExtra: String = "", wrapperExtra: String = "", rootExtra: String = ""): String {
        val declaration = target?.let { "\"targetId\":$it," } ?: ""
        return """{"onResponseReceivedCommands":[{$wrapperExtra"$kind":{$declaration"continuationItems":[$slots]$payloadExtra}}]$rootExtra}"""
    }
    private fun rejectedBody(body: String) {
        assertEquals("unknown_search_page", read(body, true).error?.code)
        val pure = searchPage(body)
        assertTrue(pure.items.isEmpty()); assertTrue(pure.groups.isEmpty()); assertTrue(pure.messages.isEmpty())
        assertTrue(pure.actions.isEmpty()); assertNull(pure.nextKey)
    }
    private fun filter() = """{"searchFilterGroupRenderer":{"title":"Type","filters":[{"searchFilterRenderer":{
        "label":"Video","navigationEndpoint":{"searchEndpoint":{"params":"shadow-params"}}}}]}}"""
    private fun injected(node: Json, path: List<String>, extra: Json.Obj): Json = if (path.isEmpty()) Json.Obj((node as Json.Obj).fields + extra.fields) else when (node) {
        is Json.Obj -> Json.Obj(node.fields + (path.first() to injected(node.fields.getValue(path.first()), path.drop(1), extra)))
        is Json.Arr -> Json.Arr(node.items.mapIndexed { index, child -> if (index == path.first().toInt()) injected(child, path.drop(1), extra) else child })
        else -> error("fixture path")
    }

    @Test fun fullSearchReadsOnlyItsSelectedPrimaryDespiteUnownedCardsGroupsMessagesAndCursors() {
        for (field in listOf("responseContext", "metadata", "sidebar", "secondaryContents", "header", "footer", "topbar")) {
            val shadow = """{"contents":[${video("shadow")},${shelf("shadow-shelf", "shadow-group")},${message("Shadow")},${cursor("shadow-page")}]}"""
            // header has an independent action grammar; use a known display-only header there.
            val extra = if (field == "header") ",\"header\":{\"searchHeaderRenderer\":{\"title\":\"Header\",\"text\":$shadow}}"
                else ",\"$field\":$shadow"
            val loaded = read(page(video("own") + "," + message("Owned") + "," + cursor("own-next"), extra)).value!!
            assertEquals(listOf("own"), loaded.items.map { it.id })
            assertEquals(listOf("own"), loaded.groups.single().items.map { it.id })
            assertEquals(listOf("Owned"), loaded.messages); assertEquals("own-next", loaded.nextKey)
        }
    }

    @Test fun unownedSameIdentityCannotTakeCapabilitiesOrFillDisplayFieldsInEitherSourceOrder() {
        val shadow = video("same", ",\"title\":\"Shadow title\",\"ownerText\":{\"simpleText\":\"Shadow owner\"},\"menu\":{\"menuRenderer\":{\"items\":[{\"menuServiceItemRenderer\":{\"serviceEndpoint\":{\"feedbackEndpoint\":{\"feedbackToken\":\"shadow-command\"}}}}]}}")
        val primary = jsonObject(page(video("same")))!!
        for (first in listOf(true, false)) {
            val fields = if (first) linkedMapOf("responseContext" to jsonObject(shadow)!!) + primary.fields else primary.fields + ("responseContext" to jsonObject(shadow)!!)
            val item = read(Json.Obj(fields).encode()).value!!.items.single()
            assertNull(item.title); assertNull(item.author); assertTrue(item.actions.isEmpty())
        }
    }

    @Test fun cardOwnedMetadataAndPreviewListsCannotSupplyPageCursorOrMessageOrExtraCard() {
        val nested = video("own", ",\"metadata\":{\"preview\":{\"contents\":[${video("preview")},${cursor("preview")},${message("Preview")}]}}")
        val loaded = read(page(nested)).value!!
        assertEquals(listOf("own"), loaded.items.map { it.id }); assertNull(loaded.nextKey); assertTrue(loaded.messages.isEmpty())
    }

    @Test fun explicitEmptyAndMetadataOnlyContinuationIgnoreAllShadowContent() {
        val shadow = """{"contents":[${video("shadow")},${cursor("shadow")},${message("Shadow")}]}"""
        val body = page("", ",\"responseContext\":$shadow")
        val empty = read(body).value!!
        assertTrue(empty.items.isEmpty()); assertTrue(empty.groups.isEmpty()); assertNull(empty.nextKey); assertTrue(empty.messages.isEmpty())
        val end = """{"responseContext":$shadow,"trackingParams":"tracking"}"""
        assertTrue(read(end, true).value!!.items.isEmpty()); assertEquals("unknown_search_page", read(end).error?.code)
        assertNull(searchPage(end).nextKey)
    }

    @Test fun onlyCurrentOwnOuterAndSectionMarkersCountAndRepeatedValuesRemainAmbiguous() {
        for (unowned in listOf("same", "different")) {
            val loaded = read(page(video("own") + "," + cursor("same"), ",\"responseContext\":${cursor(unowned)}")).value!!
            assertEquals("same", loaded.nextKey)
        }
        val ambiguous = page(video("own") + "," + cursor("same") + "," + cursor("same"))
        assertEquals("unknown_search_page", read(ambiguous).error?.code); assertTrue(searchPage(ambiguous).items.isEmpty())
    }

    @Test fun foreignOrWrongTypedOwnCursorDeclarationsRejectInsteadOfPretendingTerminal() {
        val payloads = listOf(
            cursor("own", ",\"request\":\"CONTINUATION_REQUEST_TYPE_BROWSE\""),
            cursor("own", ",\"request\":\"UNKNOWN\""), cursor("own", ",\"request\":null"),
            cursor("own", endpointExtra = "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/browse\"}},"),
            cursor("own", endpointExtra = "\"commandMetadata\":null,"),
            cursor("own", endpointExtra = "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":false}},"),
            cursor("own", endpointExtra = "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"https://foreign.test/youtubei/v1/search\"}},"),
            cursor("own", endpointExtra = "\"commandMetadata\":{\"webCommandMetadata\":{\"sendPost\":false}},")
        )
        for (payload in payloads) {
            val body = page(video("own") + "," + payload)
            assertEquals("unknown_search_page", read(body).error?.code)
            assertTrue(searchPage(body).items.isEmpty()); assertNull(searchPage(body).nextKey)
            val unowned = read(page(video("own"), ",\"responseContext\":$payload")).value!!
            assertEquals(listOf("own"), unowned.items.map { it.id }); assertNull(unowned.nextKey)
        }
    }

    @Test fun explicitSearchDeclarationAndExistingCursorDisplayChildKeepOnlyTheDirectCursor() {
        val payload = cursor("own", ",\"request\":\"CONTINUATION_REQUEST_TYPE_SEARCH\"",
            "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/search\",\"sendPost\":true}},")
        assertEquals("own", read(page(video("own") + "," + payload)).value!!.nextKey)
    }

    @Test fun ownGroupAndOwnPageTokensKeepSeparateSourcesEvenWhenEqual() {
        for (outer in listOf("group", "page")) {
            val loaded = read(page(shelf("in-group", "group") + "," + cursor(outer))).value!!
            assertEquals(outer, loaded.nextKey); assertEquals("group", loaded.groups.single().nextKey)
        }
        val only = read(page(shelf("in-group", "group"))).value!!
        assertNull(only.nextKey); assertEquals("group", only.groups.single().nextKey)
    }

    @Test fun unownedGroupPresentationCannotLendItemsMessagesCursorsOrCommands() {
        val value = jsonObject(shelf("own", "group"))!!.obj("shelfRenderer")!!
        val shadow = jsonObject("""{"contents":[${video("shadow")},${cursor("shadow")},${message("Shadow")},
            {"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"FEshadow"}}}}]}""")!!
        val wrapped = Json.Obj(mapOf("shelfRenderer" to Json.Obj(value.fields + ("footer" to shadow)))).encode()
        val loaded = read(page(wrapped)).value!!
        assertEquals(listOf("own"), loaded.items.map { it.id }); assertTrue(loaded.messages.isEmpty())
        assertEquals("group", loaded.groups.single().nextKey); assertTrue(loaded.groups.single().actions.isEmpty())
    }

    @Test fun fullWebRootAndMetadataFilterGroupsCannotMintFilters() {
        val group = jsonObject(filter())!!.obj("searchFilterGroupRenderer")!!
        for (extra in listOf(",\"searchFilterGroupRenderer\":${group.encode()}", ",\"responseContext\":${filter()}", ",\"sidebar\":${filter()}")) {
            val body = page(video("own"), extra)
            assertTrue(read(body).value!!.filters.isEmpty()); assertTrue(searchFilters(body).isEmpty())
        }
        assertEquals("shadow-params", searchFilters(filter()).single().params)
        assertEquals("shadow-params", searchPage("""{"contents":[${video("own")},${filter()}]}""").filters.single().params)
    }

    @Test fun ownedPopupFilterPreservesParamsButCannotPromoteItsNestedButton() {
        val extracted = """{"contents":[${video("own")}],"searchFilterGroupRenderer":${jsonObject(filter())!!.obj("searchFilterGroupRenderer")!!.encode()}}"""
        val body = searchPrimaryWithFilterFixture(extracted)
        val loaded = read(body).value!!
        assertEquals("shadow-params", loaded.filters.single().params); assertTrue(loaded.actions.isEmpty())
        assertEquals(loaded, searchPage(body))
    }

    @Test fun selectedBodyCollectionExcludesResponseMetadataAndRejectsParallelBodyOwners() {
        val body = """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[${video("own")},${cursor("own")}]}}],
            "responseContext":{"contents":[${video("shadow")},${cursor("shadow")}]}}"""
        assertEquals(listOf("own"), read(body, true).value!!.items.map { it.id }); assertEquals("own", read(body, true).value!!.nextKey)
        val parsed = jsonObject(body)!!
        val command = parsed.arr("onResponseReceivedCommands").single()
        val parallel = Json.Obj(parsed.fields + ("onResponseReceivedCommands" to Json.Arr(listOf(command, command)))).encode()
        assertEquals("unknown_search_page", read(parallel, true).error?.code)
    }

    @Test fun explicitLegacyFragmentsStayFiniteAndUnknownAncestorsNeverActivateFallback() {
        assertEquals("own", searchPage(video("own")).items.single().id)
        assertEquals("own", searchPage("""{"source":"extracted fixture","items":[${video("own")}]}""").items.single().id)
        assertEquals("page", searchPage("""{"contents":[${video("own")},${cursor("page")}],"header":${cursor("header")},"secondaryContents":${video("shadow")}}""").nextKey)
        for (body in listOf("""{"unknown":{"contents":[${video("shadow")},${cursor("shadow")}]}}""", """{"responseContext":${video("shadow")}}""")) {
            assertTrue(searchPage(body).items.isEmpty()); assertNull(searchPage(body).nextKey)
        }
    }

    @Test fun officialCardIsOpaqueBesideOrganicResultsAndCannotEstablishItsOwnPage() {
        val card = """{"officialCardViewModel":{"contents":[{"horizontalShelfViewModel":{"contents":[${video("unproven")}]}}]}}"""
        val loaded = read(page(video("own") + "," + card)).value!!
        assertEquals(listOf("own"), loaded.items.map { it.id })
        assertEquals("unknown_search_page", read(page(card)).error?.code)
        assertTrue(searchPage(page(card)).items.isEmpty())
    }

    @Test fun explicitLegacyContainerKeepsOnlyItsAcceptedSlotAction() {
        val button = """{"buttonRenderer":{"title":"Own button","navigationEndpoint":{"browseEndpoint":{"browseId":"FEown"}}}}"""
        val body = """{"sectionListRenderer":{"contents":[$button],"metadata":{"content":$button}}}"""
        assertEquals(1, searchPage(body).actions.size)
        val unknown = """{"sectionListRenderer":{"contents":[{"unknown":{"content":$button}}]}}"""
        assertTrue(searchPage(unknown).actions.isEmpty())
    }

    @Test fun ownPageCursorCannotDiscardForeignOrWrongTypedAncestorDeclarations() {
        val body = page("""{"gridRenderer":{"items":[${video("own")},${cursor("own")}]}}""")
        val prefixes = listOf(emptyList(), listOf("contents"), listOf("contents", "twoColumnSearchResultsRenderer"),
            listOf("contents", "twoColumnSearchResultsRenderer", "primaryContents"),
            listOf("contents", "twoColumnSearchResultsRenderer", "primaryContents", "sectionListRenderer"),
            listOf("contents", "twoColumnSearchResultsRenderer", "primaryContents", "sectionListRenderer", "contents", "0", "itemSectionRenderer"),
            listOf("contents", "twoColumnSearchResultsRenderer", "primaryContents", "sectionListRenderer", "contents", "0", "itemSectionRenderer", "contents", "0", "gridRenderer"))
        for (prefix in prefixes) for (metadata in listOf("""{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}}}""", """{"commandMetadata":null}""")) {
            val bad = injected(jsonObject(body)!!, prefix, jsonObject(metadata)!!).encode()
            assertEquals("unknown_search_page", read(bad).error?.code); assertTrue(searchPage(bad).items.isEmpty())
        }
    }

    @Test fun declaredUnknownTargetsAndInitialBodyMixturesNeverBecomeOwnedSearchCollections() {
        for (target in listOf("\"comments-section\"", "\"result\"", "null", "false")) {
            val body = """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"targetId":$target,"continuationItems":[${video("own")},${cursor("own")}]}}]}"""
            assertEquals("unknown_search_page", read(body, true).error?.code); assertTrue(searchPage(body).items.isEmpty())
        }
        val extra = """, "onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[${video("parallel")}]}}]"""
        assertEquals("unknown_search_page", read(page(video("own"), extra)).error?.code)
        val playlist = """{"continuationContents":{"playlistVideoListContinuation":{"contents":[${video("foreign")}]}}}"""
        assertEquals("unknown_search_page", read(playlist, true).error?.code); assertTrue(searchPage(playlist).items.isEmpty())
    }

    @Test fun cursorCommandCarriersCannotCompeteButItsButtonDisplayStillCannotIssueAnAction() {
        val competing = cursor("own", endpointExtra = "\"browseEndpoint\":{\"browseId\":\"FEforeign\"},")
        assertEquals("unknown_search_page", read(page(video("own") + "," + competing)).error?.code)
        val endpoint = jsonObject(cursor("own"))!!
        val bad = injected(endpoint, listOf("continuationItemRenderer"), jsonObject("""{"navigationEndpoint":{"browseEndpoint":{"browseId":"FEforeign"}}}""")!!).encode()
        assertEquals("unknown_search_page", read(page(video("own") + "," + bad)).error?.code)
        val withButton = injected(endpoint, listOf("continuationItemRenderer"), jsonObject("""{"button":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"FEdisplay"}}}}}""")!!).encode()
        val good = read(page(video("own") + "," + withButton)).value!!
        assertEquals("own", good.nextKey); assertTrue(good.actions.isEmpty())
    }

    @Test fun filterPopupAndContextualAncestorsCannotDiscardForeignOrMalformedDeclarations() {
        val extracted = """{"contents":[${video("own")}],"searchFilterGroupRenderer":${jsonObject(filter())!!.obj("searchFilterGroupRenderer")!!.encode()}}"""
        val body = searchPrimaryWithFilterFixture(extracted)
        val button = listOf("header", "searchHeaderRenderer", "searchFilterButton", "buttonRenderer")
        val popup = button + listOf("command", "openPopupAction")
        for (prefix in listOf(popup, popup + "popup", popup + listOf("popup", "menuRenderer"))) {
            for (declaration in listOf("""{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}}}""", """{"commandMetadata":false}""")) {
                val bad = injected(jsonObject(body)!!, prefix, jsonObject(declaration)!!).encode()
                assertTrue(read(bad).value!!.filters.isEmpty()); assertTrue(searchFilters(bad).isEmpty())
            }
        }
        val root = jsonObject(body)!!
        val node = root.path(*button.toTypedArray())!!
        val contextual = Json.Obj(mapOf("rendererContext" to Json.Obj(mapOf("commandContext" to Json.Obj(mapOf("onTap" to Json.Obj(mapOf("innertubeCommand" to node.obj("command")!!))))))))
        val replaced = injected(root, button, Json.Obj(mapOf("command" to Json.Null))).encode()
        // A competing/wrong-typed legacy carrier must not allow contextual fallthrough.
        val invalid = injected(jsonObject(replaced)!!, button, contextual).encode()
        assertTrue(searchPage(invalid).filters.isEmpty())
    }

    @Test fun uniqueOuterScopeRetainsR18PriorityButSameScopeDeclarationsRemainAmbiguous() {
        val section = """{"itemSectionRenderer":{"contents":[${video("own")},${cursor("child-one")},${cursor("child-two")}]}}"""
        val body = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
            "continuations":[{"nextContinuationData":{"continuation":"outer"}}],"contents":[$section]}}}}}"""
        assertEquals("outer", read(body).value!!.nextKey)
        val root = jsonObject(body)!!
        val path = listOf("contents", "twoColumnSearchResultsRenderer", "primaryContents", "sectionListRenderer")
        val ambiguous = injected(root, path, jsonObject("""{"contents":[$section,${cursor("outer")}]}""")!!).encode()
        assertEquals("unknown_search_page", read(ambiguous).error?.code); assertTrue(searchPage(ambiguous).items.isEmpty())
    }

    @Test fun filterItemCannotChooseNavigationAcrossACompetingDeclaredCommandCarrier() {
        val extracted = """{"contents":[${video("own")}],"searchFilterGroupRenderer":${jsonObject(filter())!!.obj("searchFilterGroupRenderer")!!.encode()}}"""
        val body = searchPrimaryWithFilterFixture(extracted)
        val path = listOf("header", "searchHeaderRenderer", "searchFilterButton", "buttonRenderer", "command", "openPopupAction", "popup",
            "menuRenderer", "items", "0", "searchFilterGroupRenderer", "filters", "0", "searchFilterRenderer")
        for (carrier in listOf("serviceEndpoint", "onTap", "command", "defaultServiceEndpoint", "rendererContext")) for (payload in listOf(Json.Null, Json.Obj(emptyMap()))) {
            val bad = injected(jsonObject(body)!!, path, Json.Obj(mapOf(carrier to payload))).encode()
            assertTrue(read(bad).value!!.filters.isEmpty()); assertTrue(searchFilters(bad).isEmpty())
        }
        val command = path + "navigationEndpoint"
        val competing = injected(jsonObject(body)!!, command, jsonObject("""{"innertubeCommand":{}}""")!!).encode()
        assertTrue(read(competing).value!!.filters.isEmpty())
    }

    @Test fun observedAppendBodyTargetKeepsOnlyItsOwnedCollectionAcrossPureAndNetworkReaders() {
        val section = """{"itemSectionRenderer":{"contents":[${video("own")},${message("Owned")}]}}"""
        for (slot in listOf("", ",\"slot\":\"RELOAD_CONTINUATION_SLOT_BODY\"")) {
            val reply = body(section + "," + cursor("own-next"), payloadExtra = slot)
            val loaded = read(reply, true).value!!
            assertEquals(listOf("own"), loaded.items.map { it.id }); assertEquals("own-next", loaded.nextKey)
            assertEquals(listOf("Owned"), loaded.messages); assertEquals(listOf("own"), loaded.groups.single().items.map { it.id })
            assertEquals(loaded, searchPage(reply))
        }
    }

    @Test fun absentAppendTargetRetainsLegacySearchCursorPayloadAndIndependentActions() {
        val direct = cursor("unchanged", ",\"request\":\"CONTINUATION_REQUEST_TYPE_SEARCH\"",
            "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/search\",\"sendPost\":true}},")
        val reply = body(video("own") + "," + direct, target = null)
        val loaded = read(reply, true).value!!
        assertEquals("unchanged", loaded.nextKey); assertEquals(listOf("own"), loaded.items.map { it.id })
        assertTrue(loaded.actions.isEmpty()); assertEquals(loaded, searchPage(reply))
    }

    @Test fun appendTargetRequiresExactTypedPresenceWithoutCoercionOrNormalization() {
        for (target in listOf("null", "false", "0", "[]", "{}", "\"\"", "\" search-feed\"", "\"search-feed \"",
            "\"SEARCH-FEED\"", "\"comments-section\"", "\"unknown\"")) {
            rejectedBody(body(video("own") + "," + cursor("own"), target))
        }
    }

    @Test fun outerCommandTargetCannotBorrowTheAppendPayloadTargetRole() {
        for (target in listOf("\"search-feed\"", "null", "false", "\"comments-section\"")) {
            rejectedBody(body(video("own"), wrapperExtra = "\"targetId\":$target,"))
            rejectedBody(body(video("own"), target = null, wrapperExtra = "\"targetId\":$target,"))
        }
    }

    @Test fun reloadTargetAndHeaderBodyPoliciesAreUnchangedByTheAppendObservation() {
        val items = video("own") + "," + cursor("own")
        rejectedBody(body(items, kind = "reloadContinuationItemsCommand", payloadExtra = ",\"slot\":\"RELOAD_CONTINUATION_SLOT_BODY\""))
        rejectedBody(body(items, kind = "reloadContinuationItemsCommand"))
        rejectedBody(body(items, payloadExtra = ",\"slot\":\"RELOAD_CONTINUATION_SLOT_HEADER\""))
        val legacy = body(items, target = null, kind = "reloadContinuationItemsCommand", payloadExtra = ",\"slot\":\"RELOAD_CONTINUATION_SLOT_BODY\"")
        assertEquals("own", read(legacy, true).value!!.nextKey)
    }

    @Test fun legacyContinuationOwnerCannotBorrowTheAppendPayloadTargetRole() {
        for (target in listOf("\"search-feed\"", "null", "false")) {
            rejectedBody("""{"continuationContents":{"gridContinuation":{"targetId":$target,"items":[${video("own")},${cursor("own")}]}}}""")
        }
        val absent = """{"continuationContents":{"gridContinuation":{"items":[${video("own")},${cursor("own")}]}}}"""
        assertEquals("own", read(absent, true).value!!.nextKey)
    }

    @Test fun observedAppendTargetCannotOverrideForeignRouteOrRequestOnItsCursorAncestry() {
        val reply = jsonObject(body(video("own") + "," + cursor("own")))!!
        val paths = listOf(emptyList(), listOf("onResponseReceivedCommands", "0"),
            listOf("onResponseReceivedCommands", "0", "appendContinuationItemsAction"))
        val declarations = listOf("""{"request":"CONTINUATION_REQUEST_TYPE_BROWSE"}""",
            """{"request":null}""", """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}}}""",
            """{"commandMetadata":false}""")
        for (path in paths) for (declaration in declarations) {
            rejectedBody(injected(reply, path, jsonObject(declaration)!!).encode())
        }
    }

    @Test fun matchingAppendTargetsNeverMergeParallelOwnersOrInitialAndBodySources() {
        val reply = jsonObject(body(video("own") + "," + cursor("same")))!!
        val command = reply.arr("onResponseReceivedCommands").single()
        rejectedBody(Json.Obj(reply.fields + ("onResponseReceivedCommands" to Json.Arr(listOf(command, command)))).encode())
        rejectedBody(Json.Obj(reply.fields + ("onResponseReceivedActions" to Json.Arr(listOf(command)))).encode())
        val initial = jsonObject(page(video("initial")))!!
        val mixed = Json.Obj(initial.fields + reply.fields).encode()
        rejectedBody(mixed); assertEquals("unknown_search_page", read(mixed).error?.code)
    }

    @Test fun matchingAppendTargetRetainsSourceCountAmbiguityAndTheExplicitOuterScopePolicy() {
        rejectedBody(body(video("own") + "," + cursor("same") + "," + cursor("same")))
        val first = """{"itemSectionRenderer":{"contents":[${video("first")},${cursor("same")}]}}"""
        val second = """{"itemSectionRenderer":{"contents":[${video("second")},${cursor("same")}]}}"""
        rejectedBody(body(first + "," + second))
        val outer = read(body(first + "," + second + "," + cursor("outer")), true).value!!
        assertEquals("outer", outer.nextKey); assertEquals(listOf("first", "second"), outer.items.map { it.id })
    }

    @Test fun unownedSearchFeedTargetsCannotNominateOrRepairACollection() {
        val shadow = """{"targetId":"search-feed","continuationItems":[${video("shadow")},${cursor("shadow")}] }"""
        rejectedBody(body(video("own"), "\"comments-section\"", rootExtra = ",\"responseContext\":$shadow"))
        rejectedBody("""{"unknown":{"appendContinuationItemsAction":$shadow}}""")
        val metadataEnd = read("""{"responseContext":$shadow}""", true).value!!
        assertTrue(metadataEnd.items.isEmpty()); assertTrue(metadataEnd.groups.isEmpty()); assertNull(metadataEnd.nextKey)
        val loaded = read(body(video("own"), rootExtra = ",\"responseContext\":$shadow"), true).value!!
        assertEquals(listOf("own"), loaded.items.map { it.id }); assertNull(loaded.nextKey)
    }

    @Test fun observedAppendTargetDoesNotAuthorizeAnUnknownOwnedChild() {
        val card = """{"officialCardRenderer":{"contents":[{"horizontalShelfViewModel":{"items":[${video("unproven")}]}}]}}"""
        rejectedBody(body(video("own") + "," + card + "," + cursor("own")))
    }

    @Test fun observedAppendTargetKeepsOwnCardOrderAndGroupCursorSeparateFromShadows() {
        val shadow = """{"contents":[${video("own", ",\"title\":\"Shadow\"")},${cursor("shadow")}]}"""
        val loaded = read(body(video("own") + "," + shelf("in-group", "group") + "," + cursor("page"),
            rootExtra = ",\"responseContext\":$shadow"), true).value!!
        assertEquals(listOf("own", "in-group"), loaded.items.map { it.id }); assertNull(loaded.items.first().title)
        assertEquals("page", loaded.nextKey); assertEquals("group", loaded.groups.single().nextKey)
        assertEquals(listOf("in-group"), loaded.groups.single().items.map { it.id }); assertTrue(loaded.actions.isEmpty())
        val groupOnly = read(body(shelf("in-group", "group")), true).value!!
        assertNull(groupOnly.nextKey); assertEquals("group", groupOnly.groups.single().nextKey)
    }

    @Test fun appendTargetObservationDoesNotChangeInitialOpaqueTargetMetadataPolicy() {
        val original = jsonObject(page(video("own") + "," + cursor("own")))!!
        val path = listOf("contents", "twoColumnSearchResultsRenderer", "primaryContents", "sectionListRenderer")
        for (target in listOf(Json.Str("search-feed"), Json.Str("other-ui-role"), Json.Null)) {
            val reply = injected(original, path, Json.Obj(mapOf("targetId" to target))).encode()
            assertEquals("own", read(reply).value!!.nextKey)
        }
    }
}
