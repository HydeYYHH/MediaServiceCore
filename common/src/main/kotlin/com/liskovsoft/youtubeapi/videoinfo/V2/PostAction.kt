package com.liskovsoft.youtubeapi.videoinfo.V2

/** A server-issued command, restricted to post interaction endpoints. */
data class PostCommand(val endpoint: String, val payload: String, val pollTotal: String? = null)
data class PostPollChoice(val text: String, val image: String?, val selected: Boolean,
    val percentage: String?, val selectedPercentage: String?, val select: PostCommand?, val deselect: PostCommand?)
data class PostPoll @JvmOverloads constructor(val choices: List<PostPollChoice>, val total: String?, val voted: Boolean,
    /** Missing selection flags must not confirm that a vote was removed. */
    val selectionKnown: Boolean = false)

private val postEndpoints = setOf("like/like", "like/dislike", "like/removelike", "comment/perform_comment_action", "feedback")

internal fun postCommand(node: Json.Obj?): PostCommand? {
    node ?: return null
    node.obj("innertubeCommand")?.let { return postCommand(it) }
    node.obj("command")?.let { return postCommand(it) }
    node.obj("commandExecutorCommand")?.arr("commands")?.let { commands ->
        return commands.asSequence().mapNotNull { postCommand(it as? Json.Obj) }.firstOrNull()
    }
    val total = node.objects("updateBackstagePollAction").firstNotNullOfOrNull { it.display("totalVotes") }
    node.obj("feedbackEndpoint")?.text("feedbackToken")?.takeIf(String::isNotBlank)?.let {
        return PostCommand("feedback", "\"feedbackTokens\":[${jsonQuote(it)}]", total)
    }
    node.obj("performCommentActionEndpoint")?.text("action")?.takeIf(String::isNotBlank)?.let {
        return PostCommand("comment/perform_comment_action", "\"actions\":[${jsonQuote(it)}]", total)
    }
    node.obj("likeEndpoint")?.let { like ->
        val status = like.text("status")
        val endpoint = when (status) { "LIKE" -> "like/like"; "DISLIKE" -> "like/dislike"; "INDIFFERENT" -> "like/removelike"; else -> return null }
        val target = like.obj("target") ?: return null
        val params = like.text(when (status) { "LIKE" -> "likeParams"; "DISLIKE" -> "dislikeParams"; else -> "removeLikeParams" })
        return PostCommand(endpoint, "\"target\":${target.encode()}" + params?.let { ",\"params\":${jsonQuote(it)}" }.orEmpty())
    }
    val endpoint = node.path("commandMetadata", "webCommandMetadata")?.text("apiUrl")
        ?.removePrefix("/youtubei/v1/")?.substringBefore('?') ?: return null
    if (endpoint !in postEndpoints) return null
    val payload = node.fields.entries.firstOrNull { it.key.endsWith("Endpoint") }?.value as? Json.Obj ?: return null
    return PostCommand(endpoint, payload.fields.entries.joinToString(",") { jsonQuote(it.key) + ":" + it.value.encode() })
}

internal fun postPoll(poll: Json.Obj): PostPoll {
    val rawValues = poll.arr("choices")
    val rawChoices = rawValues.mapNotNull { it as? Json.Obj }
    val choices = rawChoices.map { choice ->
        PostPollChoice(choice.display("text").orEmpty(), choice.thumbnail("image"), choice.bool("selected") == true || choice.bool("isSelected") == true,
            choice.display("votePercentage") ?: choice.display("votePercentageIfNotSelected"), choice.display("votePercentageIfSelected"),
            postCommand(choice.obj("selectServiceEndpoint")), postCommand(choice.obj("deselectServiceEndpoint")))
    }
    val selected = choices.any { it.selected }
    val known = rawValues.size == rawChoices.size && (selected || rawChoices.isNotEmpty() && rawChoices.all {
        it.bool("selected") != null || it.bool("isSelected") != null
    })
    return PostPoll(choices, poll.display("totalVotes"), selected, known)
}

fun performPostCommand(command: PostCommand?, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (context.webSession?.signedIn != true) return ReadResult(error = RequestError(RequestFailure.AUTH, "post_web_authorization_required"))
    if (command == null || command.endpoint !in postEndpoints || command.payload.isBlank())
        return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "missing_post_action"))
    return requestAction(contextualQuery(webQuery(YOUTUBE_API + command.endpoint, command.payload, context.visitor),
        context), http, entityScope = ActionEntityScope.None, context = context)
}

/** Post ratings can acknowledge success with a vote update instead of a status field. */
fun updatedPost(reply: ActionReply, expected: PostItem): PostItem? {
    val root = jsonObject(reply.body) ?: return null
    posts(root, expected.authorId).firstOrNull {
        it.postId == expected.postId && it.voteStatus == expected.voteStatus &&
            it.poll?.choices?.map { choice -> choice.selected } == expected.poll?.choices?.map { choice -> choice.selected }
    }?.let { return it }
    val vote = root.objects("updateCommentVoteAction").firstOrNull {
        it.text("commentId") == expected.postId && it.text("voteStatus") == expected.voteStatus
    }
    if (vote != null) return expected.copy(likes = vote.display("voteCount") ?: expected.likes)
    if (!reply.confirmed) return null
    val total = root.objects("updateBackstagePollAction").firstNotNullOfOrNull { it.display("totalVotes") }
    return if (total != null) expected.copy(poll = expected.poll?.copy(total = total)) else expected
}
