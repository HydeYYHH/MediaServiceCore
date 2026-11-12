package com.liskovsoft.youtubeapi.videoinfo.V2

data class NoticePage(val notices: List<Notice>, val nextKey: String?)
/** Bell badge count: unseen notifications, independent of an individual notification's read flag. */
data class NoticeCount(val unseenCount: Long)
fun noticePage(body: String): NoticePage {
    val root = jsonObject(body)
    return NoticePage(notices(body), root?.let(::pageKey))
}

internal fun readNoticePage(root: Json.Obj): ReadResult<NoticePage> {
    val page = notificationResponsePage(root)
    return if (page != null) ReadResult(page) else ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_notification_page"))
}

fun noticeCount(body: String): ReadResult<NoticeCount> {
    val root = jsonObject(body) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_json"))
    val values = ArrayList<Long>()
    var malformed = false
    fun collect(node: Json.Obj) {
        val count = node.long("unseenCount")
        if (count == null || count < 0) malformed = true else values.add(count)
    }
    if (root.fields.containsKey("unseenCount")) collect(root)
    for (field in listOf("actions", "onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
        if (!root.fields.containsKey(field)) continue
        val commands = root.fields[field] as? Json.Arr
        if (commands == null) { malformed = true; continue }
        for (command in commands.items) {
            val node = command as? Json.Obj
            if (node == null) { malformed = true; continue }
            if (!node.fields.containsKey("updateNotificationsUnseenCountAction")) continue
            val action = node.obj("updateNotificationsUnseenCountAction")
            if (action == null) malformed = true else collect(action)
        }
    }
    val count = values.distinct().singleOrNull()
    return if (!malformed && count != null) ReadResult(NoticeCount(count))
        else ReadResult(error = RequestError(RequestFailure.PARSE, "missing_notification_count"))
}

fun loadNoticeCount(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<NoticeCount> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val reply = requestObject(youtubeQuery(YOUTUBE_API + "notification/get_unseen_count", "", context), http)
    return if (reply.ok) noticeCount(reply.value!!.encode()) else ReadResult(error = reply.error)
}
fun loadNotices(context: RequestContext, continuation: String? = null, http: HttpClient = JvmHttpClient()): ReadResult<NoticePage> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH,"sign_in_required"))
    val query = if (continuation == null) queryInContext(noticeQuery(context.visitor),context) else
        youtubeQuery(YOUTUBE_API+"notification/get_notification_menu", payload("continuation" to continuation),context)
    val reply = requestObject(query,http)
    return if (reply.ok) readNoticePage(reply.value!!) else ReadResult(error=reply.error)
}

fun markNoticeRead(notice: Notice, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    val command = notice.readCommand ?: return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "missing_notification_read_command"))
    return noticeInteraction(command.interactions, context, http)
}

private fun noticeInteraction(token: String, context: RequestContext, http: HttpClient): ReadResult<ActionReply> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return requestAction(youtubeQuery(YOUTUBE_API + "notification/record_interactions",
        payload("serializedRecordNotificationInteractionsRequest" to token), context), http, entityScope = ActionEntityScope.None, context = context)
}
fun hideNotice(token: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (token.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_notification_token"))
    return noticeInteraction(token,context,http)
}
fun setNoticeState(params: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (params.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_notification_params"))
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return requestAction(youtubeQuery(YOUTUBE_API + "notification/modify_channel_preference", payload("params" to params), context),
        http, entityScope = ActionEntityScope.None, context = context)
}
/** Confirms only a replacement button for this channel and this known server-issued preference. */
fun setNoticeState(channelId: String, state: NoticeState, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (!channelId.matches(Regex("UC[A-Za-z0-9_-]{22}")))
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_channel_id"))
    if (state.params.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_notification_params"))
    if (state.disabled) return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "notification_preference_disabled"))
    val expected = state.stateId?.takeIf { it in setOf(0, 2, 3) }
        ?: return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "unknown_notification_preference"))
    val response = setNoticeState(state.params, context, http)
    if (!response.ok) return response
    val original = response.value!!
    val root = jsonObject(original.body) ?: return response
    val matches = noticePreferenceAcknowledged(root, channelId, expected)
    // Reuse the common error/status rules. Domain evidence cannot override a failed or unknown status.
    val checked = actionReply(root, confirmsState = matches, entityScope = ActionEntityScope.None)
    if (!checked.ok) return ReadResult(error = checked.error?.copy(status = original.httpStatus))
    val confirmed = matches && checked.value!!.confirmed
    return ReadResult(original.copy(state = if (confirmed) ActionState.CONFIRMED else ActionState.UNCONFIRMED,
        metadata = original.metadata + mapOf("confirmation" to if (confirmed) "notification_button_replace" else "unconfirmed",
            "notification_response_ack" to original.state.name, "notification_expected_state" to expected.toString())))
}
fun sendFeedback(token: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<FeedbackReply> {
    if (token.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_feedback_token"))
    val response = signedAction("feedback","\"feedbackTokens\":[${jsonQuote(token)}]",context,http)
    if (!response.ok) return ReadResult(error=response.error)
    val action = response.value!!
    val reply = feedbackReply(action.body)
    return if (reply.processed) ReadResult(reply) else ReadResult(error=RequestError(RequestFailure.HTTP,"feedback_not_processed", status = action.httpStatus))
}
