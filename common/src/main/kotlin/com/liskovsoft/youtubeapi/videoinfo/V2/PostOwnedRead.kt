package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

/** Network roles are projected directly; the permissive post fragment parsers never see raw responses. */
internal fun ownedChannelTabs(root: Json.Obj): List<BrowseTab>? = postOwned(root) { tabs(root).map { it.tab } }

internal fun ownedInitialChannelPosts(root: Json.Obj, channelId: String): PostPage? = postOwned(root) {
    val selected = tabs(root).singleOrNull { it.tab.selected }
    if (selected == null || selected.tab.kind != ChannelTabKind.POSTS) emptyPage()
    else collection(objectAt(selected.source, "content"), channelId)
}

/** This role is used only after the caller requested the channel's POSTS surface. */
internal fun ownedChannelPosts(root: Json.Obj, channelId: String, continuation: Boolean): PostPage? = postOwned(root) {
    if (continuation) continued(root, channelId) else {
        val directTabs = tabs(root)
        if (hasTabs(root)) {
            val selected = directTabs.singleOrNull { it.tab.selected }
            require(selected != null && selected.tab.kind == ChannelTabKind.POSTS)
            collection(objectAt(selected!!.source, "content"), channelId)
        } else collection(root, channelId)
    }
}

internal fun ownedPostDocument(root: Json.Obj, requestedPostId: String): PostPage? = postOwned(root) {
    document(root, requestedPostId)
}

private fun <T> postOwned(root: Json.Obj, block: PostProjection.() -> T): T? =
    try { PostProjection().apply { budget(root); rootScope(root); require(!root.fields.containsKey("error")) }.block() }
    catch (_: PostProjection.Invalid) { null }

private class PostProjection {
    class Invalid : RuntimeException()
    fun require(condition: Boolean) { if (!condition) throw Invalid() }
    fun objectAt(node: Json.Obj, name: String): Json.Obj = (node.fields[name] as? Json.Obj).also { require(it != null) }!!
    private fun arrayAt(node: Json.Obj, name: String): List<Json> =
        (node.fields[name] as? Json.Arr).also { require(it != null) }!!.items
    private fun string(node: Json.Obj, name: String): String = node.text(name).also { require(!it.isNullOrBlank()) }!!
    private fun optionalString(node: Json.Obj, name: String): String? {
        require(!node.fields.containsKey(name) || node.fields[name] is Json.Str)
        return node.text(name)
    }
    private fun optionalBool(node: Json.Obj, name: String): Boolean? {
        require(!node.fields.containsKey(name) || node.fields[name] is Json.Bool)
        return node.bool(name)
    }
    private var nodes = 0
    fun budget(value: Json, depth: Int = 0) {
        require(++nodes <= 125_000 && depth <= 96)
        when (value) {
            is Json.Obj -> value.fields.values.forEach { budget(it, depth + 1) }
            is Json.Arr -> value.items.forEach { budget(it, depth + 1) }
            else -> Unit
        }
    }
    private val metadata = setOf("trackingParams", "clickTrackingParams", "responseContext", "loggingDirectives",
        "accessibility", "accessibilityData", "rendererContext", "commandMetadata")
    private fun fields(node: Json.Obj, allowed: Set<String>) {
        require(node.fields.keys.all { it in allowed || it in metadata })
        for (name in listOf("trackingParams", "clickTrackingParams")) optionalString(node, name)
        for (name in metadata - setOf("trackingParams", "clickTrackingParams"))
            require(!node.fields.containsKey(name) || node.fields[name] is Json.Obj)
    }
    private fun text(node: Json.Obj, name: String): String? {
        val value = node.fields[name] ?: return null
        if (value is Json.Str) return value.value
        require(value is Json.Obj)
        val obj = value as Json.Obj
        // Navigation/formatting on text runs is presentation, never a source of commands.
        optionalString(obj, "simpleText")?.let { return it }
        optionalString(obj, "content")?.let { return it }
        if (!obj.fields.containsKey("runs")) return null
        return arrayAt(obj, "runs").joinToString("") {
            require(it is Json.Obj); stringOrEmpty(it as Json.Obj, "text")
        }.takeIf { it.isNotEmpty() }
    }
    private fun stringOrEmpty(node: Json.Obj, name: String): String {
        require(node.fields[name] is Json.Str); return node.text(name)!!
    }
    private fun thumbnail(node: Json.Obj, name: String): String? {
        if (!node.fields.containsKey(name)) return null
        val image = objectAt(node, name)
        val urls = listOf("thumbnails", "sources").flatMap { key ->
            if (!image.fields.containsKey(key)) emptyList() else arrayAt(image, key).map { entry ->
                require(entry is Json.Obj); string(entry as Json.Obj, "url")
            }
        }
        return urls.lastOrNull()
    }
    fun emptyPage() = PostPage(emptyList(), null)

    fun rootScope(root: Json.Obj) {
        val roles = setOf("contents", "sectionListRenderer", "richGridRenderer", "itemSectionRenderer",
            "backstagePostRenderer", "postRenderer", "continuationContents", "onResponseReceivedActions",
            "onResponseReceivedEndpoints", "onResponseReceivedCommands")
        require(root.fields.keys.count { it in roles } <= 1)
    }

    fun document(root: Json.Obj, requestedPostId: String): PostPage {
        require(postIdAccepted(requestedPostId))
        val scan = Scan(null, document = true)
        val direct = listOf("backstagePostRenderer", "postRenderer").filter(root.fields::containsKey)
        require(direct.size <= 1)
        if (direct.isNotEmpty()) {
            require(!root.fields.containsKey("contents"))
            scan.post(objectAt(root, direct.single()))
        } else if (layout(root) != null) {
            // /post documents use a selected primary WEB tab without a channel-tab route.
            // The requested post identity, not a title or a metadata descendant, owns this read.
            val selected = tabs(root).singleOrNull { it.tab.selected }
            require(selected != null)
            scan.container(objectAt(selected!!.source, "content"))
        } else scan.container(root)
        require(scan.items.size == 1 && scan.items.single().postId == requestedPostId)
        return PostPage(scan.items.toList(), null)
    }

    data class Tab(val tab: BrowseTab, val source: Json.Obj)
    private fun layout(root: Json.Obj): Json.Obj? {
        val contents = root.fields["contents"] ?: return null
        if (contents is Json.Arr) return null // Explicit legacy collection, never channel tab capabilities.
        require(contents is Json.Obj)
        val body = contents as Json.Obj
        val kinds = listOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer").filter(body.fields::containsKey)
        require(kinds.size <= 1)
        if (kinds.isEmpty()) return null
        fields(body, setOf(kinds.single()))
        api(body, "/youtubei/v1/browse")
        return objectAt(body, kinds.single())
    }
    fun hasTabs(root: Json.Obj) = layout(root) != null
    fun tabs(root: Json.Obj): List<Tab> {
        api(root, "/youtubei/v1/browse")
        val layout = layout(root) ?: return emptyList()
        api(layout, "/youtubei/v1/browse")
        require(listOf("continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints",
            "onResponseReceivedCommands").none(root.fields::containsKey))
        val result = arrayAt(layout, "tabs").map { raw ->
            require(raw is Json.Obj); val wrapper = raw as Json.Obj
            val renderers = listOf("tabRenderer", "expandableTabRenderer").filter(wrapper.fields::containsKey)
            require(renderers.size == 1)
            fields(wrapper, setOf(renderers.single()))
            api(wrapper, "/youtubei/v1/browse")
            val tab = objectAt(wrapper, renderers.single())
            api(tab, "/youtubei/v1/browse")
            val selected = optionalBool(tab, "selected") == true
            val identifier = optionalString(tab, "tabIdentifier")
            val candidates = listOf("endpoint", "navigationEndpoint", "serviceEndpoint").filter(tab.fields::containsKey)
            require(candidates.size <= 1)
            val command = candidates.singleOrNull()?.let { objectAt(tab, it) }
            var id: String? = null; var params: String? = null; var url: String? = null
            if (command != null) {
                fields(command, setOf("browseEndpoint"))
                api(command, "/youtubei/v1/browse")
                command.fields["browseEndpoint"]?.let {
                    val endpoint = objectAt(command, "browseEndpoint")
                    fields(endpoint, setOf("browseId", "params", "canonicalBaseUrl"))
                    id = string(endpoint, "browseId")
                    params = optionalString(endpoint, "params")
                    optionalString(endpoint, "canonicalBaseUrl")
                }
                if (command.fields.containsKey("commandMetadata")) {
                    val meta = objectAt(command, "commandMetadata")
                    if (meta.fields.containsKey("webCommandMetadata"))
                        url = optionalString(objectAt(meta, "webCommandMetadata"), "url")
                }
            }
            Tab(BrowseTab(text(tab, "title"), id, params, selected, channelTabKind(identifier, url)), tab)
        }
        require(result.count { it.tab.selected } <= 1)
        return result
    }

    fun collection(root: Json.Obj, channelId: String): PostPage = Scan(channelId).apply { container(root) }.page()
    fun continued(root: Json.Obj, channelId: String): PostPage {
        api(root, "/youtubei/v1/browse")
        val envelopes = listOf("continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints",
            "onResponseReceivedCommands").filter(root.fields::containsKey)
        require(envelopes.size <= 1)
        if (envelopes.isEmpty()) return collection(root, channelId) // Explicit legacy contents[] only.
        require(!root.fields.containsKey("contents"))
        val scan = Scan(channelId)
        if (envelopes.single() == "continuationContents") {
            val envelope = objectAt(root, "continuationContents")
            api(envelope, "/youtubei/v1/browse")
            val names = listOf("sectionListContinuation", "itemSectionContinuation", "richGridContinuation").filter(envelope.fields::containsKey)
            require(names.size == 1); fields(envelope, setOf(names.single()))
            val content = objectAt(envelope, names.single())
            scan.list(content)
        } else {
            val actions = arrayAt(root, envelopes.single())
            require(actions.size == 1)
            val action = actions.single() as? Json.Obj; require(action != null)
            api(action!!, "/youtubei/v1/browse")
            val names = listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand").filter(action!!.fields::containsKey)
            require(names.size == 1); fields(action, setOf(names.single()))
            val content = objectAt(action, names.single())
            api(content, "/youtubei/v1/browse")
            fields(content, setOf("continuationItems", "targetId", "slot"))
            postRole(content)
            scan.slots(arrayAt(content, "continuationItems"))
        }
        return scan.page()
    }

    private fun postRole(node: Json.Obj) {
        val target = optionalString(node, "targetId")
        // The public WEB item-section matcher and observed POSTS responses use this exact role.
        // Arbitrary strings still cannot declare an empty post collection or a continuation owner.
        require(target == null || target == "backstage-item-section")
        val section = optionalString(node, "sectionIdentifier")
        require(section == null || section == "backstage-item-section")
        val slot = optionalString(node, "slot")
        require(slot == null || slot == "RELOAD_CONTINUATION_SLOT_BODY")
    }

    inner class Scan(private val channelId: String?, private val document: Boolean = false) {
        val items = ArrayList<PostItem>()
        private var next: String? = null
        private var slots = 0
        fun page() = PostPage(items.toList(), next)
        fun container(node: Json.Obj) {
            rootScope(node)
            if (!document) api(node, "/youtubei/v1/browse")
            val names = listOf("contents", "sectionListRenderer", "richGridRenderer", "itemSectionRenderer").filter(node.fields::containsKey)
            require(names.size == 1)
            when (names.single()) {
                "contents" -> when (val content = node.fields["contents"]) {
                    is Json.Arr -> slots(content.items)
                    is Json.Obj -> container(content)
                    else -> throw Invalid()
                }
                else -> list(objectAt(node, names.single()))
            }
        }
        fun list(node: Json.Obj) {
            if (!document) api(node, "/youtubei/v1/browse")
            fields(node, setOf("contents", "continuations", "targetId", "sectionIdentifier", "header", "subMenu",
                "isPreviousContinuation", "disablePullToRefresh", "disableReloadContinuation"))
            postRole(node)
            slots(arrayAt(node, "contents"))
            if (node.fields.containsKey("continuations")) {
                val values = arrayAt(node, "continuations"); require(values.size <= 1)
                values.forEach { raw ->
                    require(raw is Json.Obj); val entry = raw as Json.Obj
                    api(entry, "/youtubei/v1/browse")
                    val kinds = listOf("nextContinuationData", "reloadContinuationData").filter(entry.fields::containsKey)
                    require(kinds.size == 1); fields(entry, setOf(kinds.single()))
                    val data = objectAt(entry, kinds.single()); api(data, "/youtubei/v1/browse")
                    cursor(string(data, "continuation"))
                }
            }
        }
        fun slots(values: List<Json>) { values.forEach(::slot) }
        private fun slot(raw: Json) {
            require(++slots <= 2_000 && raw is Json.Obj)
            val node = raw as Json.Obj
            if (node.isAdvertisement()) return
            if (!document) api(node, "/youtubei/v1/browse")
            val names = node.fields.keys - metadata
            require(names.size == 1)
            val name = names.single(); val value = objectAt(node, name)
            fields(node, setOf(name))
            when (name) {
                "backstagePostRenderer", "postRenderer" -> post(value)
                "backstagePostThreadRenderer" -> {
                    if (!document) api(value, "/youtubei/v1/browse")
                    fields(value, setOf("post", "comments", "replies", "commentTargetTitle", "commentTarget", "useUpdatedRepostUi"))
                    optionalBool(value, "useUpdatedRepostUi")
                    slot(objectAt(value, "post"))
                }
                "richItemRenderer" -> {
                    if (!document) api(value, "/youtubei/v1/browse")
                    fields(value, setOf("content", "renderedFromContent", "isSlimMedia")); slot(objectAt(value, "content"))
                }
                "sectionListRenderer", "richGridRenderer" -> list(value)
                "itemSectionRenderer" -> {
                    if (document && value.text("sectionIdentifier") in setOf("comment-item-section", "comments-section")) return
                    list(value)
                }
                "continuationItemRenderer" -> if (!document) cursor(continuation(value))
                "messageRenderer" -> require(!text(value, "text").isNullOrBlank())
                else -> throw Invalid()
            }
        }
        private fun cursor(value: String) {
            require(next == null && value.isNotBlank() && value.length <= 65_536); next = value
        }
        fun post(node: Json.Obj) {
            val value = item(node, channelId)
            require(items.none { it.postId == value.postId }); items += value
        }
    }

    private fun continuation(node: Json.Obj): String {
        fields(node, setOf("continuationEndpoint", "button", "trigger", "request"))
        api(node, "/youtubei/v1/browse")
        val endpoint = node.fields["continuationEndpoint"]?.let { objectAt(node, "continuationEndpoint") }
        val button = node.fields["button"]?.let {
            val wrapper = objectAt(node, "button"); api(wrapper, "/youtubei/v1/browse")
            objectAt(wrapper, "buttonRenderer").also { api(it, "/youtubei/v1/browse") }
        }
        require((endpoint != null) != (button != null))
        val command = endpoint ?: button!!.let {
            val carriers = listOf("command", "continuationCommand").filter(it.fields::containsKey)
            require(carriers.size == 1)
            if (carriers.single() == "command") objectAt(it, "command") else it
        }
        fields(command, setOf("continuationCommand")); api(command, "/youtubei/v1/browse")
        val value = objectAt(command, "continuationCommand")
        fields(value, setOf("token", "request"))
        return string(value, "token")
    }

    private fun item(node: Json.Obj, channelId: String?): PostItem {
        val id = string(node, "postId"); require(postIdAccepted(id))
        val author = node.fields["authorEndpoint"]?.let { endpoint ->
            require(endpoint is Json.Obj)
            val browse = objectAt(endpoint as Json.Obj, "browseEndpoint")
            string(browse, "browseId")
        } ?: channelId
        val avatar = thumbnail(node, "authorThumbnail")?.let { if (it.startsWith("//")) "https:$it" else it }
        val actions = node.fields["actionButtons"]?.let { buttons(objectAt(node, "actionButtons"), id) }
        val attachment = node.fields["backstageAttachment"]?.let { attachment(objectAt(node, "backstageAttachment"), id) }
        val vote = optionalString(node, "voteStatus")
        require(vote == null || vote in setOf("LIKE", "DISLIKE", "INDIFFERENT"))
        return PostItem(author, id, text(node, "authorText"), avatar, text(node, "publishedTimeText"),
            text(node, "contentText"), attachment?.images.orEmpty(), text(node, "voteCount"), text(node, "replyCount"),
            vote, actions?.like, actions?.unlike, actions?.dislike, actions?.undislike, attachment?.poll,
            actions?.signIn == true || attachment?.signIn == true, attachment?.video, attachment?.quiz)
    }
    private data class Buttons(val like: PostCommand?, val unlike: PostCommand?, val dislike: PostCommand?,
        val undislike: PostCommand?, val signIn: Boolean)
    private fun buttons(node: Json.Obj, postId: String): Buttons {
        val wrappers = listOf("postActionButtonsRenderer", "commentActionButtonsRenderer").filter(node.fields::containsKey)
        require(wrappers.size <= 1)
        val buttons = if (wrappers.isEmpty()) node else {
            fields(node, setOf(wrappers.single())); objectAt(node, wrappers.single())
        }
        // Other own UI buttons are deliberately not traversed, including their menus/metadata.
        fields(buttons, setOf("likeButton", "dislikeButton", "replyButton", "shareButton", "menuRenderer", "menu", "style"))
        optionalString(buttons, "style")
        var signIn = false
        fun button(name: String, toggled: Boolean, status: String): PostCommand? {
            if (!buttons.fields.containsKey(name)) return null
            val raw = objectAt(buttons, name)
            val toggle = if (raw.fields.containsKey("toggleButtonRenderer")) {
                fields(raw, setOf("toggleButtonRenderer")); objectAt(raw, "toggleButtonRenderer")
            } else raw
            val field = if (toggled) "toggledServiceEndpoint" else "defaultServiceEndpoint"
            val navigation = if (toggled) "toggledNavigationEndpoint" else "defaultNavigationEndpoint"
            val carriers = listOf(field, navigation).filter(toggle.fields::containsKey)
            require(carriers.size <= 1)
            if (carriers.isEmpty()) return null
            val endpoint = objectAt(toggle, carriers.single())
            if (carriers.single() == navigation) {
                // A navigation sign-in gate is a restriction, never an alternative mutation command.
                fields(endpoint, setOf("signInEndpoint")); objectAt(endpoint, "signInEndpoint")
                for (ancestor in listOf(node, buttons, raw, toggle, endpoint)) api(ancestor, null)
                signIn = true; return null
            }
            if (endpoint.fields.containsKey("signInEndpoint")) {
                fields(endpoint, setOf("signInEndpoint")); objectAt(endpoint, "signInEndpoint"); signIn = true; return null
            }
            return command(endpoint, postId, status).also {
                for (ancestor in listOf(node, buttons, raw, toggle)) api(ancestor, "/youtubei/v1/" + it.endpoint)
            }
        }
        val like = button("likeButton", false, "LIKE"); val unlike = button("likeButton", true, "INDIFFERENT")
        val dislike = button("dislikeButton", false, "DISLIKE"); val undislike = button("dislikeButton", true, "INDIFFERENT")
        return Buttons(like, unlike, dislike, undislike, signIn)
    }
    private data class Attachment(val images: List<String> = emptyList(), val poll: PostPoll? = null,
        val signIn: Boolean = false, val video: MediaItem? = null, val quiz: PostQuiz? = null)
    private fun attachment(node: Json.Obj, postId: String): Attachment {
        val names = node.fields.keys - metadata
        require(names.size == 1); val name = names.single(); fields(node, setOf(name))
        val value = objectAt(node, name)
        return when (name) {
            "backstageImageRenderer" -> Attachment(listOfNotNull(thumbnail(value, "image")))
            "postMultiImageRenderer" -> {
                fields(value, setOf("images"))
                val images = arrayAt(value, "images"); require(images.size in 1..100)
                Attachment(images.map { raw ->
                    require(raw is Json.Obj); val wrapper = raw as Json.Obj
                    fields(wrapper, setOf("backstageImageRenderer"))
                    val image = objectAt(wrapper, "backstageImageRenderer")
                    thumbnail(image, "image").also { require(!it.isNullOrBlank()) }!!
                })
            }
            "pollRenderer" -> poll(value, postId, node).let { Attachment(poll = it.first, signIn = it.second) }
            "videoRenderer" -> Attachment(video = video(value))
            "quizRenderer" -> Attachment(quiz = postQuizAttachment(value).also { require(it != null) })
            else -> throw Invalid()
        }
    }
    /** A closed, owned presentation role. Video UI commands never become post capabilities or list cursors. */
    private fun video(node: Json.Obj): MediaItem {
        fields(node, setOf("videoId", "thumbnail", "title", "descriptionSnippet", "longBylineText", "shortBylineText", "ownerText",
            "publishedTimeText", "lengthText", "viewCountText", "shortViewCountText", "navigationEndpoint", "showActionMenu",
            "ownerBadges", "thumbnailOverlays", "menu", "channelThumbnailSupportedRenderers", "avatar"))
        val id = string(node, "videoId"); require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        for (name in listOf("title", "descriptionSnippet", "longBylineText", "shortBylineText", "ownerText", "publishedTimeText",
            "lengthText", "viewCountText", "shortViewCountText")) text(node, name)
        if (node.fields.containsKey("thumbnail")) thumbnail(node, "thumbnail")
        optionalBool(node, "showActionMenu")
        // These proven presentation containers are bounded by the document budget and deliberately not traversed.
        // In particular their nested post renderers, menu endpoints and continuation commands have no owning list role.
        for (name in listOf("menu", "channelThumbnailSupportedRenderers", "avatar"))
            if (node.fields.containsKey(name)) objectAt(node, name)
        for (name in listOf("ownerBadges", "thumbnailOverlays")) if (node.fields.containsKey(name)) {
            val values = arrayAt(node, name); require(values.size <= 100)
            values.forEach { require(it is Json.Obj) }
        }
        if (node.fields.containsKey("navigationEndpoint")) {
            val navigation = objectAt(node, "navigationEndpoint")
            fields(navigation, setOf("watchEndpoint")); api(navigation, null)
            val watch = objectAt(navigation, "watchEndpoint")
            fields(watch, setOf("videoId", "playerParams", "watchEndpointSupportedOnesieConfig")); api(watch, null)
            require(string(watch, "videoId") == id)
            // Opaque navigation presentation only: type-check without parsing or copying to the display item.
            optionalString(watch, "playerParams")
            if (watch.fields.containsKey("watchEndpointSupportedOnesieConfig")) objectAt(watch, "watchEndpointSupportedOnesieConfig")
        }
        val channels = ArrayList<String>()
        for (name in listOf("longBylineText", "shortBylineText", "ownerText")) {
            val line = node.obj(name) ?: continue
            if (!line.fields.containsKey("runs")) continue
            for (raw in arrayAt(line, "runs")) {
                val run = raw as? Json.Obj; require(run != null)
                if (!run!!.fields.containsKey("navigationEndpoint")) continue
                val navigation = objectAt(run, "navigationEndpoint")
                fields(navigation, setOf("browseEndpoint")); api(navigation, "/youtubei/v1/browse")
                val browse = objectAt(navigation, "browseEndpoint")
                fields(browse, setOf("browseId", "canonicalBaseUrl")); api(browse, "/youtubei/v1/browse")
                val channel = string(browse, "browseId"); require(Regex("UC[A-Za-z0-9_-]{22}").matches(channel))
                optionalString(browse, "canonicalBaseUrl"); channels += channel
            }
        }
        require(channels.distinct().size <= 1)
        return postVideoAttachment(node).also { require(it != null) }!!
    }

    private fun poll(node: Json.Obj, postId: String, attachment: Json.Obj): Pair<PostPoll, Boolean> {
        val rawChoices = arrayAt(node, "choices"); require(rawChoices.size in 1..100)
        var signIn = false
        val choices = rawChoices.map { raw ->
            require(raw is Json.Obj); val choice = raw as Json.Obj
            val selected = optionalBool(choice, "selected"); val isSelected = optionalBool(choice, "isSelected")
            require(selected == null || isSelected == null || selected == isSelected)
            fun endpoint(name: String): PostCommand? {
                if (!choice.fields.containsKey(name)) return null
                val endpoint = objectAt(choice, name)
                if (endpoint.fields.containsKey("signInEndpoint")) {
                    fields(endpoint, setOf("signInEndpoint")); objectAt(endpoint, "signInEndpoint"); signIn = true; return null
                }
                return command(endpoint, postId).also {
                    for (ancestor in listOf(attachment, node, choice)) api(ancestor, "/youtubei/v1/" + it.endpoint)
                }
            }
            PostPollChoice(text(choice, "text").also { require(!it.isNullOrBlank()) }!!, thumbnail(choice, "image"),
                selected == true || isSelected == true, text(choice, "votePercentage") ?: text(choice, "votePercentageIfNotSelected"),
                text(choice, "votePercentageIfSelected"), endpoint("selectServiceEndpoint"), endpoint("deselectServiceEndpoint"))
        }
        // sign-in has no command and cannot be borrowed from an unrelated choice child.
        val known = choices.any { it.selected } || rawChoices.all {
            val choice = it as Json.Obj; choice.bool("selected") != null || choice.bool("isSelected") != null
        }
        return PostPoll(choices, text(node, "totalVotes"), choices.any { it.selected }, known) to signIn
    }

    /** Optional metadata may corroborate a fixed endpoint; it can never invent one. */
    private fun api(node: Json.Obj, expected: String?) {
        if (!node.fields.containsKey("commandMetadata")) return
        val metadata = objectAt(node, "commandMetadata")
        if (!metadata.fields.containsKey("webCommandMetadata")) return
        val web = objectAt(metadata, "webCommandMetadata")
        if (!web.fields.containsKey("apiUrl")) return
        val uri = runCatching { URI(string(web, "apiUrl")) }.getOrNull(); require(uri != null)
        require(expected != null) // Pure navigation gates have no service-API role.
        require(uri!!.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery in listOf(null, "prettyPrint=false") &&
            uri.port in listOf(-1, 443) && (uri.scheme == null && uri.rawAuthority == null ||
                uri.scheme == "https" && uri.host == "www.youtube.com") && uri.path == expected)
    }
    private fun command(node: Json.Obj, postId: String, expectedStatus: String? = null, depth: Int = 0): PostCommand {
        require(depth <= 8)
        val keys = listOf("innertubeCommand", "command", "commandExecutorCommand", "likeEndpoint", "feedbackEndpoint",
            "performCommentActionEndpoint").filter(node.fields::containsKey)
        require(keys.size == 1); fields(node, setOf(keys.single()))
        return when (val key = keys.single()) {
            "innertubeCommand", "command", "commandExecutorCommand" -> {
                var executorMetadata: Json.Obj? = null
                val child = if (key == "commandExecutorCommand") {
                    val executor = objectAt(node, key); fields(executor, setOf("commands"))
                    executorMetadata = executor
                    val commands = arrayAt(executor, "commands"); require(commands.size == 1)
                    commands.single() as? Json.Obj ?: throw Invalid()
                } else objectAt(node, key)
                command(child, postId, expectedStatus, depth + 1).also {
                    api(node, "/youtubei/v1/" + it.endpoint)
                    executorMetadata?.let { container -> api(container, "/youtubei/v1/" + it.endpoint) }
                }
            }
            "likeEndpoint" -> {
                val like = objectAt(node, key)
                fields(like, setOf("status", "target", "likeParams", "dislikeParams", "removeLikeParams"))
                val status = string(like, "status")
                require(status in setOf("LIKE", "DISLIKE", "INDIFFERENT") && (expectedStatus == null || status == expectedStatus))
                val target = objectAt(like, "target"); fields(target, setOf("postId"))
                require(string(target, "postId") == postId)
                val endpoint = when (status) { "LIKE" -> "like/like"; "DISLIKE" -> "like/dislike"; else -> "like/removelike" }
                api(node, "/youtubei/v1/$endpoint")
                for (name in listOf("likeParams", "dislikeParams", "removeLikeParams")) optionalString(like, name)
                val params = like.text(when (status) { "LIKE" -> "likeParams"; "DISLIKE" -> "dislikeParams"; else -> "removeLikeParams" })
                PostCommand(endpoint, "\"target\":{\"postId\":" + jsonQuote(postId) + "}" +
                    params?.let { ",\"params\":" + jsonQuote(it) }.orEmpty())
            }
            "feedbackEndpoint" -> {
                val feedback = objectAt(node, key); fields(feedback, setOf("feedbackToken", "clientActions"))
                api(node, "/youtubei/v1/feedback")
                var total: String? = null
                if (feedback.fields.containsKey("clientActions")) {
                    val actions = arrayAt(feedback, "clientActions"); require(actions.size <= 1)
                    actions.forEach { raw ->
                        require(raw is Json.Obj); val action = raw as Json.Obj
                        fields(action, setOf("updateBackstagePollAction"))
                        val update = objectAt(action, "updateBackstagePollAction"); fields(update, setOf("totalVotes"))
                        total = text(update, "totalVotes")
                    }
                }
                PostCommand("feedback", "\"feedbackTokens\":[" + jsonQuote(string(feedback, "feedbackToken")) + "]", total)
            }
            else -> {
                val action = objectAt(node, key); fields(action, setOf("action", "clientActions"))
                // WEB's request builder reads only action/actions. Client UI actions are never
                // executed, traversed, or copied into the single-action request issued here.
                if (action.fields.containsKey("clientActions")) require(arrayAt(action, "clientActions").size <= 64)
                api(node, "/youtubei/v1/comment/perform_comment_action")
                PostCommand("comment/perform_comment_action", "\"actions\":[" + jsonQuote(string(action, "action")) + "]")
            }
        }
    }
}
