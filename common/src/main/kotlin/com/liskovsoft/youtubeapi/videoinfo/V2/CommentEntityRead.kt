package com.liskovsoft.youtubeapi.videoinfo.V2

private fun textNode(value: String?) = Json.Obj(mapOf("simpleText" to (value?.let { Json.Str(it) } ?: Json.Null)))

internal fun commentEntityRenderers(root: Json.Obj, section: Json.Obj): List<Json.Obj> {
    // YoutubeIE._extract_comment/_comment_entries, yt-dlp@897341241f2bfb2439fe0367f4c7c9ccd29a3828.
    val entities = entityPayloads(root, "commentEntityPayload")
    val states = entityPayloads(root, "commentToolbarStateEntityPayload", "engagementToolbarStateEntityPayload")
    val surfaces = entityPayloads(root, "commentToolbarSurfaceEntityPayload", "engagementToolbarSurfaceEntityPayload")
    // YouTube.js CommentView.applyMutations/applyCommentsMutations: attachment surfaces have their own key.
    // Never borrow another comment's attachment merely because it occurs in the same response.
    val commentSurfaces = root.objects("commentSurfaceEntityPayload").filter { !it.text("key").isNullOrBlank() }
    val views = section.objects("commentViewModel").filter { it.text("commentKey") != null }.distinctBy { it.text("commentKey") }
    val ordered = if (views.isEmpty()) entities.values.map { it to null } else views.mapNotNull { view ->
        entities[view.text("commentKey")]?.let { it to view }
    }
    return ordered.mapNotNull { (entity, view) ->
        val properties = entity.obj("properties") ?: return@mapNotNull null
        val id = properties.text("commentId") ?: return@mapNotNull null
        val key = view?.text("commentKey") ?: entity.text("key") ?: id
        val author = entity.obj("author")
        val toolbar = entity.obj("toolbar")
        val state = states[view?.text("toolbarStateKey")]
        val surface = surfaces[view?.text("toolbarSurfaceKey")]
        val contentSurface = view?.text("commentSurfaceKey")?.takeIf(String::isNotBlank)?.let { surfaceKey ->
            commentSurfaces.filter { it.text("key") == surfaceKey }.distinct().singleOrNull()
        }
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
            "voteCount" to textNode((if (liked) toolbar?.scalar("likeCountLiked") else null)
                ?: toolbar?.scalar("likeCountNotliked") ?: toolbar?.scalar("likeCountA11y")),
            "replyCount" to textNode(toolbar?.scalar("replyCount")),
            "isCreator" to Json.Bool(author?.bool("isCreator") == true), "isVerified" to Json.Bool(author?.bool("isVerified") == true),
            "isPinned" to Json.Bool(view?.fields?.containsKey("pinnedText") == true))
        val viewerRating = when (state?.text("likeState")) {
            "TOOLBAR_LIKE_STATE_LIKED" -> "LIKE"
            "TOOLBAR_LIKE_STATE_DISLIKED" -> "DISLIKE"
            "TOOLBAR_LIKE_STATE_INDIFFERENT" -> "INDIFFERENT"
            else -> null
        }
        viewerRating?.let { fields["voteStatus"] = Json.Str(it) }
        author?.text("channelId")?.let { fields["authorEndpoint"] = Json.Obj(mapOf("browseEndpoint" to Json.Obj(mapOf("browseId" to Json.Str(it))))) }
        state?.text("heartState")?.let { fields["isHearted"] = Json.Bool(it == "TOOLBAR_HEART_STATE_HEARTED") }
        author?.text("avatarThumbnailUrl")?.let { fields["authorThumbnail"] = Json.Obj(mapOf("thumbnails" to Json.Arr(listOf(Json.Obj(mapOf("url" to Json.Str(it))))))) }
        parent?.let { fields["parentCommentId"] = Json.Str(it) }
        thread?.obj("replies")?.let { fields["replies"] = it }
        contentSurface?.let { fields["commentSurface"] = it }
        surface?.let {
            fields["toolbarSurface"] = it
            fields["actionButtons"] = normalizedToolbar(it, state)
            fields["actionMenu"] = it
        }
        Json.Obj(fields)
    }
}

internal fun commentImageAttachment(renderer: Json.Obj): CommentImageAttachment? {
    val image = renderer.path("commentSurface", "imageAttachmentViewModel", "commentImageAttachmentViewModel") ?: return null
    return CommentImageAttachment(readArtwork(image.obj("image")), image.int("width")?.takeIf { it > 0 },
        image.int("height")?.takeIf { it > 0 }, image.scalar("aspectRatio")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 },
        image.display("unavailableText"), image.display("unsupportedText"))
}

internal fun commentVoiceReply(renderer: Json.Obj): CommentVoiceReply? {
    val voice = renderer.path("commentSurface", "voiceReplyContainerViewModel", "voiceReplyContainerViewModel") ?: return null
    return CommentVoiceReply(voice.display("transcriptText"), voice.display("voiceReplyUnavailableText"))
}

private fun entityPayloads(root: Json.Obj, vararg names: String): Map<String, Json.Obj> {
    val values = LinkedHashMap<String, Json.Obj>()
    for (name in names) for (payload in root.objects(name)) {
        (payload.text("key") ?: payload.obj("properties")?.text("commentId"))?.let { values[it] = payload }
    }
    val mutations = root.arr("mutations") + root.path("frameworkUpdates", "entityBatchUpdate")?.arr("mutations").orEmpty()
    for (value in mutations) {
        val mutation = value as? Json.Obj ?: continue
        val key = mutation.text("entityKey") ?: continue
        for (name in names) mutation.obj("payload")?.obj(name)?.let { values[key] = it }
    }
    return values
}

private fun commentViewKey(node: Json.Obj?): String? = node?.text("commentKey")
    ?: node?.objects("commentViewModel")?.firstOrNull { it.text("commentKey") != null }?.text("commentKey")

private fun normalizedToolbar(surface: Json.Obj, state: Json.Obj?): Json.Obj {
    val buttons = LinkedHashMap<String, Json>()
    for (name in listOf("likeButton", "dislikeButton")) {
        val value = surface.obj(name) ?: Json.Obj(emptyMap())
        val button = value.obj("toggleButtonRenderer") ?: value
        fun endpoint(toggled: Boolean): Json.Obj? {
            val command = when (name) {
                "likeButton" -> if (toggled) "unlikeCommand" else "likeCommand"
                else -> if (toggled) "undislikeCommand" else "dislikeCommand"
            }
            val source = button.obj(if (toggled) "toggledServiceEndpoint" else "defaultServiceEndpoint")
                ?: if (!toggled) button.obj("innertubeCommand") ?: button.obj("onTap") else null
            return (source ?: surface.obj(command))?.objects("performCommentActionEndpoint")?.firstOrNull()
                ?.let { Json.Obj(mapOf("performCommentActionEndpoint" to it)) }
        }
        val fields = LinkedHashMap<String, Json>()
        endpoint(false)?.let { fields["defaultServiceEndpoint"] = it }
        endpoint(true)?.let { fields["toggledServiceEndpoint"] = it }
        state?.text("likeState")?.takeIf { it in setOf("TOOLBAR_LIKE_STATE_LIKED", "TOOLBAR_LIKE_STATE_DISLIKED", "TOOLBAR_LIKE_STATE_INDIFFERENT") }?.let {
            fields["isToggled"] = Json.Bool(it == if (name == "likeButton") "TOOLBAR_LIKE_STATE_LIKED" else "TOOLBAR_LIKE_STATE_DISLIKED")
        }
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
