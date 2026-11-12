package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

/** Network readers never give the fragment parser the original response or its opaque metadata. */
internal fun ownedCommentResponse(root: Json.Obj, allowEmptyReplies: Boolean = false): Json.Obj? =
    runCatching { CommentProjection().response(root, allowEmptyReplies) }.getOrNull()

internal fun ownedCommentOpening(root: Json.Obj, videoId: String? = null): Json.Obj? =
    runCatching { CommentProjection().opening(root, videoId) }.getOrNull()

internal fun ownedCommentMutation(root: Json.Obj): Json.Obj? =
    runCatching { CommentProjection().mutation(root) }.getOrNull()

/** A relative-path projection, not a claim that the server returned a complete comment history. */
private class CommentProjection {
    private class Invalid : RuntimeException()
    private fun require(ok: Boolean) { if (!ok) throw Invalid() }
    private fun obj(vararg fields: Pair<String, Json>) = Json.Obj(linkedMapOf(*fields))
    private fun arr(items: List<Json>) = Json.Arr(items)
    private fun nonblank(node: Json.Obj, key: String): String {
        val value = node.text(key); require(!value.isNullOrBlank()); return value!!
    }
    private fun objectAt(node: Json.Obj, key: String): Json.Obj {
        val value = node.obj(key); require(value != null); return value!!
    }
    private fun arrayAt(node: Json.Obj, key: String): List<Json> {
        val value = node.fields[key] as? Json.Arr; require(value != null); return value!!.items
    }
    private var nodes = 0
    private fun budget(value: Json, depth: Int = 0) {
        require(++nodes <= 125_000 && depth <= 96)
        when (value) {
            is Json.Obj -> value.fields.values.forEach { budget(it, depth + 1) }
            is Json.Arr -> value.items.forEach { budget(it, depth + 1) }
            else -> Unit
        }
    }
    private val opaque = setOf("responseContext", "trackingParams", "clickTrackingParams", "loggingDirectives",
        "rendererContext", "accessibility", "accessibilityData", "accessibilityLabel", "loggingContext", "trackingContext")
    private val commentData = setOf("commentRenderer", "commentThreadRenderer", "commentViewModel", "commentEntityPayload",
        "commentSurfaceEntityPayload", "commentToolbarStateEntityPayload", "commentToolbarSurfaceEntityPayload",
        "engagementToolbarStateEntityPayload", "engagementToolbarSurfaceEntityPayload", "appendContinuationItemsAction",
        "reloadContinuationItemsCommand", "itemSectionContinuation", "commentRepliesContinuation", "sortFilterSubMenuRenderer",
        "createCommentParams", "createReplyParams", "performCommentActionEndpoint", "continuationCommand",
        "nextContinuationData", "reloadContinuationData", "iconType", "buttonRenderer", "gridButtonRenderer", "buttonViewModel",
        "toggleButtonRenderer", "menuServiceItemRenderer", "menuNavigationItemRenderer", "chipCloudChipRenderer", "listItemViewModel", "buttonCardViewModel")
    private fun presentation(value: Json): Json = when (value) {
        is Json.Obj -> Json.Obj(value.fields.filterKeys { it !in opaque }.mapValues { (name, child) ->
            require(name !in commentData); presentation(child)
        })
        is Json.Arr -> arr(value.items.map(::presentation))
        else -> value
    }
    private fun metadata(root: Json.Obj) {
        require(root.fields["responseContext"]?.let { it is Json.Obj } != false)
        require(root.fields["trackingParams"]?.let { it is Json.Str } != false)
    }
    private fun endpointMetadata(node: Json.Obj, paths: Set<String> = setOf("/youtubei/v1/next", "/youtubei/v1/browse")): Json.Obj? {
        if (!node.fields.containsKey("commandMetadata")) return null
        val metadata = objectAt(node, "commandMetadata")
        val web = objectAt(metadata, "webCommandMetadata")
        if (!web.fields.containsKey("apiUrl")) return null
        val raw = nonblank(web, "apiUrl")
        val uri = runCatching { URI(raw) }.getOrNull(); require(uri != null)
        require(uri!!.rawUserInfo == null && uri.rawFragment == null && uri.port in listOf(-1, 443) &&
            uri.rawQuery in listOf(null, "prettyPrint=false") &&
            (uri.isAbsolute && uri.scheme == "https" && uri.host == "www.youtube.com" || !uri.isAbsolute && uri.rawAuthority == null))
        require(uri.path in paths)
        return obj("webCommandMetadata" to obj("apiUrl" to Json.Str(uri.path)))
    }
    private fun route(parent: Json.Obj?, current: Json.Obj?): Json.Obj? {
        require(parent == null || current == null || parent.encode() == current.encode())
        return current ?: parent
    }
    /** Only explicitly recognized command paths can issue a cursor. Neighbor data cannot supply one. */
    private fun continuation(source: Json.Obj, inherited: Json.Obj? = null): Json.Obj? {
        val candidates = ArrayList<Json.Obj>()
        val outer = route(inherited, endpointMetadata(source))
        fun direct(node: Json.Obj, parent: Json.Obj? = outer) {
            val route = route(parent, endpointMetadata(node))
            val declared = listOf("continuationCommand", "nextContinuationData", "reloadContinuationData").filter(node.fields::containsKey)
            require(declared.size <= 1)
            for (name in declared) {
                val command = objectAt(node, name)
                val token = nonblank(command, if (name == "continuationCommand") "token" else "continuation")
                require(token.length <= 65_536)
                val fields = linkedMapOf<String, Json>("continuationCommand" to obj("token" to Json.Str(token)))
                route?.let { fields["commandMetadata"] = it }
                candidates += Json.Obj(fields)
            }
        }
        direct(source)
        if (source.fields.containsKey("continuationEndpoint")) direct(objectAt(source, "continuationEndpoint"))
        if (source.fields.containsKey("button")) {
            val wrapper = objectAt(source, "button")
            val button = objectAt(wrapper, "buttonRenderer")
            val route = route(route(outer, endpointMetadata(wrapper)), endpointMetadata(button))
            if (button.fields.containsKey("command")) direct(objectAt(button, "command"), route)
            direct(button, route)
        }
        require(candidates.size <= 1)
        return candidates.singleOrNull()
    }
    private fun action(source: Json.Obj?, strict: Boolean = false): Json.Obj? {
        if (source == null) return null
        if (strict) {
            val declared = listOf("innertubeCommand", "performCommentActionEndpoint").filter(source.fields::containsKey)
            require(declared.size == 1)
            require(source.fields.keys.all { it in setOf("innertubeCommand", "performCommentActionEndpoint", "commandMetadata") || it in opaque })
            declared.forEach { objectAt(source, it) }
        }
        val actual = source.obj("innertubeCommand") ?: source
        if (strict && actual !== source) {
            require(actual.fields.keys.all { it == "performCommentActionEndpoint" || it == "commandMetadata" || it in opaque })
            objectAt(actual, "performCommentActionEndpoint")
        }
        require(actual === source || !source.fields.containsKey("performCommentActionEndpoint"))
        val paths = setOf("/youtubei/v1/comment/perform_comment_action")
        val outerRoute = endpointMetadata(source, paths)
        val actualRoute = endpointMetadata(actual, paths)
        if (strict) {
            require(outerRoute != null || actualRoute != null)
            for (node in listOf(source, actual)) node.path("commandMetadata", "webCommandMetadata")?.fields?.get("sendPost")?.let {
                require(it is Json.Bool && it.value)
            }
        }
        val endpoint = actual.obj("performCommentActionEndpoint") ?: return null
        val token = nonblank(endpoint, "action")
        require(actual.fields.keys.none { it.endsWith("Endpoint") && it != "performCommentActionEndpoint" })
        return obj("performCommentActionEndpoint" to obj("action" to Json.Str(token)))
    }
    private fun button(source: Json.Obj): Json.Obj {
        val actual = source.obj("toggleButtonRenderer") ?: source.obj("buttonRenderer") ?: source
        val paths = setOf("/youtubei/v1/comment/perform_comment_action")
        endpointMetadata(source, paths); endpointMetadata(actual, paths)
        val fields = linkedMapOf<String, Json>()
        for (name in listOf("defaultServiceEndpoint", "toggledServiceEndpoint", "serviceEndpoint", "innertubeCommand", "onTap"))
            action(actual.obj(name))?.let { fields[name] = it }
        actual.fields["isToggled"]?.let { require(it is Json.Bool); fields["isToggled"] = it }
        return obj("toggleButtonRenderer" to Json.Obj(fields))
    }
    private fun menu(source: Json.Obj, deleteOnly: Boolean = false): Json.Obj {
        val renderer = source.obj("menuRenderer") ?: source
        val paths = setOf("/youtubei/v1/comment/perform_comment_action")
        endpointMetadata(source, paths); endpointMetadata(renderer, paths)
        if (deleteOnly) require(renderer.fields.keys.all { it == "items" || it == "commandMetadata" || it in opaque })
        val items = if (deleteOnly) arrayAt(renderer, "items") else (renderer.fields["items"] as? Json.Arr)?.items.orEmpty()
        require(items.size <= 200)
        var deleteCount = 0
        val projected = items.mapNotNull { value ->
            val wrapper = value as? Json.Obj ?: throw Invalid()
            val item = wrapper.obj("menuServiceItemRenderer") ?: wrapper.obj("menuNavigationItemRenderer") ?: return@mapNotNull null
            // An inline menu also contains EDIT navigation. It is not a comment action,
            // so neither inspect its opaque endpoint nor expose it to fragment readers.
            val delete = item.path("icon")?.text("iconType") == "DELETE"
            if (deleteOnly && !delete) return@mapNotNull null
            if (deleteOnly) {
                require(listOf("menuServiceItemRenderer", "menuNavigationItemRenderer").count(wrapper.fields::containsKey) == 1)
                require(wrapper.fields.keys.all { it in setOf("menuServiceItemRenderer", "menuNavigationItemRenderer", "commandMetadata") || it in opaque })
            }
            endpointMetadata(wrapper, paths); endpointMetadata(item, paths)
            fun confirmed(): Json.Obj? {
                var node = item.obj("navigationEndpoint") ?: return null
                for (name in listOf("confirmDialogEndpoint", "content", "confirmDialogRenderer", "confirmButton", "buttonRenderer", "serviceEndpoint")) {
                    endpointMetadata(node, paths)
                    if (deleteOnly) require(node.fields.keys.none {
                        (it.endsWith("Endpoint") || it.endsWith("Command")) && it != name
                    })
                    node = node.obj(name) ?: return null
                }
                return action(node, strict = deleteOnly)
            }
            if (deleteOnly) for (name in listOf("serviceEndpoint", "navigationEndpoint"))
                if (item.fields.containsKey(name)) objectAt(item, name)
            val commands = listOfNotNull(action(item.obj("serviceEndpoint"), strict = deleteOnly), confirmed())
            require(commands.size <= 1)
            val command = commands.singleOrNull()
            // A DELETE icon in an unrelated entry must not lend its meaning to another entry's action.
            if (delete && command == null) return@mapNotNull null
            if (deleteOnly && delete) require(++deleteCount <= 1)
            val fields = linkedMapOf<String, Json>()
            for (name in listOf("text", "title")) item.fields[name]?.let { fields[name] = presentation(it) }
            item.fields["icon"]?.let { val icon = it as? Json.Obj ?: throw Invalid()
                val type = nonblank(icon, "iconType")
                require(icon.fields.keys.all { name -> name == "iconType" || name in opaque })
                fields["icon"] = obj("iconType" to Json.Str(type)) }
            if (command != null) fields["serviceEndpoint"] = command
            else for (name in listOf("serviceEndpoint", "navigationEndpoint")) item.fields[name]?.let { fields[name] = presentation(it) }
            obj("menuServiceItemRenderer" to Json.Obj(fields))
        }
        return obj("menuRenderer" to obj("items" to arr(projected)))
    }
    /** Only this inline endpoint on the already joined toolbar may supply its own menu. */
    private fun inlineMenu(source: Json.Obj): Json.Obj {
        val paths = setOf("/youtubei/v1/comment/perform_comment_action")
        fun step(node: Json.Obj, name: String): Json.Obj {
            require(node.fields.keys.all { it == name || it == "commandMetadata" || it in opaque })
            endpointMetadata(node, paths)
            return objectAt(node, name)
        }
        val command = step(source, "innertubeCommand")
        val endpoint = step(command, "menuEndpoint")
        val wrapper = step(endpoint, "menu")
        step(wrapper, "menuRenderer")
        return menu(wrapper, deleteOnly = true)
    }
    private fun detail(source: Json.Obj, inherited: Json.Obj? = null): Json.Obj? {
        continuation(source, inherited)?.let { return it }
        val show = source.obj("showEngagementPanelEndpoint") ?: return null
        val engagement = objectAt(show, "engagementPanel")
        val panel = objectAt(engagement, "engagementPanelSectionListRenderer")
        val content = panel.obj("content") ?: return null
        val parent = listOf(source, show, engagement, panel, content).fold(inherited) { current, node -> route(current, endpointMetadata(node)) }
        continuation(content, parent)?.let { return it }
        val sectionList = content.obj("sectionListRenderer") ?: return null
        val sectionParent = route(parent, endpointMetadata(sectionList))
        val sections = sectionList.arr("contents")
        val routes = sections.mapNotNull { (it as? Json.Obj)?.obj("itemSectionRenderer") }.mapNotNull { section ->
            val sectionRoute = route(sectionParent, endpointMetadata(section))
            val declarations = section.arr("continuations").mapNotNull { it as? Json.Obj }.mapNotNull { continuation(it, sectionRoute) }
            require(declarations.size <= 1)
            declarations.singleOrNull()
        }
        require(routes.size <= 1); return routes.singleOrNull()
    }
    private fun replies(source: Json.Obj, inherited: Json.Obj? = null): Json.Obj {
        val renderer = source.obj("commentRepliesRenderer") ?: throw Invalid()
        val routes = ArrayList<Json.Obj>()
        val parent = route(route(inherited, endpointMetadata(source)), endpointMetadata(renderer))
        continuation(renderer, parent)?.let(routes::add)
        for (item in renderer.arr("continuations")) {
            val wrapper = item as? Json.Obj ?: throw Invalid()
            continuation(wrapper, parent)?.let(routes::add)
        }
        for (item in renderer.arr("contents")) {
            val wrapper = item as? Json.Obj ?: throw Invalid()
            wrapper.obj("continuationItemRenderer")?.let { continuation(it, parent)?.let(routes::add) }
            // Inline previews are not additional root records and cannot lend another comment's capabilities.
        }
        require(routes.size <= 1)
        return obj("commentRepliesRenderer" to (routes.singleOrNull()?.let { obj("continuationEndpoint" to it) } ?: obj()))
    }
    private val ids = ArrayList<String>()
    private data class View(val key: String, val literal: String?, val source: Json.Obj)
    private val views = ArrayList<View>()
    private fun legacy(source: Json.Obj, inherited: Json.Obj? = null): Json.Obj {
        val parent = route(inherited, endpointMetadata(source))
        val id = nonblank(source, "commentId"); require(id !in ids); ids += id
        val fields = linkedMapOf<String, Json>("commentId" to Json.Str(id))
        for (name in listOf("authorText", "contentText", "publishedTimeText", "voteCount", "authorThumbnail", "replyCount", "repliesCount",
            "isLiked", "isDisliked", "voteStatus", "isCreator", "authorIsChannelOwner", "isVerified", "isPinned", "isHearted", "pinnedCommentBadge"))
            source.fields[name]?.let { fields[name] = presentation(it) }
        for (name in listOf("parentCommentId", "createReplyParams")) if (source.fields.containsKey(name)) fields[name] = Json.Str(nonblank(source, name))
        source.path("authorEndpoint", "browseEndpoint")?.let { fields["authorEndpoint"] = obj("browseEndpoint" to obj("browseId" to Json.Str(nonblank(it, "browseId")))) }
        source.path("actionButtons", "commentActionButtonsRenderer")?.let { buttons ->
            val content = linkedMapOf<String, Json>()
            for (name in listOf("likeButton", "dislikeButton")) buttons.obj(name)?.let { content[name] = button(it) }
            fields["actionButtons"] = obj("commentActionButtonsRenderer" to Json.Obj(content))
        }
        source.obj("actionMenu")?.let { fields["actionMenu"] = menu(it) }
        source.obj("replies")?.let { fields["replies"] = replies(it, parent) }
        source.obj("detailViewEndpoint")?.let { detail(it, parent)?.let { route -> fields["detailViewEndpoint"] = obj("continuationEndpoint" to route) } }
        return Json.Obj(fields)
    }
    private fun modern(source: Json.Obj): Json.Obj {
        require(!source.fields.containsKey("commandMetadata"))
        val actual = if (source.fields.containsKey("commentKey")) source else objectAt(source, "commentViewModel")
        require(!actual.fields.containsKey("commandMetadata"))
        require(!actual.fields.containsKey("commentViewModel"))
        val key = nonblank(actual, "commentKey"); require(views.none { it.key == key })
        val literal = if (actual.fields.containsKey("commentId")) nonblank(actual, "commentId") else null
        views += View(key, literal, actual)
        val fields = linkedMapOf<String, Json>("commentKey" to Json.Str(key))
        literal?.let { fields["commentId"] = Json.Str(it) }
        for (name in listOf("toolbarStateKey", "toolbarSurfaceKey", "commentSurfaceKey", "sharedKey"))
            if (actual.fields.containsKey(name)) fields[name] = Json.Str(nonblank(actual, name))
        actual.fields["pinnedText"]?.let { fields["pinnedText"] = presentation(it) }
        return Json.Obj(fields)
    }
    private fun slot(value: Json): Json.Obj? {
        val wrapper = value as? Json.Obj ?: throw Invalid(); require(wrapper.fields.size == 1)
        return when (val name = wrapper.fields.keys.single()) {
            // A known filter-context list item is display-only; nested data and commands are never searched.
            "commentFilterContextViewModel" -> { objectAt(wrapper, name); null }
            "commentRenderer" -> obj(name to legacy(objectAt(wrapper, name)))
            "commentViewModel" -> obj(name to modern(objectAt(wrapper, name)))
            "commentThreadRenderer" -> {
                val thread = objectAt(wrapper, name)
                val parent = endpointMetadata(thread)
                require(listOf("comment", "commentViewModel").count(thread.fields::containsKey) == 1)
                val fields = linkedMapOf<String, Json>()
                if (thread.fields.containsKey("comment")) {
                    val comment = objectAt(thread, "comment"); require(comment.fields.keys == setOf("commentRenderer"))
                    fields["comment"] = obj("commentRenderer" to legacy(objectAt(comment, "commentRenderer"), parent))
                } else fields["commentViewModel"] = obj("commentViewModel" to modern(objectAt(thread, "commentViewModel")))
                thread.obj("replies")?.let { fields["replies"] = replies(it, parent) }
                obj(name to Json.Obj(fields))
            }
            "continuationItemRenderer" -> {
                val command = continuation(objectAt(wrapper, name)); require(command != null)
                obj(name to obj("continuationEndpoint" to command!!))
            }
            "commentsHeaderRenderer" -> obj(name to header(objectAt(wrapper, name)))
            "commentSimpleboxRenderer", "commentsSimpleboxRenderer" -> obj("commentsHeaderRenderer" to header(obj("createRenderer" to wrapper)))
            "messageRenderer" -> obj(name to presentation(objectAt(wrapper, name)))
            else -> throw Invalid()
        }
    }
    private fun creation(source: Json.Obj?): Json.Obj? = source?.let { endpoint ->
        endpointMetadata(endpoint, setOf("/youtubei/v1/comment/create_comment"))
        val key = endpoint.text("createCommentParams") ?: return@let null
        require(key.isNotBlank()); obj("createCommentParams" to Json.Str(key))
    }
    private fun sorts(source: Json.Obj?): Json.Obj? = source?.let { renderer ->
        val parent = endpointMetadata(renderer)
        val items = arrayAt(renderer, "subMenuItems"); require(items.size <= 32)
        val result = items.map { value ->
            val item = value as? Json.Obj ?: throw Invalid()
            val endpoint = item.obj("serviceEndpoint") ?: item.obj("navigationEndpoint") ?: item.obj("continuation")
            val command = endpoint?.let { continuation(it, route(parent, endpointMetadata(item))) }; require(command != null)
            val fields = linkedMapOf<String, Json>("serviceEndpoint" to command!!)
            item.fields["title"]?.let { require(it is Json.Str); fields["title"] = it }
            item.fields["selected"]?.let { require(it is Json.Bool); fields["selected"] = it }
            Json.Obj(fields)
        }
        obj("subMenuItems" to arr(result))
    }
    private var channelCreationRequired = false
    private fun header(source: Json.Obj): Json.Obj {
        require(!source.fields.containsKey("commandMetadata"))
        val fields = linkedMapOf<String, Json>()
        source.fields["countText"]?.let { fields["countText"] = presentation(it) }
        fun declaredObject(parent: Json.Obj?, name: String): Json.Obj? =
            parent?.takeIf { it.fields.containsKey(name) }?.let { objectAt(it, name) }
        val renderer = declaredObject(source, "createRenderer")
        val variants = listOf("commentSimpleboxRenderer", "commentsSimpleboxRenderer").filter { renderer?.fields?.containsKey(it) == true }
        require(variants.size <= 1)
        val simple = variants.singleOrNull()?.let { objectAt(renderer!!, it) }
        val createPaths = setOf("/youtubei/v1/comment/create_comment")
        renderer?.let { endpointMetadata(it, createPaths) }
        simple?.let { endpointMetadata(it, createPaths) }
        val submitWrapper = declaredObject(simple, "submitButton")
        submitWrapper?.let { endpointMetadata(it, createPaths) }
        val submitButton = declaredObject(submitWrapper, "buttonRenderer")
        submitButton?.let { endpointMetadata(it, createPaths) }
        val submit = declaredObject(submitButton, "serviceEndpoint")
        val setup = submit?.fields?.containsKey("channelCreationServiceEndpoint") == true
        if (setup) {
            // Account setup is not a comment command. Drop its complete subtree, including nested capabilities.
            objectAt(submit!!, "channelCreationServiceEndpoint")
            require(submit.fields.keys.all { it in opaque || it in setOf("channelCreationServiceEndpoint", "commandMetadata") })
            submit.fields["commandMetadata"]?.let { value ->
                val metadata = value as? Json.Obj ?: throw Invalid()
                val web = objectAt(metadata, "webCommandMetadata")
                web.fields["apiUrl"]?.let { require(it is Json.Str && it.value.isNotBlank()) }
            }
            channelCreationRequired = true
        } else submit?.let {
            endpointMetadata(it, createPaths)
            if (it.fields.containsKey("createCommentEndpoint"))
                require(it.fields.keys.none { name -> name.endsWith("Endpoint") && name != "createCommentEndpoint" })
        }
        val candidates = listOfNotNull(creation(declaredObject(source, "createCommentEndpoint")), creation(declaredObject(simple, "createCommentEndpoint")),
            creation(declaredObject(submit, "createCommentEndpoint")))
        require(!setup || candidates.isEmpty())
        require(candidates.size <= 1); candidates.singleOrNull()?.let { fields["createCommentEndpoint"] = it }
        val menus = listOfNotNull(source.obj("sortFilterSubMenuRenderer"), source.path("sortMenu", "sortFilterSubMenuRenderer"))
        require(menus.size <= 1); sorts(menus.singleOrNull())?.let { fields["sortFilterSubMenuRenderer"] = it }
        return Json.Obj(fields)
    }
    private fun directHeader(root: Json.Obj): Json.Obj {
        val fields = linkedMapOf<String, Json>()
        val endpoint = creation(root.obj("createCommentEndpoint"))
        val loose = root.fields["createCommentParams"]?.let { require(it is Json.Str && it.value.isNotBlank()); obj("createCommentParams" to it) }
        require(endpoint == null || loose == null)
        (endpoint ?: loose)?.let { fields["createCommentEndpoint"] = it }
        sorts(root.obj("sortFilterSubMenuRenderer"))?.let { fields["sortFilterSubMenuRenderer"] = it }
        return Json.Obj(fields)
    }
    private fun surface(source: Json.Obj): Json.Obj {
        endpointMetadata(source, setOf("/youtubei/v1/comment/perform_comment_action"))
        val fields = linkedMapOf<String, Json>()
        source.text("key")?.let { fields["key"] = Json.Str(it) }
        for (name in listOf("likeButton", "dislikeButton")) source.obj(name)?.let { fields[name] = button(it) }
        for (name in listOf("likeCommand", "unlikeCommand", "dislikeCommand", "undislikeCommand")) action(source.obj(name))?.let { fields[name] = it }
        val menus = listOf("menu", "overflowMenu", "menuRenderer", "menuCommand").filter(source.fields::containsKey)
        // Different menu roles must not lend icons or actions to each other.
        require(menus.size <= 1)
        menus.singleOrNull()?.let { name ->
            val value = objectAt(source, name)
            val projected = if (name == "menuCommand") inlineMenu(value) else menu(value)
            fields["menuRenderer"] = projected.fields.getValue("menuRenderer")
        }
        return Json.Obj(fields)
    }
    private fun framework(root: Json.Obj): Json.Obj? {
        val container = root.obj("frameworkUpdates")
        require(!root.fields.containsKey("frameworkUpdates") || container != null)
        val batch = container?.obj("entityBatchUpdate")
        require(container?.fields?.containsKey("entityBatchUpdate") != true || batch != null)
        val mutations = ArrayList<Json>()
        for (parent in listOfNotNull(root, batch)) if (parent.fields.containsKey("mutations")) mutations += arrayAt(parent, "mutations")
        require(mutations.size <= 1000)
        val wanted = linkedMapOf<String, String>()
        for (view in views) {
            require(wanted[view.key] == null || wanted[view.key] == "commentEntityPayload")
            wanted[view.key] = "commentEntityPayload"
            for ((field, role) in listOf("toolbarStateKey" to "engagementToolbarStateEntityPayload", "toolbarSurfaceKey" to "engagementToolbarSurfaceEntityPayload", "commentSurfaceKey" to "commentSurfaceEntityPayload"))
                view.source.text(field)?.let { key -> require(wanted[key] == null); wanted[key] = role }
        }
        val found = linkedMapOf<String, Json.Obj>()
        val projected = ArrayList<Json>()
        for (value in mutations) {
            val mutation = value as? Json.Obj ?: throw Invalid()
            val key = mutation.text("entityKey") ?: continue
            val role = wanted[key] ?: continue
            require(mutation.text("type") == "ENTITY_MUTATION_TYPE_REPLACE")
            val payload = objectAt(mutation, "payload")
            val aliases = when (role) { "engagementToolbarStateEntityPayload" -> listOf(role, "commentToolbarStateEntityPayload")
                "engagementToolbarSurfaceEntityPayload" -> listOf(role, "commentToolbarSurfaceEntityPayload"); else -> listOf(role) }
            val declared = aliases.filter(payload.fields::containsKey); require(declared.size == 1)
            val entity = objectAt(payload, declared.single()); require(found.put(key, entity) == null)
            entity.fields["key"]?.let { require(it is Json.Str && it.value == key) }
            val scoped = when (role) {
                "commentEntityPayload" -> {
                    val properties = objectAt(entity, "properties"); val id = nonblank(properties, "commentId")
                    val view = views.single { it.key == key }; require(view.literal == null || view.literal == id)
                    require(id !in ids); ids += id
                    val fields = linkedMapOf<String, Json>("commentId" to Json.Str(id))
                    for (name in listOf("content", "publishedTime", "parentCommentId", "replyLevel")) properties.fields[name]?.let { fields[name] = presentation(it) }
                    val output = linkedMapOf<String, Json>("key" to Json.Str(key), "properties" to Json.Obj(fields))
                    entity.obj("author")?.let { output["author"] = presentation(it) }
                    entity.obj("toolbar")?.let { toolbar -> output["toolbar"] = Json.Obj(toolbar.fields.filterKeys {
                        it in setOf("likeCountLiked", "likeCountNotliked", "likeCountA11y", "replyCount")
                    }.also { fields -> require(fields.values.all { it is Json.Str || it is Json.Num }) }) }
                    Json.Obj(output)
                }
                "engagementToolbarSurfaceEntityPayload" -> surface(entity)
                "engagementToolbarStateEntityPayload" -> Json.Obj(entity.fields.filterKeys { it in setOf("key", "likeState", "heartState") }.also { fields -> require(fields.values.none { it is Json.Obj || it is Json.Arr }) })
                else -> presentation(entity)
            }
            projected += obj("entityKey" to Json.Str(key), "type" to Json.Str("ENTITY_MUTATION_TYPE_REPLACE"), "payload" to obj(declared.single() to scoped))
        }
        require(views.all { found.containsKey(it.key) })
        if (projected.isEmpty()) return null
        return obj("entityBatchUpdate" to obj("mutations" to arr(projected)))
    }
    fun response(root: Json.Obj, allowEmptyReplies: Boolean): Json.Obj {
        budget(root); metadata(root)
        val allowed = opaque + setOf("frameworkUpdates", "mutations", "continuationContents", "onResponseReceivedEndpoints", "onResponseReceivedActions", "onResponseReceivedCommands", "createCommentEndpoint", "createCommentParams", "sortFilterSubMenuRenderer")
        require(root.fields.keys.all { it in allowed })
        val bodies = ArrayList<Json.Obj>(); val headers = ArrayList<Json.Obj>()
        if (root.fields.containsKey("continuationContents")) {
            val continuation = objectAt(root, "continuationContents"); require(continuation.fields.size == 1)
            require(continuation.fields.keys.single() in setOf("itemSectionContinuation", "commentRepliesContinuation"))
            bodies += objectAt(continuation, continuation.fields.keys.single())
        }
        for (name in listOf("onResponseReceivedEndpoints", "onResponseReceivedActions", "onResponseReceivedCommands")) if (root.fields.containsKey(name)) {
            val actions = arrayAt(root, name); require(actions.size <= 8)
            for (value in actions) {
                val action = value as? Json.Obj ?: throw Invalid()
                require(action.fields.keys.all { it in setOf("clickTrackingParams", "appendContinuationItemsAction", "reloadContinuationItemsCommand") })
                require(action.fields["clickTrackingParams"]?.let { it is Json.Str } != false)
                val commands = listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand").filter(action.fields::containsKey); require(commands.size == 1)
                val collection = objectAt(action, commands.single())
                when (collection.text("slot")) {
                    "RELOAD_CONTINUATION_SLOT_HEADER" -> headers += collection
                    "RELOAD_CONTINUATION_SLOT_BODY" -> bodies += collection
                    null -> { require(!collection.fields.containsKey("slot")); bodies += collection }
                    else -> throw Invalid()
                }
            }
        }
        require(bodies.size == 1 && headers.size <= 1)
        val body = bodies.single()
        val target = body.text("targetId")
        if (body.fields.containsKey("targetId")) require(target in setOf("comments-section", "engagement-panel-comments-section", "shorts-engagement-panel-comments-section") ||
            target?.matches(Regex("comment-replies-item-[A-Za-z0-9_.-]{1,1024}")) == true)
        val fieldsAllowed = setOf("contents", "continuationItems", "continuations", "targetId", "trackingParams", "slot", "header")
        require(body.fields.keys.all { it in fieldsAllowed })
        require(body.fields["trackingParams"]?.let { it is Json.Str } != false)
        val arrays = listOf("contents", "continuationItems").filter(body.fields::containsKey)
        val omitted = arrays.isEmpty() && allowEmptyReplies && body.fields.keys == setOf("targetId") && target?.startsWith("comment-replies-item-") == true &&
            root.fields.keys.all { it in setOf("responseContext", "trackingParams", "onResponseReceivedEndpoints") }
        require(arrays.size == 1 || omitted)
        val raw = if (omitted) emptyList() else arrayAt(body, arrays.single()); require(raw.size <= 1000)
        val items = ArrayList<Json>()
        val direct = directHeader(root); if (direct.fields.isNotEmpty()) items += obj("commentsHeaderRenderer" to direct)
        if (body.fields.containsKey("header")) {
            val declared = objectAt(body, "header"); require(declared.fields.keys == setOf("commentsHeaderRenderer"))
            items += obj("commentsHeaderRenderer" to header(objectAt(declared, "commentsHeaderRenderer")))
        }
        for (head in headers) {
            require(head.fields.keys.all { it in setOf("slot", "continuationItems", "targetId", "trackingParams") })
            require(head.fields["trackingParams"]?.let { it is Json.Str } != false)
            require(!head.fields.containsKey("targetId") || head.fields["targetId"] is Json.Str && (head.text("targetId") == target || head.text("targetId") == "comments-section-header"))
            for (value in arrayAt(head, "continuationItems")) {
                val wrapper = value as? Json.Obj ?: throw Invalid(); require(wrapper.fields.keys == setOf("commentsHeaderRenderer"))
                items += obj("commentsHeaderRenderer" to header(objectAt(wrapper, "commentsHeaderRenderer")))
            }
        }
        items += raw.mapNotNull(::slot)
        require(!channelCreationRequired || items.none { (it as? Json.Obj)?.obj("commentsHeaderRenderer")?.fields?.containsKey("createCommentEndpoint") == true })
        require(items.sumOf { (it as? Json.Obj)?.obj("commentsHeaderRenderer")?.let { head -> if (head.fields.containsKey("createCommentEndpoint")) 1 else 0 } ?: 0 } <= 1)
        require(items.sumOf { (it as? Json.Obj)?.obj("commentsHeaderRenderer")?.let { head -> if (head.fields.containsKey("sortFilterSubMenuRenderer")) 1 else 0 } ?: 0 } <= 1)
        val continuationRows = if (body.fields.containsKey("continuations")) arrayAt(body, "continuations") else emptyList()
        for (value in continuationRows) {
            val command = continuation(value as? Json.Obj ?: throw Invalid()); require(command != null)
            items += obj("continuationItemRenderer" to obj("continuationEndpoint" to command!!))
        }
        require(items.count { (it as? Json.Obj)?.fields?.containsKey("continuationItemRenderer") == true } <= 1)
        val collection = linkedMapOf<String, Json>("contents" to arr(items))
        target?.let { collection["targetId"] = Json.Str(it) }
        val fields = linkedMapOf<String, Json>("continuationContents" to obj("itemSectionContinuation" to Json.Obj(collection)))
        framework(root)?.let { fields["frameworkUpdates"] = it }
        val result = Json.Obj(fields)
        val parsed = commentPage(result)
        require(parsed.comments.mapNotNull { it.id }.size == ids.size && parsed.comments.mapNotNull { it.id }.toSet() == ids.toSet())
        return result
    }
    fun opening(root: Json.Obj, videoId: String?): Json.Obj {
        budget(root); metadata(root)
        if (videoId != null) {
            if (root.fields.containsKey("currentVideoEndpoint")) require(root.path("currentVideoEndpoint", "watchEndpoint")?.text("videoId") == videoId)
            root.obj("videoDetails")?.text("videoId")?.let { require(it == videoId) }
        }
        val panels = ArrayList<Pair<String, Json.Obj>>()
        fun panel(value: Json.Obj?, role: String) {
            if (value?.text("panelIdentifier") in setOf("engagement-panel-comments-section", "comment-item-section")) panels += role to value!!
        }
        panel(root.obj("engagementPanelSectionListRenderer"), "CANONICAL_PANEL")
        for (value in root.arr("engagementPanels")) panel((value as? Json.Obj)?.obj("engagementPanelSectionListRenderer"), "WATCH_PANEL")
        val sections = ArrayList<Pair<String, Json.Obj>>()
        fun section(value: Json.Obj?, role: String) {
            if (value?.text("sectionIdentifier") == "comment-item-section" || value?.text("targetId") == "comments-section") sections += role to value!!
        }
        section(root.obj("itemSectionRenderer"), "ROOT_SECTION")
        if (root.fields["contents"] is Json.Arr) for (value in root.arr("contents")) section((value as? Json.Obj)?.obj("itemSectionRenderer"), "ROOT_CONTENTS_SECTION")
        for (layout in listOf("twoColumnWatchNextResults", "singleColumnWatchNextResults")) {
            val results = root.path("contents", layout, "results", "results")
            for (value in results?.arr("contents").orEmpty()) section((value as? Json.Obj)?.obj("itemSectionRenderer"), "WATCH_RESULTS_SECTION")
        }
        require(panels.size <= 1 && sections.size <= 1)
        val dualSurface = panels.isNotEmpty() && sections.isNotEmpty()
        if (dualSurface) {
            // The watch page can expose independent inline and engagement-panel discussions.
            // MSC selects the panel, never combines their different cursors or capabilities.
            require(panels.single().first == "WATCH_PANEL" && sections.single().first == "WATCH_RESULTS_SECTION")
            require(videoId != null && root.path("currentVideoEndpoint", "watchEndpoint")?.text("videoId") == videoId)
            require(root.fields.keys.none { it in setOf("createCommentEndpoint", "createCommentParams", "sortFilterSubMenuRenderer", "commentsHeaderRenderer") })
        }
        val ownedPanel = panels.singleOrNull()?.second
        val selected = if (ownedPanel != null) objectAt(ownedPanel, "content") else sections.singleOrNull()?.second
        val candidates = ArrayList<Json.Obj>()
        if (selected != null) {
            val parent = route(ownedPanel?.let(::endpointMetadata), endpointMetadata(selected))
            continuation(selected, parent)?.let(candidates::add)
            selected.obj("continuationItemRenderer")?.let { continuation(it, parent)?.let(candidates::add) }
            for (value in selected.arr("contents")) (value as? Json.Obj)?.obj("continuationItemRenderer")?.let { continuation(it, parent)?.let(candidates::add) }
            for (value in selected.arr("continuations")) continuation(value as? Json.Obj ?: throw Invalid(), parent)?.let(candidates::add)
            val sectionList = selected.obj("sectionListRenderer")
            val listParent = route(parent, sectionList?.let(::endpointMetadata))
            for (value in sectionList?.arr("contents").orEmpty()) {
                val section = (value as? Json.Obj)?.obj("itemSectionRenderer") ?: throw Invalid()
                val sectionRoute = route(listParent, endpointMetadata(section))
                for (item in section.arr("contents")) (item as? Json.Obj)?.obj("continuationItemRenderer")?.let { continuation(it, sectionRoute)?.let(candidates::add) }
                for (item in section.arr("continuations")) continuation(item as? Json.Obj ?: throw Invalid(), sectionRoute)?.let(candidates::add)
            }
        }
        require(candidates.size <= 1)
        val result = linkedMapOf<String, Json>()
        // The direct watch-page message is a presentation role, never a source of commands.
        root.obj("messageRenderer")?.let { result["messageRenderer"] = presentation(it) }
        if (selected != null) {
            val panel = linkedMapOf<String, Json>("panelIdentifier" to Json.Str("engagement-panel-comments-section"),
                "content" to (candidates.singleOrNull()?.let { obj("continuationItemRenderer" to obj("continuationEndpoint" to it)) } ?: obj()))
            ownedPanel?.path("header", "engagementPanelTitleHeaderRenderer")?.fields?.get("contextualInfo")?.let {
                panel["header"] = obj("engagementPanelTitleHeaderRenderer" to obj("contextualInfo" to presentation(it)))
            }
            result["engagementPanelSectionListRenderer"] = Json.Obj(panel)
            val headers = listOfNotNull(if (dualSurface) null else directHeader(root), if (dualSurface) null else root.obj("commentsHeaderRenderer")?.let(::header), ownedPanel?.path("header", "commentsHeaderRenderer")?.let(::header),
                selected.path("header", "commentsHeaderRenderer")?.let(::header), selected.obj("commentsHeaderRenderer")?.let(::header))
            val combined = linkedMapOf<String, Json>()
            for (head in headers) for ((name, value) in head.fields) { require(name !in combined); combined[name] = value }
            require(!channelCreationRequired || "createCommentEndpoint" !in combined)
            if (combined.isNotEmpty()) result["commentsHeaderRenderer"] = Json.Obj(combined)
            if (!dualSurface) root.obj("commentsEntryPointRenderer")?.let { entry -> entry.fields["commentCount"]?.let { result["commentsEntryPointRenderer"] = obj("commentCount" to presentation(it)) } }
        }
        return Json.Obj(result)
    }
    fun mutation(root: Json.Obj): Json.Obj {
        budget(root); metadata(root)
        val created = ArrayList<Json.Obj>()
        if (root.fields.containsKey("actions")) {
            val actions = arrayAt(root, "actions"); require(actions.size <= 8)
            for (value in actions) {
                val action = value as? Json.Obj ?: throw Invalid()
                val declared = listOf("createCommentAction", "createCommentReplyAction").filter(action.fields::containsKey)
                if (declared.isEmpty()) continue
                require(declared.size == 1)
                val name = declared.single()
                require(action.fields.keys.all { it == name || it in opaque })
                val command = objectAt(action, name)
                val reply = name == "createCommentReplyAction"
                val targets = if (reply) setOf("parentCommentId", "replyToCommentId") else emptySet()
                require(command.fields.keys.all { it == "contents" || it in targets || it in opaque })
                if (reply) {
                    require(targets.any(command.fields::containsKey))
                    for (target in targets) if (command.fields.containsKey(target)) nonblank(command, target)
                }
                val contents = objectAt(command, "contents")
                val renderers = if (reply) setOf("commentThreadRenderer", "commentViewModel")
                    else setOf("commentRenderer", "commentThreadRenderer", "commentViewModel")
                require(contents.fields.size == 1 && contents.fields.keys.single() in renderers)
                created += contents
            }
        }
        require(created.size <= 1)
        // Only the declared creation result or a direct returned comment can mint a model.
        if (root.fields.keys.any { it in setOf("continuationContents", "onResponseReceivedEndpoints", "onResponseReceivedActions", "onResponseReceivedCommands") }) {
            require(created.isEmpty())
            return CommentProjection().response(Json.Obj(root.fields.filterKeys { it in opaque || it in setOf("frameworkUpdates", "mutations", "continuationContents", "onResponseReceivedEndpoints", "onResponseReceivedActions", "onResponseReceivedCommands", "createCommentEndpoint", "createCommentParams", "sortFilterSubMenuRenderer") }), false)
        }
        val comments = listOf("commentRenderer", "commentThreadRenderer", "commentViewModel").filter(root.fields::containsKey)
        require(comments.size + created.size <= 1)
        val returned = created.singleOrNull() ?: comments.singleOrNull()?.let { obj(it to root.fields.getValue(it)) }
            ?: return obj()
        val projected = requireNotNull(slot(returned))
        val fields = linkedMapOf<String, Json>("continuationContents" to obj("itemSectionContinuation" to obj("contents" to arr(listOf(projected)))))
        framework(root)?.let { fields["frameworkUpdates"] = it }
        return Json.Obj(fields)
    }
}
