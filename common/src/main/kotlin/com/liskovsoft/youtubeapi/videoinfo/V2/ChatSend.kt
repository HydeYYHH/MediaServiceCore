package com.liskovsoft.youtubeapi.videoinfo.V2

// Message writes are never retried automatically.
internal fun sendChatMessage(params: String, text: String, context: RequestContext, http: HttpClient = JvmHttpClient(),
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
    // Only a matching message in the endpoint's action list acknowledges this
    // submission. Unrelated/nested entities must not clear the caller's draft.
    val echoed = root?.let { response ->
        response.arr("actions").any { entry ->
            val action = (entry as? Json.Obj)?.obj("addChatItemAction") ?: return@any false
            val item = action.obj("item") ?: return@any false
            val entity = item.obj("liveChatTextMessageRenderer") ?: return@any false
            val returnedIds = listOf(response, action, item, entity).mapNotNull { it.fields["clientMessageId"] }
            !entity.text("id").isNullOrBlank() && entity.display("message") == message &&
                returnedIds.all { (it as? Json.Str)?.value == clientMessageId }
        }
    } == true
    return ReadResult(value.copy(state = if (echoed) ActionState.CONFIRMED else ActionState.UNCONFIRMED))
}

/** Address parameters belong to the account/channel that read the chat. The service enforces permission. */
class ChatSender internal constructor(private val params: String, private val binding: ChatSessionBinding,
    private val postingError: () -> RequestError?) {

    fun send(text: String, http: HttpClient = JvmHttpClient(),
        clientMessageId: String = java.util.UUID.randomUUID().toString()): ReadResult<ActionReply> {
        val context = binding.snapshot()
        if (!context.ok) return ReadResult(error = context.error)
        postingError()?.let { return ReadResult(error = it) }
        val result = sendChatMessage(params, text, context.value!!, http, clientMessageId)
        val current = binding.snapshot()
        return if (!current.ok) ReadResult(error = current.error) else result
    }
}
