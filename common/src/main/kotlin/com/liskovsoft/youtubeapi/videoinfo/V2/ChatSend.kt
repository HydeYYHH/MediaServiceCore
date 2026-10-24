package com.liskovsoft.youtubeapi.videoinfo.V2

// live_chat/send_message. Write operation: automation never calls this against the live
// service, so verification is by request-construction unit tests only.
fun sendChatMessage(params: String, text: String, context: RequestContext, http: HttpClient = JvmHttpClient(),
    clientMessageId: String = java.util.UUID.randomUUID().toString().replace("-", "")): ReadResult<ActionReply> {
    if (params.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_chat_key"))
    val message = text.trim()
    if (message.isEmpty() || message.length > 200) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_chat_message"))
    val fields = payload("params" to params, "clientMessageId" to clientMessageId) +
        ",\"richMessage\":{\"textRuns\":[{\"text\":${jsonQuote(message)}}]}"
    return signedAction("live_chat/send_message", fields, context, http)
}
