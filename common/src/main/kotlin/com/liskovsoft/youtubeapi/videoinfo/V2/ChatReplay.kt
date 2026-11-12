package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChatEvent(val offsetMs: Long?, val action: ChatAction)

// YoutubeLiveChatFD.real_download / parse_actions_replay, yt-dlp@897341241f2bfb2439fe0367f4c7c9ccd29a3828.

fun loadChatReplay(continuation: String, positionMs: Long = 0, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient()): ReadResult<ChatPage> {
    if (continuation.isBlank() || positionMs < 0) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_chat_replay"))
    val fields = payload("continuation" to continuation) + ",\"currentPlayerState\":{${payload("playerOffsetMs" to positionMs.toString())}}"
    val response = requestObject(youtubeQuery(YOUTUBE_API + "live_chat/get_live_chat_replay", fields, context), http)
    if (!response.ok) return ReadResult(error = response.error)
    val page = readChatPage(response.value!!)
    return if (page.ok) ReadResult(page.value!!.copy(replay = true)) else page
}

class ChatReplay(private val key: String, session: AccountSession = AccountSession(),
    private val http: HttpClient = JvmHttpClient()) : java.io.Closeable {
    @Volatile private var closed = false
    private var next: String? = key
    private var position = -1L
    private val seen = java.util.LinkedHashSet<String>()
    private val binding = ChatSessionBinding(session)
    init { require(key.isNotBlank()) }

    override fun close() { closed = true }

    /** Allows coroutine hosts to inject a fresh, cancellable transport for each page. */
    @Synchronized fun read(positionMs: Long, http: HttpClient = this.http): ReadResult<ChatPage> {
        if (closed) return ReadResult(error = RequestError(RequestFailure.CANCELLED, "closed"))
        if (positionMs < 0) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_chat_position"))
        val context = binding.snapshot()
        if (!context.ok) return ReadResult(error = context.error)
        if (positionMs < position) { next = key; seen.clear() }
        val continuation = next ?: return ReadResult(ChatPage(emptyList(), emptyList(), null, 0, replay = true))
        val result = loadChatReplay(continuation, positionMs, context.value!!, http)
        if (closed) return ReadResult(error = RequestError(RequestFailure.CANCELLED, "closed"))
        val current = binding.snapshot()
        if (!current.ok) return ReadResult(error = current.error)
        if (!result.ok) return result
        val page = uniqueChat(result.value!!, seen)
        next = page.nextKey
        position = positionMs
        return ReadResult(page)
    }

    @Synchronized fun seek(positionMs: Long): ReadResult<ChatPage> {
        if (positionMs < 0) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_chat_position"))
        next = key
        seen.clear()
        return read(positionMs)
    }
}

internal fun uniqueChat(page: ChatPage, seen: MutableSet<String>): ChatPage {
    val events = page.events.filter { event ->
        val action = event.action
        action !is ChatAction.Add || action.message.id == null || seen.add(action.message.id)
    }
    while (seen.size > 1000) seen.remove(seen.first())
    val actions = events.map { it.action }
    return page.copy(messages = actions.filterIsInstance<ChatAction.Add>().map { it.message },
        removals = actions.filterIsInstance<ChatAction.Remove>().map { it.removal }, actions = actions, events = events)
}
