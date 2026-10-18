package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChatMessage(val id: String?, val author: String?, val text: String?, val photo: String?, val timestampUs: Long?, val kind: String)
data class ChatRemoval(val messageId: String?, val authorId: String?)
sealed class ChatAction {
    data class Add(val message: ChatMessage) : ChatAction()
    data class Remove(val removal: ChatRemoval) : ChatAction()
}
data class ChatPage(val messages: List<ChatMessage>, val removals: List<ChatRemoval>, val nextKey: String?, val waitMs: Long,
    val actions: List<ChatAction> = emptyList())
fun chatPage(body: String): ChatPage {
    val root = jsonObject(body)
    val continuation = root?.path("continuationContents","liveChatContinuation") ?: root
    val actions = ArrayList<ChatAction>()
    val kinds = setOf("liveChatTextMessageRenderer","liveChatPaidMessageRenderer","liveChatMembershipItemRenderer","liveChatPaidStickerRenderer")
    fun visit(node: Json) {
        when(node) {
            is Json.Obj -> {
                for((kind,value) in node.fields) {
                    val item=value as? Json.Obj
                    when {
                        kind in kinds && item!=null -> actions.add(ChatAction.Add(ChatMessage(item.text("id"),item.display("authorName"),
                            item.display("message") ?: item.display("headerSubtext"),item.thumbnail("authorPhoto"),item.long("timestampUsec"),kind)))
                        kind=="markChatItemAsDeletedAction" && item!=null -> actions.add(ChatAction.Remove(ChatRemoval(item.text("targetItemId"),null)))
                        kind=="markChatItemsByAuthorAsDeletedAction" && item!=null -> actions.add(ChatAction.Remove(ChatRemoval(null,item.text("externalChannelId"))))
                        else -> visit(value)
                    }
                }
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    if(continuation!=null) visit(continuation)
    val messages=actions.filterIsInstance<ChatAction.Add>().map {it.message}
    val removals=actions.filterIsInstance<ChatAction.Remove>().map {it.removal}
    val next = continuation?.objects("timedContinuationData")?.firstOrNull()
        ?: continuation?.objects("invalidationContinuationData")?.firstOrNull()
        ?: continuation?.objects("reloadContinuationData")?.firstOrNull()
    return ChatPage(messages,removals,next?.text("continuation"),(next?.long("timeoutMs") ?: 1000).coerceIn(100,60000),actions)
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
            val actions=page.actions.filter { it !is ChatAction.Add || it.message.id==null || seen.add(it.message.id) }
            val unique=actions.filterIsInstance<ChatAction.Add>().map {it.message}
            while(seen.size>1000) seen.remove(seen.first())
            receive(page.copy(messages=unique,actions=actions))
            continuation=page.nextKey
            if(!closed && continuation!=null) try { wait(page.waitMs) } catch(_:InterruptedException) {
                Thread.currentThread().interrupt(); return ReadResult(error=RequestError(RequestFailure.CANCELLED,"cancelled"))
            }
        }
        return ReadResult(true)
    }
}
