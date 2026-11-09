package com.liskovsoft.youtubeapi.videoinfo.V2

// Write operation: never retry automatically. Live probes require explicit opt-in.
fun sendChatMessage(params: String, text: String, context: RequestContext, http: HttpClient = JvmHttpClient(),
    clientMessageId: String = java.util.UUID.randomUUID().toString()): ReadResult<ActionReply> {
    if (params.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_chat_key"))
    val message = text.trim()
    if (message.isEmpty() || message.length > 200) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_chat_message"))
    val fields = payload("params" to params, "clientMessageId" to clientMessageId) +
        ",\"richMessage\":{\"textSegments\":[{\"text\":${jsonQuote(message)}}]}"
    var failure: Pair<Int, String?>? = null
    val transport = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply = http.execute(request).also { response ->
            if (response.status !in 200..299) failure = response.status to jsonObject(response.body)?.obj("error")?.text("status")
        }
    }
    val reply = signedAction("live_chat/send_message", fields, context, transport)
    val rejected = failure
    if (reply.error != null && rejected != null) {
        val code = when {
            rejected.first == 429 -> "chat_rate_limited"
            rejected.second in setOf("PERMISSION_DENIED", "FAILED_PRECONDITION") -> "chat_posting_restricted"
            else -> null
        }
        if (code != null) return ReadResult(error = reply.error.copy(kind = RequestFailure.HTTP, code = code))
    }
    val value = reply.value ?: return reply
    val root = jsonObject(value.body)
    val rejection = root?.obj("errorMessage")?.objects("liveChatTextActionsErrorMessageRenderer")?.firstOrNull()?.display("errorText")
    if (!rejection.isNullOrBlank()) return ReadResult(error = RequestError(RequestFailure.HTTP,
        "chat_message_rejected", value.httpStatus, detail = rejection.take(300)))
    // HTTP success can also carry an attestation challenge or a refusal. Only a
    // server-issued message entity confirms posting; never clear a draft otherwise.
    val echoed = root?.objects("addChatItemAction")?.any { action ->
        action.obj("item")?.objects("liveChatTextMessageRenderer")?.any { !it.text("id").isNullOrBlank() } == true
    } == true
    return ReadResult(value.copy(state = if (echoed) ActionState.CONFIRMED else ActionState.UNCONFIRMED))
}
