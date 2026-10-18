package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChatMessage(val id: String?, val author: String?, val text: String?, val photo: String?, val timestampUs: Long?, val kind: String)
data class ChatRemoval(val messageId: String?, val authorId: String?)
data class ChatPage(val messages: List<ChatMessage>, val removals: List<ChatRemoval>, val nextKey: String?, val waitMs: Long)
fun chatPage(body: String): ChatPage {
    val root = jsonObject(body)
    val continuation = root?.path("continuationContents","liveChatContinuation") ?: root
    val messages = ArrayList<ChatMessage>()
    for (kind in listOf("liveChatTextMessageRenderer","liveChatPaidMessageRenderer","liveChatMembershipItemRenderer","liveChatPaidStickerRenderer")) {
        continuation?.objects(kind)?.forEach {
            messages.add(ChatMessage(it.text("id"),it.display("authorName"),it.display("message") ?: it.display("headerSubtext"),
                it.thumbnail("authorPhoto"),it.long("timestampUsec"),kind))
        }
    }
    val removals = continuation?.objects("markChatItemAsDeletedAction").orEmpty().map { ChatRemoval(it.text("targetItemId"),null) } +
        continuation?.objects("markChatItemsByAuthorAsDeletedAction").orEmpty().map { ChatRemoval(null,it.text("externalChannelId")) }
    val next = continuation?.objects("timedContinuationData")?.firstOrNull()
        ?: continuation?.objects("invalidationContinuationData")?.firstOrNull()
        ?: continuation?.objects("reloadContinuationData")?.firstOrNull()
    return ChatPage(messages,removals,next?.text("continuation"),(next?.long("timeoutMs") ?: 1000).coerceIn(100,60000))
}
fun loadChat(continuation: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<ChatPage> {
    if (continuation.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_chat_key"))
    val response=requestObject(youtubeQuery(YOUTUBE_API+"live_chat/get_live_chat",payload("continuation" to continuation),context),http)
    return if(response.ok) ReadResult(chatPage(response.value!!.encode())) else ReadResult(error=response.error)
}
class ChatConnection(private val context: RequestContext = RequestContext(), private val http: HttpClient = JvmHttpClient(),
    private val wait: (Long)->Unit = Thread::sleep) : java.io.Closeable {
    @Volatile private var closed=false
    override fun close() { closed=true }
    fun listen(key:String, receive:(ChatPage)->Unit):ReadResult<Boolean> {
        var continuation:String?=key
        val seen=java.util.LinkedHashSet<String>()
        while(!closed && !Thread.currentThread().isInterrupted && continuation!=null) {
            val reply=loadChat(continuation,context,http)
            if(!reply.ok) return ReadResult(error=reply.error)
            val page=reply.value!!
            val unique=page.messages.filter { it.id==null || seen.add(it.id) }
            while(seen.size>1000) seen.remove(seen.first())
            receive(page.copy(messages=unique))
            continuation=page.nextKey
            if(!closed && continuation!=null) try { wait(page.waitMs) } catch(_:InterruptedException) {
                Thread.currentThread().interrupt(); return ReadResult(error=RequestError(RequestFailure.CANCELLED,"cancelled"))
            }
        }
        return ReadResult(true)
    }
}
