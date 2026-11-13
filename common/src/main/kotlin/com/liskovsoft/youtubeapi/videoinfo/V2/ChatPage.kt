package com.liskovsoft.youtubeapi.videoinfo.V2
import java.io.Closeable
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(val id: String?, val author: String?, val text: String?, val photo: String?, val timestampUs: Long?, val kind: String,
    val authorId: String? = null)
data class ChatRemoval(val messageId: String?, val authorId: String?)
sealed class ChatAction {
    data class Add(val message: ChatMessage) : ChatAction()
    data class Remove(val removal: ChatRemoval) : ChatAction()
}
data class ChatPage @JvmOverloads constructor(val messages: List<ChatMessage>, val removals: List<ChatRemoval>, val nextKey: String?, val waitMs: Long,
    val actions: List<ChatAction> = emptyList(), val events: List<ChatEvent> = emptyList(), val replay: Boolean = false,
    val sendParams: String? = null, val postingRestriction: String? = null)
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
                            item.display("message") ?: item.display("headerSubtext"),item.thumbnail("authorPhoto"),item.long("timestampUsec"),kind,
                            item.text("authorExternalChannelId")))))
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
        replay != null || events.any { it.offsetMs != null },
        continuation?.objects("sendLiveChatMessageEndpoint")?.firstOrNull()?.text("params"),
        continuation?.path("actionPanel", "liveChatRestrictedParticipationRenderer")?.display("message")?.take(300))
}
fun loadChat(continuation: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<ChatPage> {
    if (continuation.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"missing_chat_key"))
    val response=requestObject(youtubeQuery(YOUTUBE_API+"live_chat/get_live_chat",payload("continuation" to continuation),context),http)
    return if(response.ok) readChatPage(response.value!!) else ReadResult(error=response.error)
}

/** An unknown response must not silently end a healthy chat connection. */
internal fun readChatPage(root: Json.Obj): ReadResult<ChatPage> {
    fun unknown() = ReadResult<ChatPage>(error = RequestError(RequestFailure.PARSE, "unknown_chat_page"))
    val content = if ("continuationContents" in root.fields)
        root.path("continuationContents", "liveChatContinuation") ?: return unknown() else root
    for (name in listOf("actions", "continuations")) {
        if (name in content.fields && content.fields[name] !is Json.Arr) return unknown()
        if (content.arr(name).any { it !is Json.Obj }) return unknown()
    }
    val actions = content.arr("actions")
    val continuations = listOf(content) + content.arr("continuations").filterIsInstance<Json.Obj>()
    val kinds = listOf("liveChatReplayContinuationData", "timedContinuationData", "invalidationContinuationData", "reloadContinuationData")
    for (entry in continuations) for (kind in kinds) {
        if (kind in entry.fields && entry.obj(kind)?.text("continuation").isNullOrBlank()) return unknown()
    }
    val next = kinds.firstNotNullOfOrNull { kind -> continuations.firstNotNullOfOrNull { it.obj(kind) } }
    val explicitEnd = listOf("actions", "continuations").any { it in content.fields } &&
        actions.isEmpty() && content.arr("continuations").isEmpty()
    val knownControl = content.path("actionPanel", "liveChatRestrictedParticipationRenderer")?.fields?.isNotEmpty() == true
    if (next == null && !explicitEnd && !knownControl && actions.none { knownChatAction(it as Json.Obj) }) return unknown()
    val page = chatPage(content.encode())
    // Routing evidence is scoped to this continuation, never an unrelated nested diagnostic/control.
    return ReadResult(page.copy(nextKey = next?.text("continuation"),
        waitMs = (next?.long("timeoutMs") ?: 1000).coerceIn(100, 60000),
        replay = continuations.any { it.obj("liveChatReplayContinuationData") != null } || page.events.any { it.offsetMs != null }))
}

private val chatItemKinds = setOf("liveChatTextMessageRenderer", "liveChatPaidMessageRenderer", "liveChatMembershipItemRenderer",
    "liveChatPaidStickerRenderer", "liveChatViewerEngagementMessageRenderer", "liveChatAutoModMessageRenderer",
    "liveChatModeChangeMessageRenderer", "liveChatPlaceholderItemRenderer", "liveChatDonationAnnouncementRenderer",
    "liveChatSponsorshipsGiftPurchaseAnnouncementRenderer", "liveChatSponsorshipsGiftRedemptionAnnouncementRenderer",
    "liveChatSponsorshipsHeaderRenderer")

/** Known controls may carry no text message. Unknown actions remain compatible when the page has a real next key. */
private fun knownChatAction(action: Json.Obj, depth: Int = 0): Boolean {
    if (depth > 16) return false
    fun item(node: Json.Obj?, kinds: Set<String> = chatItemKinds) =
        node != null && kinds.any { node.obj(it)?.fields?.isNotEmpty() == true }
    if (item(action)) return true
    if (item(action.obj("addChatItemAction")?.obj("item")) || item(action.obj("replaceChatItemAction")?.obj("replacementItem"))) return true
    if (action.obj("markChatItemAsDeletedAction")?.text("targetItemId")?.isNotBlank() == true ||
        action.obj("markChatItemsByAuthorAsDeletedAction")?.text("externalChannelId")?.isNotBlank() == true ||
        action.obj("removeChatItemAction")?.text("targetItemId")?.isNotBlank() == true) return true
    action.obj("replayChatItemAction")?.let { replay ->
        val replayActions = replay.fields["actions"] as? Json.Arr ?: return@let
        if (replayActions.items.all { it is Json.Obj } && (replayActions.items.isEmpty() ||
                replayActions.items.any { knownChatAction(it as Json.Obj, depth + 1) })) return true
    }
    // Protocol control families: https://github.com/LuanRT/YouTube.js/tree/main/src/parser/classes/livechat
    return item(action.obj("addLiveChatTickerItemAction")?.obj("item"), setOf("liveChatTickerPaidMessageItemRenderer",
        "liveChatTickerPaidStickerItemRenderer", "liveChatTickerSponsorItemRenderer")) ||
        item(action.obj("addBannerToLiveChatCommand")?.obj("bannerRenderer"), setOf("liveChatBannerRenderer")) ||
        action.obj("removeBannerForLiveChatCommand")?.text("targetActionId")?.isNotBlank() == true ||
        item(action.obj("showLiveChatActionPanelAction")?.obj("panelToShow"), setOf("liveChatActionPanelRenderer")) ||
        item(action.obj("showLiveChatTooltipCommand")?.obj("tooltip"), setOf("tooltipRenderer")) ||
        item(action.obj("replaceLiveChatAction")?.obj("replacement"), setOf("liveChatRenderer"))
}

/** Retains identity while allowing the same account's credentials to rotate. */
internal class ChatSessionBinding(private val session: AccountSession) {
    private val generation = session.snapshot().generation
    fun snapshot(): ReadResult<RequestContext> = session.snapshot(generation)
}

/** One live-chat page at a time; hosts can suspend between reads without retaining an HTTP worker. */
class ChatPoller(key: String, session: AccountSession = AccountSession()) : Closeable {
    @Volatile private var closed = false
    private var next: String? = key
    private val seen = LinkedHashSet<String>()
    private val binding = ChatSessionBinding(session)
    @Volatile private var latestPage: ChatPage? = null

    init { require(key.isNotBlank()) }

    override fun close() { closed = true }

    @Synchronized fun read(http: HttpClient = JvmHttpClient()): ReadResult<ChatPage> {
        if (closed) return ReadResult(error = RequestError(RequestFailure.CANCELLED, "closed"))
        val context = binding.snapshot()
        if (!context.ok) return ReadResult(error = context.error)
        val continuation = next ?: run {
            latestPage = null
            return ReadResult(ChatPage(emptyList(), emptyList(), null, 0))
        }
        latestPage = null
        val result = loadChat(continuation, context.value!!, http)
        if (closed) return ReadResult(error = RequestError(RequestFailure.CANCELLED, "closed"))
        val current = binding.snapshot()
        if (!current.ok) return ReadResult(error = current.error)
        if (!result.ok) return result
        val page = uniqueChat(result.value!!, seen)
        next = page.nextKey
        latestPage = page
        return ReadResult(page)
    }

    /** Call after a fresh read; fallback parameters address the broadcaster, never grant permission. */
    @Synchronized fun sender(fallbackParams: String? = null): ReadResult<ChatSender> {
        if (closed) return ReadResult(error = RequestError(RequestFailure.CANCELLED, "closed"))
        val current = binding.snapshot()
        if (!current.ok) return ReadResult(error = current.error)
        postingError()?.let { return ReadResult(error = it) }
        val page = latestPage!!
        val params = page.sendParams?.takeIf(String::isNotBlank) ?: fallbackParams?.takeIf(String::isNotBlank)
            ?: return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "missing_chat_key"))
        return ReadResult(ChatSender(params, binding, ::postingError))
    }

    private fun postingError(): RequestError? {
        if (closed) return RequestError(RequestFailure.CANCELLED, "closed")
        val page = latestPage ?: return RequestError(RequestFailure.INVALID, "chat_read_required")
        return if (page.replay || !page.postingRestriction.isNullOrBlank())
            RequestError(RequestFailure.HTTP, "chat_posting_restricted", detail = page.postingRestriction) else null
    }
}

class ChatConnection(private val session: AccountSession = AccountSession(), private val http: HttpClient = JvmHttpClient(),
    wait: ((Long) -> Unit)? = null) : Closeable {
    @Volatile private var closed = false
    private val waiting = ConnectionWait(wait)
    private val running = AtomicBoolean()
    private val binding = ChatSessionBinding(session)
    override fun close() { closed = true; waiting.close() }

    fun listen(key: String, receive: (ChatPage) -> Unit): ReadResult<Boolean> {
        if (!running.compareAndSet(false, true)) return ReadResult(error = RequestError(RequestFailure.INVALID, "already_listening"))
        try {
            val current = binding.snapshot()
            if (!current.ok) return ReadResult(error = current.error)
            ChatPoller(key, session).use { poller ->
                while (!closed && !Thread.currentThread().isInterrupted) {
                    val identity = binding.snapshot()
                    if (!identity.ok) return ReadResult(error = identity.error)
                    val reply = poller.read(http)
                    if (closed) break
                    if (!reply.ok) return ReadResult(error = reply.error)
                    val page = reply.value!!
                    receive(page)
                    if (page.nextKey == null) break
                    if (!closed) waiting.pause(page.waitMs)
                }
            }
            return if (Thread.currentThread().isInterrupted) ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled")) else ReadResult(true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        } finally { running.set(false) }
    }
}
