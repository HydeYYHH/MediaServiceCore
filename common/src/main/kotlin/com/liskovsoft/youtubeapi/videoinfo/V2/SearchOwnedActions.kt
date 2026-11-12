package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

/** null means a legacy fragment; an invalid owned document must not become a successful empty page. */
internal data class SearchOwnedActions(val actions: List<PageAction>, val valid: Boolean)

internal fun ownedSearchActions(root: Json.Obj): SearchOwnedActions? {
    val contents = root.obj("contents")
    val layouts = listOf("twoColumnSearchResultsRenderer", "singleColumnSearchResultsRenderer")
    val envelopes = listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")
    if (root.fields["responseContext"] is Json.Obj && root.fields.keys.all { it in setOf("responseContext", "trackingParams") } &&
        root.fields["trackingParams"]?.let { it is Json.Str } != false) return SearchOwnedActions(emptyList(), true)
    if (layouts.none { contents?.fields?.containsKey(it) == true } &&
        "continuationContents" !in root.fields && envelopes.none(root.fields::containsKey)) {
        // Compatibility is for explicit renderer/list fragments, never an unknown document
        // whose only actions happen to live in global UI or response metadata.
        val direct = setOf("buttonRenderer", "gridButtonRenderer", "buttonViewModel", "toggleButtonRenderer",
            "menuServiceItemRenderer", "menuNavigationItemRenderer", "chipCloudChipRenderer", "listItemViewModel",
            "buttonCardViewModel", "sectionListRenderer", "itemSectionRenderer", "gridRenderer", "richGridRenderer")
        val names = root.fields.keys - setOf("trackingParams", "clickTrackingParams")
        val fragment = names.singleOrNull() in direct || root.fields["contents"] is Json.Arr &&
            root.fields.keys.all { it in setOf("contents", "estimatedResults", "trackingParams", "clickTrackingParams", "searchFilterGroupRenderer") }
        return if (fragment) null else SearchOwnedActions(emptyList(), false)
    }
    return runCatching { SearchActionReader().read(root) }.getOrElse { SearchOwnedActions(emptyList(), false) }
}

private class SearchActionReader {
    private var nodes = 0
    private var headerReloads = 0
    private val actions = ArrayList<PageAction>()
    private val tracking = setOf("trackingParams", "clickTrackingParams")
    private val renderers = setOf("buttonRenderer", "gridButtonRenderer", "buttonViewModel", "toggleButtonRenderer",
        "menuServiceItemRenderer", "menuNavigationItemRenderer", "chipCloudChipRenderer", "listItemViewModel", "buttonCardViewModel")
    private val lists = mapOf("sectionListRenderer" to "contents", "itemSectionRenderer" to "contents", "richGridRenderer" to "contents",
        "gridRenderer" to "items", "horizontalListRenderer" to "items", "verticalListRenderer" to "items", "reelShelfRenderer" to "items",
        "richShelfRenderer" to "contents", "gridShelfViewModel" to "contents")
    private fun need(ok: Boolean) { require(ok) }
    private fun obj(node: Json.Obj, name: String): Json.Obj = node.fields.getValue(name) as? Json.Obj ?: error("search action shape")
    private fun at(node: Json.Obj, name: String): Json.Obj? = if (name in node.fields) obj(node, name) else null
    private fun tick(depth: Int) { need(++nodes <= 4096 && depth <= 32) }
    private fun role(node: Json.Obj, allowed: Set<String>) {
        need(node.fields.keys.all { it in allowed || it in tracking || it == "commandMetadata" })
        tracking.forEach { name -> need(node.fields[name]?.let { it is Json.Str } != false) }
    }
    private fun routes(node: Json.Obj, inherited: List<String>): List<String> {
        val metadata = at(node, "commandMetadata") ?: return inherited
        val web = obj(metadata, "webCommandMetadata")
        val raw = web.fields["apiUrl"] ?: return inherited
        val uri = URI((raw as? Json.Str)?.value ?: error("search action route"))
        need(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443) &&
            (uri.scheme == null && uri.rawAuthority == null || uri.scheme == "https" && uri.host == "www.youtube.com"))
        need(uri.path in setOf("/youtubei/v1/search", "/youtubei/v1/browse"))
        return inherited + uri.path
    }

    fun read(root: Json.Obj): SearchOwnedActions {
        val initial = root.obj("contents")?.let { content ->
            val names = listOf("twoColumnSearchResultsRenderer", "singleColumnSearchResultsRenderer").filter(content.fields::containsKey)
            if (names.isEmpty()) null else {
                need(names.size == 1 && "continuationContents" !in root.fields)
                val layout = obj(content, names.single())
                val inherited = routes(layout, routes(content, routes(root, emptyList())))
                val sections = listOfNotNull(at(layout, "primaryContents")?.let { primary ->
                    obj(primary, "sectionListRenderer") to routes(primary, inherited)
                }, at(layout, "sectionListRenderer")?.let { it to inherited })
                need(sections.size == 1)
                val (section, parentRoutes) = sections.single()
                slots(section.fields.getValue("contents"), routes(section, parentRoutes), 0)
                at(section, "subMenu")?.let { submenu(it, routes(section, parentRoutes), 0) }
                at(layout, "header")?.let { header(it, inherited, 0) }
                true
            }
        } == true
        at(root, "header")?.let { header(it, routes(root, emptyList()), 0) }
        if (!initial) {
            val continuation = at(root, "continuationContents")
            if (continuation != null) {
                need(listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands").none(root.fields::containsKey))
                need(continuation.fields.size == 1)
                val name = continuation.fields.keys.single()
                val field = mapOf("sectionListContinuation" to "contents", "itemSectionContinuation" to "contents",
                    "gridContinuation" to "items", "richGridContinuation" to "contents", "horizontalListContinuation" to "items")[name]
                    ?: error("search continuation")
                val collection = obj(continuation, name)
                slots(collection.fields.getValue(field), routes(collection, routes(continuation, routes(root, emptyList()))), 0)
            }
        }
        for (name in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
            val declared = root.fields[name] ?: continue
            val commands = declared as? Json.Arr ?: error("search command list")
            need(commands.items.size <= 8)
            for (value in commands.items) {
                val command = value as? Json.Obj ?: error("search command")
                val kinds = listOf("reloadContinuationItemsCommand", "appendContinuationItemsAction").filter(command.fields::containsKey)
                // Unrelated response commands are not search action sources.
                if (kinds.isEmpty()) continue
                role(command, kinds.toSet()); need(kinds.size == 1)
                val collection = obj(command, kinds.single())
                val inherited = routes(collection, routes(command, routes(root, emptyList())))
                when (collection.text("slot")) {
                    "RELOAD_CONTINUATION_SLOT_HEADER" -> {
                        need(++headerReloads == 1)
                        val items = collection.fields["continuationItems"] as? Json.Arr ?: error("search header list")
                        need(items.items.size <= 1)
                        items.items.forEach { submenu(it as? Json.Obj ?: error("search header"), inherited, 0) }
                    }
                    null, "RELOAD_CONTINUATION_SLOT_BODY" -> if (!initial) slots(collection.fields.getValue("continuationItems"), inherited, 0)
                    else -> error("search response slot")
                }
            }
        }
        return SearchOwnedActions(actions.distinctBy { Triple(it.title, it.endpoint, it.kind) }, true)
    }

    private fun slots(value: Json, inherited: List<String>, depth: Int) {
        tick(depth)
        val array = value as? Json.Arr ?: error("search slots")
        need(array.items.size <= 512)
        array.items.forEach { wrapper ->
            val node = wrapper as? Json.Obj ?: error("search slot")
            tick(depth + 1)
            val kinds = node.fields.keys.filter { it !in tracking && it != "commandMetadata" }
            need(kinds.size == 1)
            val kind = kinds.single()
            role(node, setOf(kind))
            val item = obj(node, kind)
            if (node.isAdvertisement() || item.isAdvertisement() || kind in contentRenderers) return@forEach
            val path = routes(item, routes(node, inherited))
            when {
                kind in lists -> slots(item.fields.getValue(lists.getValue(kind)), path, depth + 1)
                kind in setOf("richItemRenderer", "richSectionRenderer", "shelfRenderer") -> {
                    val content = obj(item, "content")
                    slots(Json.Arr(listOf(content)), path, depth + 1)
                }
                kind == "buttonCardViewModel" -> direct(item, path, false)
                kind in setOf("continuationItemRenderer", "feedNudgeRenderer", "messageRenderer", "backgroundPromoRenderer", "genericPromoRenderer", "alertRenderer") -> Unit
                else -> error("unknown search slot")
            }
        }
    }
    private fun header(node: Json.Obj, inherited: List<String>, depth: Int) {
        tick(depth)
        val kinds = node.fields.keys.filter { it !in tracking && it != "commandMetadata" }
        need(kinds.size == 1)
        val kind = kinds.single()
        role(node, setOf(kind))
        if (kind in renderers) direct(obj(node, kind), routes(node, inherited), true)
        else {
            need(kind == "searchHeaderRenderer")
            val value = obj(node, kind)
            role(value, setOf("title", "text", "searchFilterButton", "filterButton", "button", "chipBar"))
            val path = routes(value, routes(node, inherited))
            val controls = listOf("searchFilterButton", "filterButton", "button").filter(value.fields::containsKey)
            need(controls.size <= 1)
            controls.forEach { field -> header(obj(value, field), path, depth + 1) }
            at(value, "chipBar")?.let { chips(it, path, depth + 1) }
        }
    }
    private fun chips(node: Json.Obj, inherited: List<String>, depth: Int) {
        tick(depth)
        role(node, setOf("chipCloudRenderer"))
        val cloud = obj(node, "chipCloudRenderer")
        role(cloud, cloud.fields.keys)
        val path = routes(cloud, routes(node, inherited))
        val items = cloud.fields["chips"] as? Json.Arr ?: error("search chips")
        need(items.items.size <= 128)
        // Other cloud presentation fields are opaque. Only this direct chips array owns commands;
        // popup/navigation descendants elsewhere in the cloud never become page actions.
        items.items.forEach { value ->
            tick(depth + 1)
            val wrapper = value as? Json.Obj ?: error("search chip")
            role(wrapper, setOf("chipCloudChipRenderer"))
            direct(obj(wrapper, "chipCloudChipRenderer"), routes(wrapper, path), false, true)
        }
    }
    private fun submenu(node: Json.Obj, inherited: List<String>, depth: Int) {
        tick(depth)
        role(node, setOf("searchSubMenuRenderer"))
        val value = obj(node, "searchSubMenuRenderer")
        role(value, setOf("title", "text", "button", "buttons", "filterButton", "searchFilterButton"))
        val path = routes(value, routes(node, inherited))
        need(listOf("button", "buttons", "filterButton", "searchFilterButton").count(value.fields::containsKey) <= 1)
        for (name in listOf("button", "filterButton", "searchFilterButton")) at(value, name)?.let { header(it, path, depth + 1) }
        value.fields["buttons"]?.let { declared ->
            val buttons = declared as? Json.Arr ?: error("search submenu buttons")
            need(buttons.items.size <= 32)
            buttons.items.forEach { header(it as? Json.Obj ?: error("search submenu button"), path, depth + 1) }
        }
    }
    private fun direct(item: Json.Obj, inherited: List<String>, uiAllowed: Boolean, chipContinuation: Boolean = false) {
        tick(0)
        val sources = listOf("serviceEndpoint", "navigationEndpoint", "onTap", "defaultServiceEndpoint", "command").filter(item.fields::containsKey)
        val itemRoutes = routes(item, inherited)
        val contextual = at(item, "rendererContext")?.let { renderer ->
            val context = obj(renderer, "commandContext")
            val tap = obj(context, "onTap")
            obj(tap, "innertubeCommand") to routes(tap, routes(context, routes(renderer, itemRoutes)))
        }
        need(sources.size + (if (contextual != null) 1 else 0) <= 1)
        var command = sources.singleOrNull()?.let { obj(item, it) } ?: contextual?.first ?: return
        var path = routes(command, contextual?.second ?: itemRoutes)
        at(command, "innertubeCommand")?.let { inner -> role(command, setOf("innertubeCommand")); command = inner; path = routes(inner, path) }
        val endpoints = command.fields.keys.filter { it !in tracking && it != "commandMetadata" }
        need(endpoints.size == 1)
        val endpoint = endpoints.single()
        role(command, setOf(endpoint))
        val payload = obj(command, endpoint)
        when (endpoint) {
            "browseEndpoint", "searchEndpoint" -> {
                need(!chipContinuation)
                val expected = if (endpoint == "browseEndpoint") "/youtubei/v1/browse" else "/youtubei/v1/search"
                need(routes(payload, path).all { it == expected })
                role(payload, if (endpoint == "browseEndpoint") setOf("browseId", "params") else setOf("query", "params"))
                need(!payload.text(if (endpoint == "browseEndpoint") "browseId" else "query").isNullOrBlank())
                need(payload.fields["params"]?.let { it is Json.Str } != false)
                directPageAction(item)?.let(actions::add) ?: error("missing direct search action")
            }
            "continuationCommand" -> {
                need(chipContinuation && path.isNotEmpty() && routes(payload, path).all { it == "/youtubei/v1/search" })
                role(payload, setOf("token", "request", "command"))
                need(!payload.text("token").isNullOrBlank() && payload.fields["request"]?.let { it is Json.Str } != false)
                // WEB chip continuations carry an opaque UI command. It is retained as data, never
                // traversed for another action or menu semantics; the direct SEARCH token owns this action.
                need(payload.fields["command"]?.let { it is Json.Obj } != false)
                directPageAction(item)?.copy(kind = null, kindToken = null)?.let(actions::add)
                    ?: error("missing direct chip action")
            }
            // These own UI controls are not native network commands. Their descendants are never page actions.
            "openPopupAction", "signalServiceEndpoint", "signInEndpoint" -> need(uiAllowed && path.isEmpty())
            else -> error("unsupported owned search action")
        }
    }
}
