package com.liskovsoft.youtubeapi.videoinfo.V2

// live_chat/send_message. Write operation: automation never calls this against the live
// service, so verification is by request-construction unit tests only.
fun sendChatMessage(params: String, text: String, context: RequestContext, http: HttpClient = JvmHttpClient(),
    clientMessageId: String = java.util.UUID.randomUUID().toString()): ReadResult<ActionReply> {
    if (params.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_chat_key"))
    val message = text.trim()
    if (message.isEmpty() || message.length > 200) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_chat_message"))
    val fields = payload("params" to params, "clientMessageId" to clientMessageId) +
        ",\"richMessage\":{\"textSegments\":[{\"text\":${jsonQuote(message)}}]}"
    val reply = signedAction("live_chat/send_message", fields, context, http)
    val value = reply.value ?: return reply
    // HTTP success can also carry an attestation challenge or a refusal. Only a
    // server-issued message entity confirms posting; never clear a draft otherwise.
    val echoed = jsonObject(value.body)?.objects("addChatItemAction")?.any { action ->
        action.obj("item")?.objects("liveChatTextMessageRenderer")?.any { !it.text("id").isNullOrBlank() } == true
    } == true
    return ReadResult(value.copy(state = if (echoed) ActionState.CONFIRMED else ActionState.UNCONFIRMED))
}
