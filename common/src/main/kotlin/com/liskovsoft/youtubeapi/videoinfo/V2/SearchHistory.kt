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
    private fun key(context: RequestContext) = context.accountId ?: "anonymous"
    @Synchronized fun read(context: RequestContext = RequestContext()) = store.read(key(context)).toList()
    @Synchronized fun record(text: String, context: RequestContext = RequestContext()) {
        val value = text.trim().takeIf { it.isNotEmpty() } ?: return
        store.write(key(context), (listOf(value) + read(context).filter { it != value }).take(capacity))
    }
    @Synchronized fun remove(text: String, context: RequestContext = RequestContext()) { store.write(key(context), read(context).filter { it != text }) }
    @Synchronized fun clear(context: RequestContext = RequestContext()) { store.write(key(context), emptyList()) }
    fun clearRemote(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
        val result = signedAction("history/clear_search_history", "\"nop\":\"false\"", context, http)
        if (result.ok) clear(context)
        return result
    }
}
