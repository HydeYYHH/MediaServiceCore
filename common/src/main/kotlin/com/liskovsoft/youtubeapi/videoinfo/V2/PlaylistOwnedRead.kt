package com.liskovsoft.youtubeapi.videoinfo.V2
import java.util.ArrayDeque

/** Entries and the forward cursor must be issued by the same playlist collection. */
internal data class OwnedPlaylistCollection(val entries: List<Json.Obj>, val nextKey: String?, val playlistId: String?)

internal fun ownedPlaylistCollection(root: Json.Obj, continuation: Boolean? = null, expectedPlaylistId: String? = null): OwnedPlaylistCollection? =
    runCatching { PlaylistCollectionReader(expectedPlaylistId).read(root, continuation) }.getOrNull()

private class PlaylistCollectionReader(expectedPlaylistId: String?) {
    private val expectedId = expectedPlaylistId?.takeIf(String::isNotBlank)?.removePrefix("VL")
    private var declaredId: String? = null
    private val entries = ArrayList<Json.Obj>()
    private val cursors = ArrayList<String>()
    private val listKinds = setOf("playlistVideoListRenderer")
    private val containers = mapOf("sectionListRenderer" to "contents", "itemSectionRenderer" to "contents",
        "gridRenderer" to "items", "richGridRenderer" to "contents")
    private val actionRoots = listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")

    fun read(root: Json.Obj, continuation: Boolean?): OwnedPlaylistCollection {
        budget(root)
        val hasInitial = "contents" in root.fields || "playlistVideoListRenderer" in root.fields
        require(!hasInitial || "continuationContents" !in root.fields)
        if (continuation == true) require(!hasInitial)
        if (continuation == false) require("continuationContents" !in root.fields)
        if (hasInitial && continuation != true) initial(root) else continued(root)
        require(cursors.size <= 1)
        return OwnedPlaylistCollection(entries, cursors.singleOrNull(), expectedId ?: declaredId)
    }

    private fun initial(root: Json.Obj) {
        route(root)
        if ("playlistVideoListRenderer" in root.fields) {
            require("contents" !in root.fields)
            list(obj(root, "playlistVideoListRenderer")); return
        }
        when (val contents = root.fields["contents"]) {
            is Json.Arr -> flat(contents)
            is Json.Obj -> {
                route(contents)
                val kinds = (listKinds + containers.keys + setOf("twoColumnBrowseResultsRenderer",
                    "singleColumnBrowseResultsRenderer", "tvBrowseRenderer")).filter { it in contents.fields }
                when (val kind = kinds.single()) {
                    "twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer" -> web(obj(contents, kind))
                    "tvBrowseRenderer" -> {
                        var node = obj(contents, kind)
                        for (field in listOf("content", "tvSurfaceContentRenderer", "content", "twoColumnRenderer", "rightColumn")) {
                            route(node); node = obj(node, field)
                        }
                        // This known TV column also owns unrelated display/recommendation fields.
                        // They are not part of its direct playlist collection or its cursor.
                        scope(Json.Obj(node.fields.filterKeys { it !in setOf("secondaryContents", "header", "continuations") }))
                    }
                    else -> scope(contents)
                }
            }
            else -> error("playlist_collection")
        }
    }

    private fun web(layout: Json.Obj) {
        route(layout)
        val tabs = arr(layout, "tabs")
        require(tabs.items.size in 1..128)
        val candidates = tabs.items.map { value ->
            val wrapper = value as? Json.Obj ?: error("playlist_tab")
            route(wrapper)
            val kind = slotKind(wrapper, setOf("tabRenderer", "expandableTabRenderer"))
            obj(wrapper, kind).also { tab ->
                route(tab)
                if ("selected" in tab.fields) require(tab.fields["selected"] is Json.Bool)
            }
        }
        val selected = candidates.filter { (it.fields["selected"] as? Json.Bool)?.value == true }
        // Older TV/WEB fixtures omit selected on their sole tab; multiple tabs never guess a selection.
        val tab = if (selected.isEmpty() && candidates.size == 1 && "selected" !in candidates.single().fields)
            candidates.single() else selected.single()
        scope(obj(tab, "content"))
    }

    private fun scope(node: Json.Obj) {
        val found = ArrayList<Pair<Json.Obj, List<Json.Obj>>>()
        fun find(value: Json.Obj, ancestors: List<Json.Obj>, depth: Int) {
            require(depth <= 16)
            val declared = (listKinds + containers.keys).filter { it in value.fields }
            require(declared.size <= 1)
            if (declared.isEmpty()) return
            val kind = slotKind(value, listKinds + containers.keys)
            val renderer = obj(value, kind)
            val path = ancestors + value + renderer
            if (kind in listKinds) { found += renderer to path; return }
            for (child in arr(renderer, containers.getValue(kind)).items) {
                val wrapper = child as? Json.Obj ?: error("playlist_slot")
                // Only these direct collection roles can lead to the owned list. A card, shelf,
                // sidebar or arbitrary object is never searched for another collection.
                find(wrapper, path, depth + 1)
            }
        }
        find(node, emptyList(), 0)
        require(found.size <= 1)
        if (found.isNotEmpty()) {
            val (renderer, ancestors) = found.single()
            ancestors.forEach { route(it); identity(it) }
            list(renderer)
        } else legacyScope(node)
    }

    private fun legacyScope(node: Json.Obj) {
        route(node)
        val kind = containers.keys.filter { it in node.fields }.single()
        val renderer = obj(node, kind)
        route(renderer)
        identity(renderer)
        val items = arr(renderer, containers.getValue(kind))
        if (kind == "sectionListRenderer") {
            val sections = items.items.filter { (it as? Json.Obj)?.fields?.containsKey("itemSectionRenderer") == true }
            if (sections.isNotEmpty()) {
                // A legacy flat playlist can have one primary item-section and its own outer
                // continuation. Two primary sections are ambiguous, even if their tokens agree.
                require(sections.size == 1)
                for (item in items.items) {
                    val wrapper = item as? Json.Obj ?: error("playlist_slot")
                    if ("itemSectionRenderer" in wrapper.fields) {
                        slotKind(wrapper, setOf("itemSectionRenderer"))
                        legacyScope(wrapper)
                    } else slot(wrapper)
                }
                ownContinuations(renderer); return
            }
        }
        flat(items)
        ownContinuations(renderer)
    }

    private fun list(renderer: Json.Obj) {
        route(renderer)
        identity(renderer)
        flat(arr(renderer, "contents"))
        ownContinuations(renderer)
    }

    private fun continued(root: Json.Obj) {
        route(root)
        if ("continuationContents" in root.fields) {
            require(actionRoots.none { it in root.fields })
            val contents = obj(root, "continuationContents")
            route(contents)
            val kind = contents.fields.keys.filter { it !in setOf("trackingParams", "clickTrackingParams", "commandMetadata") }.single()
            val node = obj(contents, kind)
            route(node)
            identity(node)
            when (kind) {
                "playlistVideoListContinuation" -> { flat(arr(node, "contents")); ownContinuations(node) }
                "gridContinuation" -> { flat(arr(node, "items")); ownContinuations(node) }
                "tvSurfaceContentContinuation" -> scope(obj(node, "content"))
                else -> error("playlist_continuation")
            }
            return
        }
        val found = ArrayList<Json.Obj>()
        for (name in actionRoots) {
            if (name !in root.fields) continue
            for (value in arr(root, name).items) {
                val command = value as? Json.Obj ?: error("playlist_action")
                val kinds = listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand").filter { it in command.fields }
                require(kinds.size <= 1)
                if (kinds.isEmpty()) continue
                route(command)
                slotKind(command, setOf("appendContinuationItemsAction", "reloadContinuationItemsCommand"))
                found += obj(command, kinds.single()).also { route(it) }
            }
        }
        val action = found.single()
        identity(action)
        flat(arr(action, "continuationItems"))
        ownContinuations(action)
    }

    private fun flat(items: Json.Arr) {
        require(items.items.size <= 2000)
        for (value in items.items) slot(value as? Json.Obj ?: error("playlist_slot"))
    }

    private fun slot(wrapper: Json.Obj) {
        route(wrapper)
        val kind = slotKind(wrapper, setOf("playlistVideoRenderer", "tileRenderer", "lockupViewModel",
            "richItemRenderer", "continuationItemRenderer", "continuationItemViewModel", "messageRenderer"))
        val node = obj(wrapper, kind)
        when (kind) {
            "richItemRenderer" -> { route(node); slot(obj(node, "content")) }
            "continuationItemRenderer" -> {
                route(node)
                val carriers = listOf("continuationEndpoint", "button").filter { it in node.fields }
                var command = obj(node, carriers.single())
                route(command)
                if (carriers.single() == "button") {
                    command = obj(command, "buttonRenderer"); route(command)
                    command = obj(command, "command"); route(command)
                }
                cursor(command)
            }
            "continuationItemViewModel" -> {
                route(node)
                val command = obj(node, "continuationCommand"); route(command)
                cursor(obj(command, "innertubeCommand"))
            }
            "messageRenderer" -> Unit // A known unavailable-entry message has no entries or cursor.
            else -> entries += wrapper
        }
    }

    private fun slotKind(node: Json.Obj, supported: Set<String>): String {
        for (name in listOf("trackingParams", "clickTrackingParams"))
            if (name in node.fields) require(node.fields[name] is Json.Str)
        val kind = node.fields.keys.filter { it !in setOf("trackingParams", "clickTrackingParams", "commandMetadata") }.single()
        require(kind in supported)
        require(node.fields[kind] is Json.Obj)
        return kind
    }

    private fun cursor(command: Json.Obj) {
        route(command)
        slotKind(command, setOf("continuationCommand"))
        val payload = obj(command, "continuationCommand")
        route(payload)
        val request = payload.fields["request"]
        if (request != null) require(request is Json.Str && request.value == "CONTINUATION_REQUEST_TYPE_BROWSE")
        cursors += (payload.text("token")?.takeIf { it.isNotBlank() && it.length <= 262144 } ?: error("playlist_token"))
    }

    private fun ownContinuations(node: Json.Obj) {
        if ("continuations" !in node.fields) return
        for (entry in arr(node, "continuations").items) {
            val wrapper = entry as? Json.Obj ?: error("playlist_cursor")
            route(wrapper)
            val kind = slotKind(wrapper, setOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData"))
            val data = obj(wrapper, kind); route(data)
            val token = data.text("continuation")?.takeIf { it.isNotBlank() && it.length <= 262144 } ?: error("playlist_token")
            if (kind == "nextContinuationData") cursors += token
        }
    }

    private fun identity(node: Json.Obj) {
        val declared = if ("playlistId" in node.fields)
            node.text("playlistId")?.takeIf(String::isNotBlank)?.removePrefix("VL") ?: error("playlist_identity") else null
        val bound = expectedId ?: declaredId
        if (bound != null && declared != null) require(declared == bound)
        if (declared != null) declaredId = declared
        if ("targetId" !in node.fields) return
        val target = node.text("targetId")?.takeIf(String::isNotBlank) ?: error("playlist_target")
        if (target == "playlist-video-list") return // Generic list role, not an identity assertion.
        val canonical = target.removePrefix("VL")
        val targetBound = expectedId ?: declaredId
        if (targetBound != null) require(canonical == targetBound)
        else require(canonical == "WL" || canonical == "LL" ||
            listOf("PL", "OLAK", "RD").any(canonical::startsWith))
        if (declared != null) require(canonical == declared)
        declaredId = canonical
    }

    private fun route(node: Json.Obj) {
        if ("commandMetadata" !in node.fields) return
        val metadata = obj(node, "commandMetadata")
        if ("webCommandMetadata" !in metadata.fields) return
        val web = obj(metadata, "webCommandMetadata")
        if ("apiUrl" !in web.fields) return
        val api = web.text("apiUrl") ?: error("playlist_route")
        require(api == "/youtubei/v1/browse" || api == "https://www.youtube.com/youtubei/v1/browse")
    }

    private fun obj(node: Json.Obj, name: String): Json.Obj = node.fields[name] as? Json.Obj ?: error("playlist_object")
    private fun arr(node: Json.Obj, name: String): Json.Arr = node.fields[name] as? Json.Arr ?: error("playlist_array")

    private fun budget(root: Json) {
        val pending = ArrayDeque<Pair<Json, Int>>()
        pending.add(root to 0)
        var nodes = 0
        var text = 0L
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            require(++nodes <= 125000 && depth <= 96)
            when (value) {
                is Json.Obj -> value.fields.forEach { (key, child) -> text += key.length; pending.add(child to depth + 1) }
                is Json.Arr -> { require(value.items.size <= 10000); value.items.forEach { pending.add(it to depth + 1) } }
                is Json.Str -> text += value.value.length
                else -> Unit
            }
            require(text <= 4L * 1024 * 1024)
        }
    }
}
