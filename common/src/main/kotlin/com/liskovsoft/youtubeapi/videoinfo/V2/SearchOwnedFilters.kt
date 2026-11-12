package com.liskovsoft.youtubeapi.videoinfo.V2

/** Filters have their own UI issuer; a result collection or response metadata is not that issuer. */
internal fun ownedSearchFilters(root: Json.Obj, legacy: Boolean): List<SearchFilter> = runCatching {
    SearchFilterReader().read(root, legacy)
}.getOrDefault(emptyList())

private class SearchFilterReader {
    private val result = ArrayList<SearchFilter>()
    private var nodes = 0
    private fun tick(depth: Int) { require(++nodes <= 2048 && depth <= 24) }
    private val tracking = setOf("trackingParams", "clickTrackingParams")
    private fun kind(wrapper: Json.Obj): String? {
        tracking.forEach { require(wrapper.fields[it]?.let { value -> value is Json.Str } != false) }
        return (wrapper.fields.keys - tracking - "commandMetadata").singleOrNull()
    }
    fun read(root: Json.Obj, legacy: Boolean): List<SearchFilter> {
        if (legacy) {
            root.obj("searchFilterGroupRenderer")?.let { group(it, 0, listOf(root)) }
            for (field in listOf("contents", "items")) (root.fields[field] as? Json.Arr)?.items?.forEach { value ->
                val wrapper = value as? Json.Obj ?: return@forEach
                if (kind(wrapper) == "searchFilterGroupRenderer") group(wrapper.obj("searchFilterGroupRenderer")!!, 0, listOf(root, wrapper))
                else control(wrapper, 0, listOf(root))
            }
            if (kind(root) in setOf("buttonRenderer", "gridButtonRenderer", "buttonViewModel", "toggleButtonRenderer")) control(root, 0, emptyList())
        }
        root.obj("header")?.let { control(it, 0, listOf(root)) }
        val contents = root.obj("contents")
        val name = listOf("twoColumnSearchResultsRenderer", "singleColumnSearchResultsRenderer").filter { contents?.fields?.containsKey(it) == true }.singleOrNull()
        name?.let { layoutName ->
            val layout = contents!!.obj(layoutName) ?: error("search filter layout")
            val path = listOf(root, contents, layout)
            layout.obj("header")?.let { control(it, 0, path) }
            val sections = listOfNotNull(layout.path("primaryContents", "sectionListRenderer"), layout.obj("sectionListRenderer"))
            require(sections.size == 1)
            sections.single().obj("subMenu")?.let { control(it, 0, path + listOfNotNull(layout.obj("primaryContents"), sections.single())) }
        }
        for (name in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
            val commands = root.fields[name] as? Json.Arr ?: continue
            require(commands.items.size <= 8)
            for (value in commands.items) {
                val reload = (value as? Json.Obj)?.obj("reloadContinuationItemsCommand") ?: continue
                if (reload.text("slot") != "RELOAD_CONTINUATION_SLOT_HEADER") continue
                val items = reload.fields["continuationItems"] as? Json.Arr ?: error("search filter header")
                require(items.items.size <= 1)
                items.items.forEach { control(it as? Json.Obj ?: error("search filter header type"), 0, listOf(root, value as Json.Obj, reload)) }
            }
        }
        return result
    }
    private fun group(node: Json.Obj, depth: Int, ancestors: List<Json.Obj>) {
        tick(depth)
        require((ancestors + node).all(::declaredSearchRoute))
        val carrier = setOf("filters", "contents").filter(node.fields::containsKey).singleOrNull() ?: error("search filter collection")
        val array = node.fields[carrier] as? Json.Arr ?: error("search filter items")
        require(array.items.size <= 128)
        for (value in array.items) {
            tick(depth + 1)
            val wrapper = value as? Json.Obj ?: error("search filter wrapper")
            require(kind(wrapper) == "searchFilterRenderer" && declaredSearchRoute(wrapper))
            val item = wrapper.obj("searchFilterRenderer") ?: error("search filter type")
            require(declaredSearchRoute(item))
            val carriers = setOf("navigationEndpoint", "serviceEndpoint", "onTap", "command", "defaultServiceEndpoint", "rendererContext")
                .filter(item.fields::containsKey)
            require(carriers.isEmpty() || carriers == listOf("navigationEndpoint"))
            val selected = item.text("status") == "FILTER_STATUS_SELECTED"
            val navigation = item.fields["navigationEndpoint"]?.let { it as? Json.Obj ?: error("search filter command type") }
            val params = navigation?.let { command ->
                require(declaredSearchRoute(command))
                require((command.fields.keys - tracking - "commandMetadata").singleOrNull() == "searchEndpoint")
                val endpoint = command.obj("searchEndpoint") ?: error("search filter endpoint")
                require(declaredSearchRoute(endpoint) && endpoint.fields["params"]?.let { it is Json.Str } != false)
                endpoint.text("params")?.takeIf(String::isNotBlank)
            }
            if (params != null || selected) result += SearchFilter(item.display("label"), node.display("title"), params, selected)
        }
    }
    private fun control(wrapper: Json.Obj, depth: Int, ancestors: List<Json.Obj>) {
        tick(depth)
        when (val controlKind = kind(wrapper)) {
            "searchHeaderRenderer", "searchSubMenuRenderer" -> {
                val node = wrapper.obj(controlKind) ?: error("search filter control")
                val path = ancestors + listOf(wrapper, node)
                for (field in listOf("searchFilterButton", "filterButton", "button")) node.obj(field)?.let { control(it, depth + 1, path) }
                (node.fields["buttons"] as? Json.Arr)?.let { buttons ->
                    require(buttons.items.size <= 32)
                    buttons.items.forEach { control(it as? Json.Obj ?: error("search filter button"), depth + 1, path) }
                }
            }
            "buttonRenderer", "gridButtonRenderer", "buttonViewModel", "toggleButtonRenderer" -> {
                val node = wrapper.obj(controlKind) ?: error("search filter button type")
                val path = ArrayList(ancestors + listOf(wrapper, node))
                val sources = listOf("command", "navigationEndpoint", "serviceEndpoint", "onTap", "defaultServiceEndpoint").filter(node.fields::containsKey)
                val contextual = node.fields["rendererContext"]?.let { declared ->
                    val renderer = declared as? Json.Obj ?: error("search filter renderer context")
                    val context = renderer.obj("commandContext") ?: error("search filter command context")
                    val tap = context.obj("onTap") ?: error("search filter tap")
                    val inner = tap.obj("innertubeCommand") ?: error("search filter contextual command")
                    require((tap.fields.keys - tracking - "commandMetadata").singleOrNull() == "innertubeCommand")
                    path += listOf(renderer, context, tap)
                    inner
                }
                require(sources.size + (if (contextual != null) 1 else 0) <= 1)
                var command = sources.singleOrNull()?.let { node.obj(it) ?: error("search filter command") } ?: contextual ?: return
                if ("innertubeCommand" in command.fields) {
                    val inner = command.obj("innertubeCommand") ?: error("search filter inner command")
                    require((command.fields.keys - tracking - "commandMetadata").singleOrNull() == "innertubeCommand")
                    path += command
                    command = inner
                }
                if ((command.fields.keys - tracking - "commandMetadata").singleOrNull() != "openPopupAction") return
                val action = command.obj("openPopupAction") ?: error("search filter popup action")
                val popup = action.obj("popup") ?: error("search filter popup")
                val popupKind = (popup.fields.keys - tracking - "commandMetadata").singleOrNull()
                require(popupKind in setOf("menuRenderer", "searchFilterOptionsDialogRenderer"))
                val collection = popup.obj(popupKind!!) ?: error("search filter popup collection")
                path += listOf(command, action, popup, collection)
                val field = if (popupKind == "menuRenderer") "items" else "groups"
                val entries = collection.fields[field] as? Json.Arr ?: error("search filter popup items")
                require(entries.items.size <= 128)
                for (value in entries.items) {
                    val item = value as? Json.Obj ?: continue
                    if (kind(item) == "searchFilterGroupRenderer") group(item.obj("searchFilterGroupRenderer")!!, depth + 1, path + item)
                }
            }
        }
    }
}
