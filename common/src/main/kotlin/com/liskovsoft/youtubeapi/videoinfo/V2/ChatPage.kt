package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChatMessage(val id: String?, val author: String?, val text: String?, val photo: String?, val timestampUs: Long?, val kind: String)
data class ChatRemoval(val messageId: String?, val authorId: String?)
sealed class ChatAction {
    data class Add(val message: ChatMessage) : ChatAction()
    data class Remove(val removal: ChatRemoval) : ChatAction()
}
data class ChatPage @JvmOverloads constructor(val messages: List<ChatMessage>, val removals: List<ChatRemoval>, val nextKey: String?, val waitMs: Long,
    val actions: List<ChatAction> = emptyList(), val events: List<ChatEvent> = emptyList(), val replay: Boolean = false)
fun chatPage(body: String): ChatPage {
    val root = jsonObject(body)
    val continuation = root?.path("continuationContents","liveChatContinuation") ?: root
    val events = ArrayList<ChatEvent>()
    val kinds = setOf("liveChatTextMessageRenderer","liveChatPaidMessageRenderer","liveChatMembershipItemRenderer","liveChatPaidStickerRenderer")
    fun visit(node: Json, offsetMs: Long? = null) {
        when(node) {
            is Json.Obj -> {
                for((kind,value) in node.fields) {
                    val item=value as? Json.Obj
                    when {
                        kind == "replayChatItemAction" && item != null -> visit(item.fields["actions"] ?: Json.Arr(emptyList()),
                            item.long("videoOffsetTimeMsec")?.takeIf { it >= 0 })
                        kind in kinds && item!=null -> events.add(ChatEvent(offsetMs, ChatAction.Add(ChatMessage(item.text("id"),item.display("authorName"),
                            item.display("message") ?: item.display("headerSubtext"),item.thumbnail("authorPhoto"),item.long("timestampUsec"),kind))))
                        kind=="markChatItemAsDeletedAction" && item!=null -> events.add(ChatEvent(offsetMs, ChatAction.Remove(ChatRemoval(item.text("targetItemId"),null))))
                        kind=="markChatItemsByAuthorAsDeletedAction" && item!=null -> events.add(ChatEvent(offsetMs, ChatAction.Remove(ChatRemoval(null,item.text("externalChannelId")))))
                        else -> visit(value, offsetMs)
                    }
                }
            }
            is Json.Arr -> node.items.forEach { visit(it, offsetMs) }
            else -> Unit
        }
    }
    if(continuation!=null) visit(continuation)
    val actions = events.map { it.action }
    val messages=actions.filterIsInstance<ChatAction.Add>().map {it.message}
    val removals=actions.filterIsInstance<ChatAction.Remove>().map {it.removal}
    val replay = continuation?.objects("liveChatReplayContinuationData")?.firstOrNull()
    val next = replay ?: continuation?.objects("timedContinuationData")?.firstOrNull()
        ?: continuation?.objects("invalidationContinuationData")?.firstOrNull()
        ?: continuation?.objects("reloadContinuationData")?.firstOrNull()
    return ChatPage(messages,removals,next?.text("continuation"),(next?.long("timeoutMs") ?: 1000).coerceIn(100,60000),actions,events,
        replay != null || events.any { it.offsetMs != null })
}
fun loadChat(continuation: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<ChatPage> {
    if (continuation.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_chat_key"))
    val response=requestObject(youtubeQuery(YOUTUBE_API+"live_chat/get_live_chat",payload("continuation" to continuation),context),http)
    return if(response.ok) ReadResult(chatPage(response.value!!.encode())) else ReadResult(error=response.error)
}
class ChatConnection(private val context: RequestContext = RequestContext(), private val http: HttpClient = JvmHttpClient(),
    wait: ((Long) -> Unit)? = null) : java.io.Closeable {
    @Volatile private var closed = false
    private val waiting = ConnectionWait(wait)
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { closed = true; waiting.close() }

    fun listen(key: String, receive: (ChatPage) -> Unit): ReadResult<Boolean> {
        if (!running.compareAndSet(false, true)) return ReadResult(error = RequestError(RequestFailure.INVALID, "already_listening"))
        try {
            var continuation: String? = key
            val seen = java.util.LinkedHashSet<String>()
            while (!closed && !Thread.currentThread().isInterrupted && continuation != null) {
                val reply = loadChat(continuation, context, http)
                if (closed) break
                if (!reply.ok) return ReadResult(error = reply.error)
                val page = uniqueChat(reply.value!!, seen)
                receive(page)
                continuation = page.nextKey
                if (!closed && continuation != null) waiting.pause(page.waitMs)
            }
            return if (Thread.currentThread().isInterrupted) ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled")) else ReadResult(true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        } finally { running.set(false) }
    }
}
