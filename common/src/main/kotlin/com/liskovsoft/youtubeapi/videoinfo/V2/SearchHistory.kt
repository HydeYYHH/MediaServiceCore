package com.liskovsoft.youtubeapi.videoinfo.V2

interface SearchHistoryStore {
    fun read(account: String): List<String>
    fun write(account: String, entries: List<String>)
}
class MemorySearchHistoryStore : SearchHistoryStore {
    private val data = HashMap<String, List<String>>()
    @Synchronized override fun read(account: String) = data[account].orEmpty().toList()
    @Synchronized override fun write(account: String, entries: List<String>) { data[account] = entries.toList() }
}
class SearchHistory(private val store: SearchHistoryStore = MemorySearchHistoryStore(), private val capacity: Int = 100) {
    init { require(capacity > 0) }
    // Persistent local history follows identity, not an in-flight generation or rotating cookies.
    // Unverified credentials have no stable account identity and are deliberately isolated by scope.
    private fun key(context: RequestContext): String {
        val identity = context.accountId?.let { "account:$it" }
            ?: if (context.signedIn) "unverified:${context.webSession!!.scope()}"
            else visitorIdentity(context.visitorCookie, context.visitor) ?: "anonymous"
        val index = if (context.accountId != null || context.signedIn) (context.webSession?.accountIndex ?: 0).toString() else null
        val scope = listOf(identity, context.onBehalfOfUser, index).joinToString("\u0000") {
            if (it == null) "-" else "${it.length}:$it"
        }
        return "msc-search-v2:" + textDigest(scope)
    }
    @Synchronized fun read(context: RequestContext = RequestContext()) = store.read(key(context)).toList()
    @Synchronized fun record(text: String, context: RequestContext = RequestContext()) {
        val value = text.trim().takeIf { it.isNotEmpty() } ?: return
        store.write(key(context), (listOf(value) + read(context).filter { it != value }).take(capacity))
    }
    @Synchronized fun remove(text: String, context: RequestContext = RequestContext()) { store.write(key(context), read(context).filter { it != text }) }
    @Synchronized fun clear(context: RequestContext = RequestContext()) { store.write(key(context), emptyList()) }
    fun clearRemote(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
        if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
        val result = requestAction(youtubeQuery(YOUTUBE_API + "history/clear_search_history", "\"nop\":\"false\"", context),
            http, entityScope = ActionEntityScope.None, context = context)
        if (result.ok && result.value?.confirmed == true) clear(context)
        return result
    }
}
