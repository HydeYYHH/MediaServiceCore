package com.liskovsoft.youtubeapi.videoinfo.V2

data class NoticePage(val notices: List<Notice>, val nextKey: String?)
fun noticePage(body: String): NoticePage {
    val root = jsonObject(body)
    return NoticePage(notices(body), root?.let(::pageKey))
}
fun loadNotices(context: RequestContext, continuation: String? = null, http: HttpClient = JvmHttpClient()): ReadResult<NoticePage> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH,"sign_in_required"))
    val query = if (continuation == null) queryInContext(noticeQuery(context.visitor),context) else
        youtubeQuery(YOUTUBE_API+"notification/get_notification_menu", payload("continuation" to continuation),context)
    val reply = requestObject(query,http)
    return if (reply.ok) ReadResult(noticePage(reply.value!!.encode())) else ReadResult(error=reply.error)
}
fun hideNotice(token: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (token.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_notification_token"))
    return signedAction("notification/record_interactions",payload("serializedRecordNotificationInteractionsRequest" to token),context,http)
}
fun setNoticeState(params: String, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    signedAction("notification/modify_channel_preference",payload("params" to params),context,http)
fun sendFeedback(token: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<FeedbackReply> {
    if (token.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_feedback_token"))
    val response = signedAction("feedback","\"feedbackTokens\":[${jsonQuote(token)}]",context,http)
    if (!response.ok) return ReadResult(error=response.error)
    val reply = feedbackReply(response.value!!.body)
    return if (reply.processed) ReadResult(reply) else ReadResult(error=RequestError(RequestFailure.HTTP,"feedback_not_processed", status = response.value.httpStatus))
}
