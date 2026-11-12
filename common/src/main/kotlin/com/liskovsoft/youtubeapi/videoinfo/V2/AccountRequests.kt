package com.liskovsoft.youtubeapi.videoinfo.V2

/** Opaque continuation bound to the session and generation that returned it. */
class AccountCursor internal constructor(internal val owner: AccountSession, internal val generation: Long,
    internal val key: String) {
    override fun toString() = "AccountCursor(generation=$generation)"
}

/** A delivered result can create cursors only for its own captured account generation. */
class AccountResult<T> internal constructor(val value: T, val generation: Long, private val owner: AccountSession) {
    internal fun belongsTo(session: AccountSession): Boolean = owner === session
    fun cursor(key: String?): AccountCursor? = key?.takeIf { it.isNotBlank() }?.let { AccountCursor(owner, generation, it) }
    override fun toString() = "AccountResult(generation=$generation)"
}

/**
 * Executes once using a captured identity and fences delivery after switching/logout.
 * This does not retry or undo an already submitted mutation. Stateless loaders remain
 * available for hosts that explicitly own their immutable request snapshots.
 */
fun <T> AccountSession.execute(operation: (RequestContext) -> ReadResult<T>): ReadResult<AccountResult<T>> {
    val captured = snapshot()
    return executeAccountOperation(this, captured, operation)
}

/** Follow a continuation without transferring it to another account or session instance. */
fun <T> AccountSession.execute(cursor: AccountCursor,
    operation: (RequestContext, String) -> ReadResult<T>): ReadResult<AccountResult<T>> {
    if (cursor.owner !== this) return ReadResult(error = RequestError(RequestFailure.INVALID, "foreign_session_cursor"))
    val captured = snapshot(cursor.generation)
    if (!captured.ok) return ReadResult(error = captured.error)
    return executeAccountOperation(this, captured.value!!) { operation(it, cursor.key) }
}

/** Domain handles keep their opening generation; they never silently adopt a later identity. */
internal fun <T> AccountSession.runBound(generation: Long,
    operation: (RequestContext) -> ReadResult<T>): ReadResult<T> {
    val captured = snapshot(generation)
    if (!captured.ok) return ReadResult(error = captured.error)
    val result = executeAccountOperation(this, captured.value!!, operation)
    return if (result.ok) ReadResult(result.value!!.value) else ReadResult(error = result.error)
}

/** Every IO boundary, including enrichment and multi-step reads, keeps the captured identity. */
internal class SessionBoundHttp(private val owner: AccountSession, private val generation: Long,
    private val delegate: HttpClient) : HttpClient {
    override fun execute(request: HttpRequest): HttpReply {
        owner.snapshot(generation).error?.let { throw RequestException(it) }
        val reply = delegate.execute(request)
        owner.snapshot(generation).error?.let { throw RequestException(it) }
        return reply
    }
}

private fun <T> executeAccountOperation(session: AccountSession, captured: RequestContext,
    operation: (RequestContext) -> ReadResult<T>): ReadResult<AccountResult<T>> {
    val result = operation(captured)
    val current = session.snapshot(captured.generation)
    if (!current.ok) return ReadResult(error = current.error)
    return if (result.ok) ReadResult(AccountResult(result.value!!, captured.generation, session))
        else ReadResult(error = result.error)
}
