package com.liskovsoft.youtubeapi.videoinfo.V2

data class Account(val id: String, val name: String?, val email: String?, val photo: String?, val selected: Boolean, val disabled: Boolean, val pageId: String?)
fun readAccounts(body: String): List<Account> {
    val root = jsonObject(body) ?: return emptyList()
    return (root.objects("accountItem") + root.objects("accountItemRenderer")).mapNotNull { item ->
        val page = item.objects("pageIdToken").firstOrNull()?.text("pageId")
        val id = page ?: item.text("accountId") ?: item.display("accountByline") ?: item.display("accountName") ?: return@mapNotNull null
        Account(id, item.display("accountName"), item.display("accountByline"), item.thumbnail("accountPhoto"),
            item.bool("isSelected") == true, item.bool("isDisabled") == true, page)
    }.distinctBy { it.id }
}
fun loadAccounts(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<List<Account>> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val result = requestObject(youtubeQuery(YOUTUBE_API + "account/accounts_list",
        "\"accountReadMask\":{\"returnOwner\":true,\"returnBrandAccounts\":true,\"returnPersonaAccounts\":false}", context), http)
    return if (result.ok) ReadResult(readAccounts(result.value!!.encode())) else ReadResult(error = result.error)
}
class AccountSession(private var context: RequestContext = RequestContext()) {
    private var accounts = emptyList<Account>()
    @Synchronized fun snapshot() = context
    @Synchronized fun accounts(): List<Account> = accounts.toList()
    @Synchronized fun replace(token: AccessToken, items: List<Account>) {
        accounts = items.toList()
        val selected = items.firstOrNull { it.selected && !it.disabled }
        context = context.copy(accessToken = token.accessToken, accountId = selected?.id,
            onBehalfOfUser = selected?.pageId, generation = context.generation + 1)
    }
    @Synchronized fun select(id: String): Boolean {
        val selected = accounts.firstOrNull { it.id == id && !it.disabled } ?: return false
        context = context.copy(accountId = selected.id, onBehalfOfUser = selected.pageId, generation = context.generation + 1)
        accounts = accounts.map { it.copy(selected = it.id == id) }
        return true
    }
    @Synchronized fun clear() { accounts = emptyList(); context = context.anonymous().copy(generation = context.generation + 1) }
}
