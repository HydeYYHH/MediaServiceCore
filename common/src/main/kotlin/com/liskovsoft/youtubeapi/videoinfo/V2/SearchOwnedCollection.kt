package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

/** Parsed items, groups, and continuation from one selected search collection. */
internal data class SearchOwnedCollection(val items: List<MediaItem>, val groups: List<MediaGroup>, val nextKey: String?,
    val messages: List<String>, val legacy: Boolean = false, val fragmentActions: List<PageAction> = emptyList())

private val searchLayouts = setOf("twoColumnSearchResultsRenderer", "singleColumnSearchResultsRenderer")
private val searchResponseLists = setOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")
private val searchTracking = setOf("trackingParams", "clickTrackingParams")
private val searchMessages = setOf("feedNudgeRenderer", "messageRenderer", "backgroundPromoRenderer", "genericPromoRenderer", "alertRenderer")
private val searchAds = setOf("searchPyvRenderer", "adSlotRenderer", "adPlacementRenderer", "inFeedAdLayoutRenderer", "displayAdRenderer",
    "promotedVideoRenderer", "promotedSparklesWebRenderer", "promotedSparklesTextSearchRenderer", "promotedSparklesWebViewRenderer",
    "adInlinePlaybackRenderer", "carouselAdRenderer")
private val searchLists = mapOf("itemSectionRenderer" to "contents", "sectionListRenderer" to "contents", "richGridRenderer" to "contents",
    "gridRenderer" to "items", "horizontalListRenderer" to "items", "verticalListRenderer" to "items", "reelShelfRenderer" to "items",
    "richShelfRenderer" to "contents", "gridShelfViewModel" to "contents")

/** Supplemental search cards whose contents and commands are outside the native result collection. */
internal val searchAuxiliarySlots = setOf("officialCardViewModel")

/** Network callers supply the actual request lane. An explicit empty metadata end belongs only to continuation. */
internal fun ownedSearchCollection(root: Json.Obj, continuation: Boolean, pureHeader: Boolean = false): SearchOwnedCollection? = runCatching {
    if (continuation && searchMetadataEnd(root)) return@runCatching SearchOwnedCollection(emptyList(), emptyList(), null, emptyList())
    val selected: Json.Arr
    var owner: Json.Obj? = null
    val ancestors = arrayListOf(root)
    if (!continuation) {
        require("continuationContents" !in root.fields)
        val contents = root.obj("contents") ?: error("search contents")
        val name = searchLayouts.filter(contents.fields::containsKey).singleOrNull() ?: error("search layout")
        val layout = contents.obj(name) ?: error("search layout type")
        ancestors += contents; ancestors += layout
        require("primaryContents" !in layout.fields || layout.path("primaryContents", "sectionListRenderer") != null)
        require("sectionListRenderer" !in layout.fields || layout.obj("sectionListRenderer") != null)
        owner = listOfNotNull(layout.path("primaryContents", "sectionListRenderer"), layout.obj("sectionListRenderer")).single()
        layout.obj("primaryContents")?.let(ancestors::add); ancestors += owner
        selected = owner.fields["contents"] as? Json.Arr ?: error("search primary list")
        // Only auxiliary initial UI commands may coexist with the selected opening collection.
        for (family in searchResponseLists) {
            val declared = root.fields[family] ?: continue
            val commands = declared as? Json.Arr ?: error("search initial response list")
            require(commands.items.size <= 8)
            for (value in commands.items) {
                val command = value as? Json.Obj ?: error("search initial response command")
                require("appendContinuationItemsAction" !in command.fields)
                command.fields["reloadContinuationItemsCommand"]?.let { payload ->
                    val reload = payload as? Json.Obj ?: error("search initial reload")
                    require(reload.text("slot") == "RELOAD_CONTINUATION_SLOT_HEADER")
                }
            }
        }
    } else {
        require(root.obj("contents")?.fields?.keys?.none { it in searchLayouts } != false)
        if ("continuationContents" in root.fields) {
            require(searchResponseLists.none(root.fields::containsKey))
            val contents = root.obj("continuationContents") ?: error("search continuation")
            ancestors += contents
            val kind = contents.fields.keys.single()
            val field = mapOf("gridContinuation" to "items", "richGridContinuation" to "contents", "sectionListContinuation" to "contents",
                "itemSectionContinuation" to "contents", "horizontalListContinuation" to "items")[kind]
                ?: error("search continuation kind")
            owner = contents.obj(kind) ?: error("search continuation type")
            require("targetId" !in owner.fields)
            ancestors += owner
            selected = owner.fields[field] as? Json.Arr ?: error("search continuation list")
        } else {
            val family = searchResponseLists.filter(root.fields::containsKey).singleOrNull() ?: error("search response family")
            val commands = root.fields[family] as? Json.Arr ?: error("search response list")
            require(commands.items.size <= 8)
            val bodies = ArrayList<Json.Arr>()
            for (value in commands.items) {
                val command = value as? Json.Obj ?: error("search response command")
                val kinds = setOf("appendContinuationItemsAction", "reloadContinuationItemsCommand").filter(command.fields::containsKey)
                if (kinds.isEmpty()) continue
                require(kinds.size == 1)
                val collection = command.obj(kinds.single()) ?: error("search response collection")
                if (pureHeader && collection.text("slot") == "RELOAD_CONTINUATION_SLOT_HEADER") continue
                // Keep the current network contract: a HEADER row is not a result collection.
                require(collection.fields["slot"]?.let { it is Json.Str && it.value == "RELOAD_CONTINUATION_SLOT_BODY" } != false)
                // Only the append BODY payload has an observed SEARCH target role. Other carriers stay unsupported.
                require("targetId" !in command.fields)
                val target = collection.fields["targetId"]
                require("targetId" !in collection.fields || kinds.single() == "appendContinuationItemsAction" &&
                    target is Json.Str && target.value == "search-feed")
                bodies += collection.fields["continuationItems"] as? Json.Arr ?: error("search response items")
                owner = collection
                ancestors += command; ancestors += collection
            }
            selected = bodies.single()
        }
    }
    SearchCollectionReader(false, advertisingEnd = continuation && owner?.text("targetId") == "search-feed" &&
        ancestors.all(::declaredSearchRoute))
        .read(selected, owner, root, ancestors)
}.getOrNull()

/** The pure parser keeps explicit extracted fragments; unknown ancestors are never searched recursively. */
internal fun pureOwnedSearchCollection(root: Json.Obj): SearchOwnedCollection? {
    if (root.obj("contents")?.fields?.keys?.any { it in searchLayouts } == true)
        return ownedSearchCollection(root, false)
    if ("continuationContents" in root.fields || searchResponseLists.any(root.fields::containsKey) || searchMetadataEnd(root))
        return ownedSearchCollection(root, true, pureHeader = true)
    return runCatching {
        val slots = when {
            root.fields["contents"] is Json.Arr && root.fields.keys.all { it in setOf("contents", "source", "estimatedResults", "searchFilterGroupRenderer",
                "header", "secondaryContents", "trackingParams", "clickTrackingParams") } -> root.fields.getValue("contents") as Json.Arr
            root.fields["items"] is Json.Arr && root.fields.keys.all { it in setOf("items", "source", "trackingParams", "clickTrackingParams") } &&
                root.fields["source"] is Json.Str -> root.fields.getValue("items") as Json.Arr
            else -> {
                val names = root.fields.keys - searchTracking
                val kind = names.singleOrNull() ?: error("unknown search fragment")
                require(kind in contentRenderers || kind in searchLists || kind in setOf("shelfRenderer", "richItemRenderer", "richSectionRenderer") ||
                    kind in searchMessages || kind == "continuationItemRenderer" || kind in legacySearchUi)
                Json.Arr(listOf(root))
            }
        }
        SearchCollectionReader(true).read(slots, null, root, listOf(root))
    }.getOrNull()
}

internal val legacySearchUi = setOf("buttonRenderer", "gridButtonRenderer", "buttonViewModel", "toggleButtonRenderer", "menuServiceItemRenderer",
    "menuNavigationItemRenderer", "chipCloudChipRenderer", "listItemViewModel", "buttonCardViewModel", "searchFilterGroupRenderer")

private fun searchMetadataEnd(root: Json.Obj): Boolean = root.fields["responseContext"] is Json.Obj &&
    root.fields.keys.all { it in setOf("responseContext", "trackingParams") } && root.fields["trackingParams"]?.let { it is Json.Str } != false

private class SearchCollectionReader(private val legacy: Boolean, private val advertisingEnd: Boolean = false) {
    private class CursorScope(val pageOwned: Boolean = true, val ancestors: List<Json.Obj> = emptyList(),
        val own: ArrayList<String> = ArrayList(), val sections: ArrayList<CursorScope> = ArrayList()) {
        fun descend(nodes: List<Json.Obj>) = CursorScope(pageOwned, ancestors + nodes, own, sections)
        fun key(): Pair<String?, Boolean> {
            // One outer collection cursor owns the entire page, even when
            // child itemSections have local alternatives. Equal token bytes never merge issuers.
            if (own.size == 1) return own.single() to false
            if (own.size > 1) return null to true
            val children = sections.map { it.key() }
            val keys = children.mapNotNull { it.first }
            return if (children.any { it.second } || keys.size > 1) null to true else keys.singleOrNull() to false
        }
    }
    private data class Group(val title: String?, val cards: List<Json.Obj>, val scope: CursorScope, val actions: List<Json.Obj>)
    private val cards = ArrayList<Json.Obj>()
    private val groups = ArrayList<Group>()
    private val messages = ArrayList<String>()
    private val actionSlots = ArrayList<Json.Obj>()
    private var visited = 0
    private var organic = 0
    private var advertisements = 0
    private var advertisingRoutesValid = true
    private var auxiliary = 0
    private var empty = 0
    private var displayedActions = 0
    private var cursorCount = 0
    private fun tick(depth: Int) { require(++visited <= 2048 && depth <= 16) }
    fun read(slots: Json.Arr, owner: Json.Obj?, root: Json.Obj, ancestors: List<Json.Obj>): SearchOwnedCollection {
        val scope = CursorScope(ancestors = ancestors)
        owner?.let { declaredContinuations(it, scope) }
        visit(slots, scope, 0)
        val pageCursor = scope.key()
        require(!pageCursor.second) // Ambiguity is a parse failure, never a fabricated terminal page.
        val supported = organic > 0 || messages.isNotEmpty() || displayedActions > 0
        val known = supported || auxiliary == 0 && (legacy ||
            cursorCount > 0 && advertisements == 0 || empty > 0 && advertisements == 0 ||
            // A search-feed append can end with ads after the organic results. Its estimate is query-wide.
            advertisements > 0 && cursorCount == 0 && (advertisingEnd && advertisingRoutesValid || when (val estimate = root.fields["estimatedResults"]) {
                is Json.Str -> estimate.value == "0"; is Json.Num -> estimate.text == "0"; else -> false
            }))
        require(known)
        // Only leaf wrappers that this walk accepted are handed to the shared card mapper.
        val items = contentItems(Json.Arr(cards))
        val projectedGroups = groups.mapNotNull { group ->
            val entries = contentItems(Json.Arr(group.cards))
            if (entries.isEmpty()) null else MediaGroup(group.title, entries, group.scope.key().first,
                group.actions.mapNotNull { wrapper -> wrapper.fields.values.singleOrNull() as? Json.Obj }.mapNotNull(::directPageAction)
                    .distinctBy { Triple(it.title, it.endpoint, it.kind) })
        }
        val fragmentActions = if (legacy) actionSlots.flatMap(::pageActions).distinctBy { Triple(it.title, it.endpoint, it.kind) } else emptyList()
        return SearchOwnedCollection(items, projectedGroups, pageCursor.first, messages.distinct(), legacy, fragmentActions)
    }
    private fun declaredContinuations(node: Json.Obj, scope: CursorScope) {
        val declared = node.fields["continuations"] ?: return
        val array = declared as? Json.Arr ?: error("search declared continuations")
        require(array.items.size <= 8)
        for (item in array.items) {
            val wrapper = item as? Json.Obj ?: error("search continuation entry")
            val name = setOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData")
                .filter(wrapper.fields::containsKey).singleOrNull() ?: error("search continuation data")
            val token = wrapper.obj(name)?.text("continuation")?.takeIf(String::isNotBlank) ?: error("search continuation token")
            if (scope.pageOwned) (scope.ancestors + listOf(wrapper, wrapper.obj(name)!!)).forEach(::requireSearchCursor)
            scope.own += token; cursorCount++
        }
    }
    private fun visit(node: Json, scope: CursorScope, depth: Int) {
        tick(depth)
        if (node is Json.Arr) {
            require(node.items.size <= 512)
            if (node.items.isEmpty()) empty++
            node.items.forEach { require(it is Json.Obj); visit(it, scope, depth + 1) }
            return
        }
        val wrapper = node as? Json.Obj ?: error("search slot wrapper")
        searchTracking.forEach { require(wrapper.fields[it]?.let { field -> field is Json.Str } != false) }
        val kind = (wrapper.fields.keys - searchTracking).singleOrNull() ?: error("search slot kind")
        val value = wrapper.obj(kind) ?: error("search slot type")
        when {
            kind in searchAuxiliarySlots -> auxiliary++
            kind in searchAds || kind in contentRenderers && value.isAdvertisement() -> {
                advertisements++
                if (advertisingEnd) advertisingRoutesValid = advertisingRoutesValid && scope.ancestors.all(::declaredSearchRoute)
            }
            kind in contentRenderers -> {
                require(contentItems(wrapper).isNotEmpty())
                cards += wrapper; organic++
            }
            kind in searchLists || kind == "shelfRenderer" -> {
                val groupOwner = kind in setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")
                val selectedScope = when (kind) {
                    "shelfRenderer", "richShelfRenderer" -> CursorScope(false, scope.ancestors + listOf(wrapper, value))
                    "itemSectionRenderer" -> CursorScope(scope.pageOwned, scope.ancestors + listOf(wrapper, value)).also { scope.sections += it }
                    else -> scope.descend(listOf(wrapper, value))
                }
                val firstCard = cards.size; val firstGroup = groups.size; val firstAction = actionSlots.size
                declaredContinuations(value, selectedScope)
                if (kind == "shelfRenderer") {
                    if (!legacy) visit(value.obj("content") ?: error("search shelf content"), selectedScope, depth + 1)
                    else {
                        val carriers = setOf("content", "contents", "items").filter(value.fields::containsKey)
                        visit(value.fields.getValue(carriers.single()), selectedScope, depth + 1)
                    }
                } else visit(value.fields[searchLists.getValue(kind)] as? Json.Arr ?: error("search list slots"), selectedScope, depth + 1)
                if (groupOwner && groups.size == firstGroup) {
                    val title = value.display("title") ?: value.path("headerRenderer", "shelfHeaderRenderer")?.let {
                        it.display("title") ?: it.path("avatarLockup", "avatarLockupRenderer")?.display("title")
                    }
                    groups += Group(title, cards.subList(firstCard, cards.size).toList(), selectedScope, actionSlots.subList(firstAction, actionSlots.size).toList())
                }
            }
            kind in setOf("richItemRenderer", "richSectionRenderer") -> visit(value.obj("content") ?: error("search content slot"), scope.descend(listOf(wrapper, value)), depth + 1)
            kind == "buttonCardViewModel" -> {
                val command = value.path("rendererContext", "commandContext", "onTap", "innertubeCommand")
                val fields = command?.fields?.keys?.filter { it !in setOf("commandMetadata", "clickTrackingParams", "trackingParams") }
                require(!value.text("title").isNullOrBlank() && fields == listOf("browseEndpoint") && !command?.obj("browseEndpoint")?.text("browseId").isNullOrBlank())
                require(command?.fields?.get("commandMetadata")?.let { it is Json.Obj } != false)
                searchTracking.forEach { require(command?.fields?.get(it)?.let { field -> field is Json.Str } != false) }
                actionSlots += wrapper; displayedActions++
            }
            kind in searchMessages -> {
                val text = value.display("title") ?: value.display("text") ?: value.display("message") ?: value.display("bodyText")
                require(!text.isNullOrBlank()); messages += text
            }
            kind == "continuationItemRenderer" -> {
                val endpoint = value.obj("continuationEndpoint") ?: error("search cursor endpoint")
                require(value.fields.keys.none { it != "continuationEndpoint" && (it.endsWith("Endpoint") || it.endsWith("Command") || it in setOf("onTap", "onSelectCommand")) })
                require((endpoint.fields.keys - searchTracking - "commandMetadata").singleOrNull() == "continuationCommand")
                val command = endpoint.obj("continuationCommand") ?: error("search cursor command")
                require(command.fields.keys.all { it in setOf("token", "request", "command", "commandMetadata", "trackingParams", "clickTrackingParams") })
                require(command.fields["command"]?.let { it is Json.Obj } != false)
                val token = command.text("token")?.takeIf(String::isNotBlank)
                    ?: error("search cursor")
                if (scope.pageOwned) (scope.ancestors + listOf(wrapper, value, endpoint, command)).forEach(::requireSearchCursor)
                scope.own += token; cursorCount++
            }
            legacy && kind in legacySearchUi -> { actionSlots += wrapper }
            else -> error("unknown search slot")
        }
    }
}

/** A declared route must agree with this own SEARCH source; absence does not invent a declaration. */
internal fun declaredSearchRoute(node: Json.Obj): Boolean = runCatching {
    fun route(value: Json) {
        val raw = (value as? Json.Str)?.value ?: error("search route type")
        val uri = URI(raw)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443) &&
            (uri.scheme == null && uri.rawAuthority == null || uri.scheme == "https" && uri.host == "www.youtube.com") && uri.path == "/youtubei/v1/search")
    }
    node.fields["apiUrl"]?.let(::route)
    if ("commandMetadata" in node.fields) {
        val metadata = node.obj("commandMetadata") ?: error("search metadata type")
        if ("webCommandMetadata" in metadata.fields) {
            val web = metadata.obj("webCommandMetadata") ?: error("search web metadata type")
            web.fields["apiUrl"]?.let(::route)
            require(web.fields["sendPost"]?.let { it is Json.Bool && it.value } != false)
        }
    }
    true
}.getOrDefault(false)

private fun requireSearchCursor(node: Json.Obj) {
    require(declaredSearchRoute(node))
    require(node.fields["request"]?.let { it is Json.Str && it.value == "CONTINUATION_REQUEST_TYPE_SEARCH" } != false)
}
