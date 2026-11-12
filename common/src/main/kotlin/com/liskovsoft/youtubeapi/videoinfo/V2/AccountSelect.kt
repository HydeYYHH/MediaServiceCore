package com.liskovsoft.youtubeapi.videoinfo.V2

data class Account @JvmOverloads constructor(val id: String, val name: String?, val email: String?, val photo: String?,
    val selected: Boolean, val disabled: Boolean, val pageId: String?, val handle: String? = null)
fun readAccounts(body: String): List<Account> {
    val root = jsonObject(body) ?: return emptyList()
    return (root.objects("accountItem") + root.objects("accountItemRenderer")).mapNotNull { item ->
        val page = item.objects("pageIdToken").firstNotNullOfOrNull { it.text("pageId")?.takeIf(String::isNotBlank) }
        // WEB and TV render different account bylines for the same identity. The
        // account-state token is stable across those profiles and cookie renewal.
        // A brand's page token takes precedence over its owner's Google identity.
        val gaia = item.objects("accountStateToken").firstNotNullOfOrNull {
            it.text("obfuscatedGaiaId")?.takeIf(String::isNotBlank)
        }
        val id = page ?: gaia?.let { "gaia:$it" } ?: item.text("accountId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val email = item.display("accountByline")?.takeIf { it.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) }
        Account(id, item.display("accountName"), email, item.thumbnail("accountPhoto"),
            item.bool("isSelected") == true, item.bool("isDisabled") == true, page, item.display("channelHandle"))
    }.distinctBy { it.id }
}
fun loadAccounts(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<List<Account>> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val result = requestObject(youtubeQuery(YOUTUBE_API + "account/accounts_list",
        "\"accountReadMask\":{\"returnOwner\":true,\"returnBrandAccounts\":true,\"returnPersonaAccounts\":false}", context), http)
    return if (result.ok) ReadResult(readAccounts(result.value!!.encode())) else ReadResult(error = result.error)
}

/** The TV request profile exposes a stable active identity while using web-cookie authorization. */
fun loadActiveAccount(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<Account> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val query = contextualQuery(tvQuery(YOUTUBE_API + "account/accounts_list", "", context.visitor), context)
    val result = requestObject(query, http)
    if (!result.ok) return ReadResult(error = result.error)
    val accounts = readAccounts(result.value!!.encode()).filter { !it.disabled }
    val active = accounts.singleOrNull { it.selected } ?: accounts.singleOrNull()
    return if (active != null) ReadResult(active)
        else ReadResult(error = RequestError(RequestFailure.PARSE, "missing_active_account"))
}
/** Host-owned cookies, validated account identities, and immutable request snapshots. */
class AccountSession(private var context: RequestContext = RequestContext()) {
    private var accounts = emptyList<Account>()
    private var revision = 0L
    @Synchronized fun snapshot() = context
    /** Long-lived operations capture a generation and refuse to cross an account switch. */
    @Synchronized fun snapshot(expectedGeneration: Long): ReadResult<RequestContext> =
        if (context.generation == expectedGeneration) ReadResult(context) else sessionChanged()
    @Synchronized fun accounts(): List<Account> = accounts.toList()

    /** Install/restore a host-owned anonymous profile through the same generation fence as login. */
    @Synchronized fun installVisitor(visitor: VisitorSession): RequestContext {
        revision++
        val same = !context.signedIn && context.accountId == null && context.onBehalfOfUser == null &&
            visitorIdentity(context.visitorCookie, context.visitor) == visitorIdentity(visitor.cookies, visitor.visitorData)
        context = visitor.requestContext(context.language, context.region, context.generation + if (same) 0 else 1)
        accounts = emptyList()
        return context
    }

    /** Validate a new web login outside the monitor, then install it atomically. */
    fun install(webSession: WebSession, http: HttpClient = JvmHttpClient()): ReadResult<RequestContext> =
        validate(webSession, http, preserveSelection = false)

    /** Renew cookies while retaining a still-available selected brand identity. */
    fun update(webSession: WebSession, http: HttpClient = JvmHttpClient()): ReadResult<RequestContext> =
        validate(webSession, http, preserveSelection = true)

    private fun validate(webSession: WebSession, http: HttpClient, preserveSelection: Boolean): ReadResult<RequestContext> {
        if (!webSession.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
        val (before, ticket) = synchronized(this) { context to ++revision }
        val candidate = before.anonymous().copy(webSession = webSession)
        val profiles = loadAccounts(candidate, http)
        if (!currentRevision(ticket)) return sessionChanged()
        if (!profiles.ok) return ReadResult(error = profiles.error)
        val available = profiles.value!!.filter { !it.disabled }
        if (available.isEmpty()) return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_accounts"))
        val previous = if (preserveSelection && before.webSession?.accountIndex == webSession.accountIndex)
            available.firstOrNull { it.id == before.accountId } else null
        val active = loadActiveAccount(candidate.copy(onBehalfOfUser = previous?.pageId), http)
        if (!currentRevision(ticket)) return sessionChanged()
        if (!active.ok) return ReadResult(error = active.error)
        val identity = active.value!!
        val match = available.singleOrNull { sameAccount(it, identity) }
            ?: return ReadResult(error = RequestError(RequestFailure.AUTH, "account_identity_mismatch"))
        if (previous != null && !sameAccount(previous, identity))
            return ReadResult(error = RequestError(RequestFailure.AUTH, "account_identity_mismatch"))
        val pageId = identity.pageId ?: match.pageId
        return synchronized(this) {
            if (revision != ticket || context.generation != before.generation) return@synchronized sessionChanged()
            val unchanged = before.signedIn && before.accountId == identity.id && before.onBehalfOfUser == pageId &&
                before.webSession?.accountIndex == webSession.accountIndex
            context = candidate.copy(accountId = identity.id, onBehalfOfUser = pageId,
                generation = before.generation + if (unchanged) 0 else 1)
            accounts = profiles.value.map { if (it === match) identity.copy(pageId = pageId, selected = true, handle = identity.handle ?: match.handle)
                else it.copy(selected = false) }
            ReadResult(context)
        }
    }

    /** Refresh labels for the installed identity; account changes go through [select] or [install]. */
    @Synchronized fun updateAccounts(items: List<Account>): Boolean {
        if (!context.signedIn) return false
        if (items.map { it.id }.distinct().size != items.size) return false
        val known = accounts.associateBy { it.id }
        if (items.any { item -> known[item.id]?.let { it.pageId == item.pageId } != true }) return false
        val selected = items.firstOrNull { it.id == context.accountId } ?: return false
        if (selected.pageId != context.onBehalfOfUser) return false
        revision++
        accounts = items.map { it.copy(selected = it.id == selected.id) }
        return true
    }
    @Synchronized fun select(id: String): Boolean {
        val selected = accounts.firstOrNull { it.id == id && !it.disabled } ?: return false
        revision++
        if (context.accountId != selected.id || context.onBehalfOfUser != selected.pageId)
            context = context.copy(accountId = selected.id, onBehalfOfUser = selected.pageId, generation = context.generation + 1)
        accounts = accounts.map { it.copy(selected = it.id == id) }
        return true
    }
    @Synchronized fun clear() {
        revision++
        accounts = emptyList()
        context = context.anonymous().copy(generation = context.generation + 1)
    }

    private fun sameAccount(first: Account, second: Account): Boolean =
        first.id == second.id && first.pageId == second.pageId

    @Synchronized private fun currentRevision(expected: Long) = revision == expected

    private fun sessionChanged(): ReadResult<RequestContext> =
        ReadResult(error = RequestError(RequestFailure.CANCELLED, "session_changed"))
}
