package com.liskovsoft.youtubeapi.videoinfo.V2

private fun textNode(value: String?) = Json.Obj(mapOf("simpleText" to (value?.let { Json.Str(it) } ?: Json.Null)))

internal fun commentEntityRenderers(root: Json.Obj, section: Json.Obj): List<Json.Obj> {
    val entities = root.objects("commentEntityPayload").associateBy { it.text("key") ?: it.obj("properties")?.text("commentId") }
    val states = root.objects("commentToolbarStateEntityPayload").associateBy { it.text("key") }
    val surfaces = root.objects("commentToolbarSurfaceEntityPayload").associateBy { it.text("key") }
    val views = section.objects("commentViewModel").filter { it.text("commentKey") != null }.distinctBy { it.text("commentKey") }
    val ordered = if (views.isEmpty()) entities.values.map { it to null } else views.mapNotNull { view ->
        entities[view.text("commentKey")]?.let { it to view }
    }
    return ordered.mapNotNull { (entity, view) ->
        val properties = entity.obj("properties") ?: return@mapNotNull null
        val id = properties.text("commentId") ?: return@mapNotNull null
        val key = entity.text("key") ?: id
        val author = entity.obj("author")
        val toolbar = entity.obj("toolbar")
        val state = states[view?.text("toolbarStateKey")]
        val surface = surfaces[view?.text("toolbarSurfaceKey")]
        val liked = state?.text("likeState") == "TOOLBAR_LIKE_STATE_LIKED"
        val thread = section.objects("commentThreadRenderer").firstOrNull { commentViewKey(it.obj("commentViewModel") ?: it.obj("comment")) == key }
        val parent = properties.text("parentCommentId") ?: section.objects("commentThreadRenderer").firstNotNull { candidate ->
            if (candidate.obj("replies")?.objects("commentViewModel")?.any { it.text("commentKey") == key } != true) null
            else entities[commentViewKey(candidate.obj("commentViewModel") ?: candidate.obj("comment"))]?.obj("properties")?.text("commentId")
        }
        val fields = linkedMapOf<String, Json>("commentId" to Json.Str(id), "entityKey" to Json.Str(key),
            "authorText" to textNode(author?.text("displayName")),
            "contentText" to Json.Obj(mapOf("runs" to Json.Arr(attributedRuns(properties.obj("content"))))),
            "publishedTimeText" to textNode(properties.text("publishedTime")),
            "voteCount" to textNode(if (liked) toolbar?.text("likeCountLiked") ?: toolbar?.text("likeCountNotliked") else toolbar?.text("likeCountNotliked")),
            "replyCount" to textNode(toolbar?.text("replyCount")), "isLiked" to Json.Bool(liked))
        author?.text("avatarThumbnailUrl")?.let { fields["authorThumbnail"] = Json.Obj(mapOf("thumbnails" to Json.Arr(listOf(Json.Obj(mapOf("url" to Json.Str(it))))))) }
        parent?.let { fields["parentCommentId"] = Json.Str(it) }
        thread?.obj("replies")?.let { fields["replies"] = it }
        surface?.let {
            fields["toolbarSurface"] = it
            fields["actionButtons"] = normalizedToolbar(it, state)
            fields["actionMenu"] = it
        }
        Json.Obj(fields)
    }
}

private fun commentViewKey(node: Json.Obj?): String? = node?.text("commentKey")
    ?: node?.objects("commentViewModel")?.firstOrNull { it.text("commentKey") != null }?.text("commentKey")

private fun normalizedToolbar(surface: Json.Obj, state: Json.Obj?): Json.Obj {
    val buttons = LinkedHashMap<String, Json>()
    for (name in listOf("likeButton", "dislikeButton")) {
        val value = surface.obj(name) ?: continue
        val button = value.obj("toggleButtonRenderer") ?: value
        fun endpoint(toggled: Boolean): Json.Obj? {
            val source = button.obj(if (toggled) "toggledServiceEndpoint" else "defaultServiceEndpoint")
                ?: if (!toggled) button.obj("innertubeCommand") ?: button.obj("onTap") else null
            return source?.objects("performCommentActionEndpoint")?.firstOrNull()?.let { Json.Obj(mapOf("performCommentActionEndpoint" to it)) }
        }
        val fields = LinkedHashMap<String, Json>()
        endpoint(false)?.let { fields["defaultServiceEndpoint"] = it }
        endpoint(true)?.let { fields["toggledServiceEndpoint"] = it }
        fields["isToggled"] = Json.Bool(state?.text("likeState") == if (name == "likeButton") "TOOLBAR_LIKE_STATE_LIKED" else "TOOLBAR_LIKE_STATE_DISLIKED")
        buttons[name] = Json.Obj(mapOf("toggleButtonRenderer" to Json.Obj(fields)))
    }
    return Json.Obj(mapOf("commentActionButtonsRenderer" to Json.Obj(buttons)))
}

private data class TextSpan(val start: Int, val end: Int, val value: Json.Obj)
private fun spans(content: Json.Obj, name: String, text: String): List<TextSpan> = content.arr(name).mapNotNull {
    val value = it as? Json.Obj ?: return@mapNotNull null
    val start = value.int("startIndex") ?: return@mapNotNull null
    val length = value.int("length") ?: return@mapNotNull null
    if (start < 0 || length <= 0 || start > text.length || length > text.length - start) return@mapNotNull null
    fun boundary(index: Int) = index == 0 || index == text.length || !(Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index]))
    if (!boundary(start) || !boundary(start + length)) return@mapNotNull null
    TextSpan(start, start + length, value)
}

private fun attributedRuns(content: Json.Obj?): List<Json> {
    if (content == null) return emptyList()
    if (content.fields["runs"] is Json.Arr) return content.arr("runs")
    val text = content.text("content") ?: return emptyList()
    val commands = spans(content, "commandRuns", text)
    val styles = spans(content, "styleRuns", text)
    val attachments = spans(content, "attachmentRuns", text)
    val bounds = (listOf(0, text.length) + (commands + styles + attachments).flatMap { listOf(it.start, it.end) }).distinct().sorted()
    return bounds.zipWithNext().mapNotNull { (start, end) ->
        if (start == end) return@mapNotNull null
        val fields = linkedMapOf<String, Json>("text" to Json.Str(text.substring(start, end)))
        commands.firstOrNull { it.start <= start && it.end >= end }?.value?.let { command ->
            (command.path("onTap", "innertubeCommand") ?: command.obj("navigationEndpoint"))?.let { fields["navigationEndpoint"] = it }
        }
        attachments.firstOrNull { it.start <= start && it.end >= end }?.value?.objects("emoji")?.firstOrNull()?.let { fields["emoji"] = it }
        val active = styles.filter { it.start <= start && it.end >= end }.map { it.value }
        fields["bold"] = Json.Bool(active.any { it.text("fontWeight") == "FONT_WEIGHT_BOLD" || it.bool("bold") == true })
        fields["italic"] = Json.Bool(active.any { it.text("fontStyle") == "FONT_STYLE_ITALIC" || it.bool("italic") == true })
        fields["strikethrough"] = Json.Bool(active.any { it.bool("strikethrough") == true })
        Json.Obj(fields)
    }
}

private fun <T, R : Any> Iterable<T>.firstNotNull(block: (T) -> R?): R? {
    for (item in this) block(item)?.let { return it }
    return null
}

internal fun commentOrder(section: Json): List<String> {
    val keys = ArrayList<String>()
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> {
                node.obj("commentRenderer")?.text("commentId")?.let { keys.add(it) }
                node.text("commentKey")?.let { keys.add(it) }
                node.fields.values.forEach { visit(it) }
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(section)
    return keys.distinct()
}
