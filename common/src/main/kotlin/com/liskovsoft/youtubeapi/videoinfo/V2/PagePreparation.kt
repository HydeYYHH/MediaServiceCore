package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Explicit page-level author preparation. Embedded artwork and same-page canonical identities win;
 * additional public WEB metadata reads happen only within [prepare]'s actual HTTP request budget.
 * The owner of the page fences account generation around this operation (e.g. AccountSession.execute).
 */
class PagePreparation @JvmOverloads constructor(private val http: HttpClient = JvmHttpClient(),
    private val maxEntries: Int = 256, private val ttlMs: Long = 3_600_000,
    private val clock: () -> Long = System::currentTimeMillis) {
    init { require(maxEntries in 1..4096); require(ttlMs > 0) }
    private data class Entry(val artwork: Artwork, val writtenAt: Long)
    private data class Lookup(val artwork: Artwork?, val reason: String?)
    private class Flight(val revision: Long, val future: CompletableFuture<Lookup> = CompletableFuture())
    private data class Slot(val artwork: Artwork? = null, val flight: Flight? = null, val leader: Boolean = false,
        val reason: String? = null)
    private class Budget(val limit: Int, var used: Int = 0)
    private val monitor = Any()
    private val cache = LinkedHashMap<String, Entry>(16, .75f, true)
    private val flights = HashMap<String, Flight>()
    private var revision = 0L
    private val canonical = Regex("UC[A-Za-z0-9_-]{22}")

    @JvmOverloads fun prepare(items: List<MediaItem>, context: RequestContext = RequestContext(),
        maxRequests: Int = 0): ReadResult<PreparedPage> = prepareBound(items, context, maxRequests, http)

    internal fun prepareBound(items: List<MediaItem>, context: RequestContext, maxRequests: Int,
        http: HttpClient, owner: AuthorInfo? = null): ReadResult<PreparedPage> {
        if (maxRequests !in 0..64) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_preparation_budget"))
        val capturedRevision = synchronized(monitor) { revision }
        val budget = Budget(maxRequests)
        val embedded = HashMap<String, Artwork>()
        items.forEach { item ->
            val id = (if (item.kind == "channel") item.id else item.channelId)?.takeIf(canonical::matches)
            val art = if (item.kind == "channel") artworkFromUrl(item.thumbnail) else ownArtwork(item)
            if (id != null && art != null) embedded[id] = merge(embedded[id], art)
        }
        val channelOwner = owner?.takeIf { it.channelId?.let(canonical::matches) == true }
        channelOwner?.avatar?.let { embedded.putIfAbsent(channelOwner.channelId!!, it) }
        synchronized(monitor) {
            if (revision == capturedRevision) {
                val now = clock()
                embedded.forEach { (id, artwork) -> cache[id] = Entry(artwork, now) }
                while (cache.size > maxEntries) cache.remove(cache.keys.first())
            }
        }
        val resolved = HashMap<String, Lookup>()
        return try {
            val prepared = items.map { item ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (item.kind == "channel") return@map PreparedMediaItem(item, AuthorInfo(null, item.author, null, "not_applicable"))
                val id = item.channelId?.takeIf(canonical::matches)
                val own = ownArtwork(item)
                val result = when {
                    own != null -> Lookup(own, null)
                    id == null -> Lookup(null, "canonical_channel_id_unavailable")
                    embedded[id] != null -> Lookup(embedded.getValue(id), null)
                    else -> resolved.getOrPut(id) { lookup(id, context, budget, http) }
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val name = item.author?.takeIf(String::isNotBlank)
                    ?: channelOwner?.takeIf { it.channelId == id }?.name
                PreparedMediaItem(item, AuthorInfo(id, name, result.artwork, result.reason))
            }
            ReadResult(PreparedPage(prepared, budget.used))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ReadResult(error = RequestError(RequestFailure.CANCELLED, "preparation_cancelled"))
        }
    }

    /** Old in-flight reads can finish for their callers but cannot refill the cleared cache. */
    fun clear() = synchronized(monitor) { revision++; cache.clear(); flights.clear() }

    private fun ownArtwork(item: MediaItem) = item.authorArtwork?.takeIf { it.variants.isNotEmpty() } ?: artworkFromUrl(item.authorThumbnail)
    private fun merge(previous: Artwork?, next: Artwork) = if (previous == null) next else Artwork(previous.variants + next.variants)

    private fun lookup(id: String, context: RequestContext, budget: Budget, http: HttpClient): Lookup {
        val slot = synchronized(monitor) {
            val now = clock()
            val entry = cache[id]
            val age = entry?.let { now - it.writtenAt }
            if (entry != null && age != null && age >= 0 && age < ttlMs) Slot(artwork = entry.artwork)
            else {
                cache.remove(id)
                if (budget.used >= budget.limit) Slot()
                else {
                    val existing = flights[id]
                    if (existing != null) Slot(flight = existing)
                    else if (flights.size >= minOf(maxEntries, 8)) Slot(reason = "artwork_lookup_capacity_exhausted")
                    else Flight(revision).let { flights[id] = it; Slot(flight = it, leader = true) }
                }
            }
        }
        slot.artwork?.let { return Lookup(it, null) }
        val flight = slot.flight ?: return Lookup(null, slot.reason ?: "request_budget_exhausted")
        if (!slot.leader) return try { flight.future.get() }
            catch (_: ExecutionException) { Lookup(null, "artwork_source_failed") }
        val result = try { fetch(id, context, budget, http) }
            catch (_: Exception) { Lookup(null, "artwork_source_failed") }
        synchronized(monitor) {
            if (flight.revision == revision && flights[id] === flight) {
                flights.remove(id)
                result.artwork?.let {
                    cache[id] = Entry(it, clock())
                    while (cache.size > maxEntries) cache.remove(cache.keys.first())
                }
            }
        }
        flight.future.complete(result)
        return result
    }

    private fun fetch(id: String, context: RequestContext, budget: Budget, http: HttpClient): Lookup {
        // Public author artwork is independent of login and visitor cookies. No menu or video lookup occurs here.
        val publicContext = context.copy(accountId = null, webSession = null, onBehalfOfUser = null, visitorCookie = null)
        val query = channelQuery(id, publicContext).withCompactResponse()
        val bounded = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                if (budget.used >= budget.limit) throw RequestException(RequestError(RequestFailure.INVALID, "preparation_budget_exhausted"))
                budget.used++
                return http.execute(request)
            }
        }
        val result = requestObject(query.copy(url = query.url + "&fields=metadata%2Cheader"), bounded)
        if (!result.ok) return Lookup(null, "artwork_source_failed")
        val author = ownedChannelAuthor(result.value!!, id)
        return Lookup(author.avatar, author.unavailableReason)
    }
}
