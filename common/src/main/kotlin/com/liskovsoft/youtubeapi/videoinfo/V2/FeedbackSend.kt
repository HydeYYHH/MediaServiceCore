package com.liskovsoft.youtubeapi.videoinfo.V2

private const val FEEDBACK_URL = "https://www.youtube.com/youtubei/v1/feedback?prettyPrint=false"

private val emptyReply = FeedbackReply(false, null, emptyList())

data class FeedbackReason(val title: String?, val token: String)

data class FeedbackReply(val processed: Boolean, val title: String?, val reasons: List<FeedbackReason>)

fun feedbackTokens(body: String): List<String> {
    val root = jsonObject(body) ?: return emptyList()
    val tokens = ArrayList<String>()
    collectTokens(root, tokens)
    return tokens
}

fun feedbackQuery(token: String?, visitor: String?): PlayerQuery {
    if (token.isNullOrEmpty()) return PlayerQuery("", emptyMap(), "")
    return webQuery(FEEDBACK_URL, """"feedbackTokens":["${escapeJson(token)}"]""", visitor)
}

fun sendFeedback(
    token: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): FeedbackReply {
    val query = feedbackQuery(token, visitor)
    if (query.url.isEmpty()) return emptyReply
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return emptyReply
    }
    if (http.first != 200) return emptyReply
    return feedbackReply(http.second)
}

internal fun feedbackReply(body: String): FeedbackReply {
    val responses = jsonObject(body)?.fields?.get("feedbackResponses") as? Json.Arr ?: return emptyReply
    val first = responses.items.firstOrNull() as? Json.Obj ?: return emptyReply
    val dialog = first.obj("followUpDialog")?.obj("dismissalFollowUpRenderer")
    return FeedbackReply(
        first.bool("isProcessed") == true,
        displayText(dialog?.obj("dismissalReasonsPrompt")),
        reasons(dialog)
    )
}

private fun reasons(dialog: Json.Obj?): List<FeedbackReason> {
    val items = dialog?.fields?.get("reasons") as? Json.Arr ?: return emptyList()
    val reasons = ArrayList<FeedbackReason>()
    for (item in items.items) {
        val renderer = (item as? Json.Obj)?.obj("dismissalReasonTextRenderer") ?: continue
        val token = renderer.obj("feedback")?.obj("feedbackEndpoint")?.text("feedbackToken")
        if (token.isNullOrEmpty()) continue
        reasons.add(FeedbackReason(displayText(renderer.obj("text")), token))
    }
    return reasons
}

private fun collectTokens(node: Json, tokens: ArrayList<String>) {
    when (node) {
        is Json.Obj -> {
            val token = node.obj("feedbackEndpoint")?.text("feedbackToken")
            if (!token.isNullOrEmpty()) tokens.add(token)
            for (child in node.fields.values) collectTokens(child, tokens)
        }
        is Json.Arr -> for (child in node.items) collectTokens(child, tokens)
        else -> return
    }
}

private fun displayText(node: Json.Obj?): String? {
    if (node == null) return null
    val simple = node.text("simpleText")
    if (simple != null && simple.isNotEmpty()) return simple
    val runs = node.fields["runs"] as? Json.Arr ?: return null
    val out = StringBuilder()
    for (run in runs.items) {
        val text = (run as? Json.Obj)?.text("text")
        if (text != null) out.append(text)
    }
    return if (out.isEmpty()) null else out.toString()
}

private fun escapeJson(value: String): String = jsonEscape(value)
