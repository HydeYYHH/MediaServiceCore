package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Semantic channel surfaces. UNKNOWN stays visible without guessing from a translated title. */
enum class ChannelTabKind { HOME, VIDEOS, SHORTS, LIVE, PLAYLISTS, POSTS, UNKNOWN }

/** Opens channel capabilities using one account generation and the endpoints actually returned by the service. */
class ChannelSession @JvmOverloads constructor(private val session: AccountSession, private val http: HttpClient = JvmHttpClient()) {
    private val issuedHandles = Collections.synchronizedMap(WeakHashMap<ChannelHandle, Boolean>())
    private val authors = Collections.synchronizedMap(WeakHashMap<ChannelHandle, AuthorInfo>())
    internal fun issued(handle: ChannelHandle): Boolean = issuedHandles.containsKey(handle)
    internal fun author(handle: ChannelHandle): AuthorInfo? = if (issued(handle)) authors[handle] else null

    @JvmOverloads fun open(channelIdOrHandle: String, http: HttpClient = this.http): ReadResult<ChannelHandle> {
        val generation = session.snapshot().generation
        return session.runBound(generation) { context ->
            if (!CHANNEL_ID.matches(channelIdOrHandle) && !CHANNEL_HANDLE.matches(channelIdOrHandle))
                return@runBound channelFailure(RequestFailure.INVALID, "invalid_channel_identity")
            val boundHttp = SessionBoundHttp(session, generation, http)
            val resolved = resolveChannelId(channelIdOrHandle, context, boundHttp)
            if (!resolved.ok) return@runBound ReadResult(error = resolved.error)
            val id = resolved.value!!
            if (!CHANNEL_ID.matches(id)) return@runBound channelFailure(RequestFailure.PARSE, "resolved_identity_is_not_a_channel")
            val response = requestObject(channelQuery(id, context).withCompactResponse(), boundHttp)
            if (!response.ok) return@runBound ReadResult(error = response.error)
            val parsed = readChannelPage(response.value!!, id, false)
            if (!parsed.ok) return@runBound ReadResult(error = parsed.error)
            if (parsed.value!!.id != id) return@runBound channelFailure(RequestFailure.PARSE, "channel_identity_mismatch")
            val root = response.value!!
            val tabs = ownedChannelTabs(root, id)
                ?: return@runBound channelFailure(RequestFailure.PARSE, "unknown_channel_tabs")
            val initialPosts = ownedInitialChannelPosts(root, id)
                ?: return@runBound channelFailure(RequestFailure.PARSE, "unknown_post_page")
            val parsedChannel = parsed.value!!
            val channel = parsedChannel.copy(page = parsedChannel.page.copy(tabs = tabs))
            val videoItems = ownedChannelVideoItems(root, id, channel.page, ChannelVideoResponseRole.OPENING)
            ReadResult(ChannelHandle(session, generation, this.http, channel, initialPosts, this, videoItems).also {
                issuedHandles[it] = true
                ownedChannelAuthor(root, id).takeIf { author -> author.channelId == id }?.let { author -> authors[it] = author }
            })
        }
    }
    companion object {
        private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        private val CHANNEL_HANDLE = Regex("@[^\\s/?#]{1,100}")
    }
}

/** A reusable opened channel. All route/cursor ownership stays inside MSC. */
class ChannelHandle @JvmOverloads internal constructor(private val session: AccountSession, private val generation: Long,
    private val http: HttpClient, val channel: ChannelPage, private val initialPosts: PostPage,
    private val issuer: ChannelSession? = null, private val initialVideoItems: Set<MediaItem> = emptySet()) {
    private data class OpenedTab(val page: BrowsePage, val ownerDisplayItems: Set<MediaItem> = emptySet(),
        val ownerDisplayScope: Boolean = false)
    private val routes = channel.page.tabs.toList()
    private val openedTabs = ConcurrentHashMap<ChannelTabKind, OpenedTab>()
    private val tabCursors = Collections.synchronizedMap(WeakHashMap<ChannelTabCursor, Boolean>())
    private val postCursors = Collections.synchronizedMap(WeakHashMap<ChannelPostsCursor, Boolean>())
    private val searchCursors = Collections.synchronizedMap(WeakHashMap<ChannelSearchCursor, Boolean>())
    @Volatile private var openedPosts: PostPage? = null
    val availableTabs: List<ChannelTabKind> = Collections.unmodifiableList(routes.map { it.kind }.distinct())
    internal fun ownedBy(account: AccountSession): Boolean = session === account
    internal fun preparedAuthor(): AuthorInfo? = issuer?.author(this)

    private fun <T> bound(operation: (RequestContext) -> ReadResult<T>): ReadResult<T> =
        if (issuer?.issued(this) != true) channelFailure(RequestFailure.INVALID, "unissued_channel_handle")
        else session.runBound(generation, operation)

    @JvmOverloads fun openTab(kind: ChannelTabKind, http: HttpClient = this.http): ReadResult<ChannelTabPage> = bound { context ->
        if (kind == ChannelTabKind.UNKNOWN) return@bound channelFailure(RequestFailure.UNSUPPORTED, "unknown_channel_tab")
        if (kind == ChannelTabKind.POSTS) return@bound channelFailure(RequestFailure.UNSUPPORTED, "channel_posts_requires_typed_reader")
        val tab = route(kind) ?: return@bound channelFailure(RequestFailure.UNSUPPORTED, "channel_tab_unavailable_or_ambiguous")
        val scope = kind == ChannelTabKind.VIDEOS && (tab.browseId == null || tab.browseId == channel.id)
        val page = openedTabs[kind] ?: if (tab.selected) OpenedTab(channel.page,
            if (scope) initialVideoItems else emptySet(), scope) else {
            if (!navigable(tab)) return@bound channelFailure(RequestFailure.UNSUPPORTED, "channel_tab_has_no_navigation_capability")
            val loaded = readTab(kind, tab.browseId ?: channel.id, context, tab.params, http = http)
            if (!loaded.ok) return@bound ReadResult(error = loaded.error)
            loaded.value!!
        }
        openedTabs[kind] = page
        ReadResult(tabPage(kind, page))
    }

    @JvmOverloads fun next(cursor: ChannelTabCursor, http: HttpClient = this.http): ReadResult<ChannelTabPage> {
        if (cursor.owner !== this || !tabCursors.containsKey(cursor)) return channelFailure(RequestFailure.INVALID, "foreign_channel_cursor")
        return bound { context ->
            val loaded = readTab(cursor.kind, null, context, continuation = cursor.key, ownerDisplayScope = tabCursors[cursor] == true, http = http)
            if (!loaded.ok) ReadResult(error = loaded.error) else ReadResult(tabPage(cursor.kind, loaded.value!!))
        }
    }

    @JvmOverloads fun openPosts(http: HttpClient = this.http): ReadResult<ChannelPostsPage> = bound { context ->
        val tab = route(ChannelTabKind.POSTS)
            ?: return@bound channelFailure(RequestFailure.UNSUPPORTED, "channel_posts_unavailable_or_ambiguous")
        val page = openedPosts ?: if (tab.selected) initialPosts else {
            if (!navigable(tab)) return@bound channelFailure(RequestFailure.UNSUPPORTED, "channel_tab_has_no_navigation_capability")
            val loaded = loadChannelPostsDirect(tab.browseId ?: channel.id!!, context, tab.params, http = SessionBoundHttp(session, generation, http))
            if (!loaded.ok) return@bound ReadResult(error = loaded.error)
            loaded.value!!
        }
        openedPosts = page
        ReadResult(postsPage(page))
    }

    @JvmOverloads fun nextPosts(cursor: ChannelPostsCursor, http: HttpClient = this.http): ReadResult<ChannelPostsPage> {
        if (cursor.owner !== this || !postCursors.containsKey(cursor)) return channelFailure(RequestFailure.INVALID, "foreign_channel_posts_cursor")
        return bound { context ->
            val tab = route(ChannelTabKind.POSTS)
                ?: return@bound channelFailure(RequestFailure.UNSUPPORTED, "channel_posts_unavailable_or_ambiguous")
            val loaded = loadChannelPostsDirect(tab.browseId ?: channel.id!!, context, continuation = cursor.key, http = SessionBoundHttp(session, generation, http))
            if (!loaded.ok) ReadResult(error = loaded.error) else ReadResult(postsPage(loaded.value!!))
        }
    }

    private fun route(kind: ChannelTabKind): BrowseTab? = routes.filter { it.kind == kind }.singleOrNull()

    @JvmOverloads fun search(query: String, http: HttpClient = this.http): ReadResult<ChannelSearchPage> = bound { context ->
        if (query.isBlank()) return@bound channelFailure(RequestFailure.INVALID, "missing_search_query")
        val loaded = loadChannel(channel.id!!, context, search = query,
            http = SessionBoundHttp(session, generation, http))
        if (!loaded.ok) ReadResult(error = loaded.error) else ReadResult(searchPage(loaded.value!!.page))
    }

    @JvmOverloads fun nextSearch(cursor: ChannelSearchCursor, http: HttpClient = this.http): ReadResult<ChannelSearchPage> {
        if (cursor.owner !== this || !searchCursors.containsKey(cursor))
            return channelFailure(RequestFailure.INVALID, "foreign_channel_search_cursor")
        return bound { context ->
            val loaded = loadChannel(channel.id!!, context, continuation = cursor.key,
                http = SessionBoundHttp(session, generation, http))
            if (!loaded.ok) ReadResult(error = loaded.error) else ReadResult(searchPage(loaded.value!!.page))
        }
    }

    private fun searchPage(page: BrowsePage) = ChannelSearchPage(page,
        page.nextKey?.takeIf(String::isNotBlank)?.let { key ->
            ChannelSearchCursor(this, key).also { searchCursors[it] = true }
        })
    private fun navigable(tab: BrowseTab) = channelTabHasNavigation(tab, channel.id)
    private fun readTab(kind: ChannelTabKind, id: String?, context: RequestContext, params: String? = null,
        continuation: String? = null, ownerDisplayScope: Boolean = kind == ChannelTabKind.VIDEOS && id == channel.id, http: HttpClient = this.http): ReadResult<OpenedTab> {
        val response = requestObject(browseQuery(id, context, params, continuation).withCompactResponse(), SessionBoundHttp(session, generation, http))
        if (!response.ok) return ReadResult(error = response.error)
        val parsed = readBrowsePage(response.value!!, continuation != null)
        if (!parsed.ok) return ReadResult(error = parsed.error)
        val page = parsed.value!!
        val items = if (ownerDisplayScope) ownedChannelVideoItems(response.value!!, channel.id!!, page,
            if (continuation == null) ChannelVideoResponseRole.ISSUED_TAB else ChannelVideoResponseRole.ISSUED_CONTINUATION) else emptySet()
        return ReadResult(OpenedTab(page, items, ownerDisplayScope))
    }
    private fun tabPage(kind: ChannelTabKind, opened: OpenedTab): ChannelTabPage {
        val page = opened.page
        return ChannelTabPage(kind, page,
            page.nextKey?.takeIf(String::isNotBlank)?.let { ChannelTabCursor(this, kind, it).also { cursor -> tabCursors[cursor] = opened.ownerDisplayScope } }, opened.ownerDisplayItems)
    }
    private fun postsPage(page: PostPage) = ChannelPostsPage(page,
        page.nextKey?.takeIf(String::isNotBlank)?.let { ChannelPostsCursor(this, it).also { cursor -> postCursors[cursor] = true } })
    override fun toString() = "ChannelHandle(availableTabs=$availableTabs)"
}

class ChannelTabPage internal constructor(val kind: ChannelTabKind, val page: BrowsePage, val next: ChannelTabCursor?,
    internal val ownerDisplayItems: Set<MediaItem>) {
    internal constructor(kind: ChannelTabKind, page: BrowsePage, next: ChannelTabCursor?) : this(kind, page, next, emptySet())
}
class ChannelPostsPage internal constructor(val page: PostPage, val next: ChannelPostsCursor?)
class ChannelSearchPage internal constructor(val page: BrowsePage, val next: ChannelSearchCursor?)
class ChannelSearchCursor internal constructor(internal val owner: ChannelHandle, internal val key: String) {
    override fun toString() = "ChannelSearchCursor"
}
class ChannelTabCursor internal constructor(internal val owner: ChannelHandle, val kind: ChannelTabKind, internal val key: String) {
    override fun toString() = "ChannelTabCursor(kind=$kind)"
}
class ChannelPostsCursor internal constructor(internal val owner: ChannelHandle, internal val key: String) {
    override fun toString() = "ChannelPostsCursor"
}

internal fun channelTabHasNavigation(tab: BrowseTab, channelId: String?): Boolean =
    !tab.params.isNullOrBlank() ||
        tab.kind == ChannelTabKind.HOME && channelId != null && tab.browseId == channelId ||
        tab.browseId?.let { it != channelId && videoIdAccepted(it) } == true

/** Stable protocol identifiers/URL paths only. A translated label is never a route identifier. */
internal fun channelTabKind(identifier: String?, endpointUrl: String?): ChannelTabKind {
    fun named(value: String?) = when (value?.lowercase(Locale.ROOT)) {
        "featured", "home" -> ChannelTabKind.HOME
        "videos" -> ChannelTabKind.VIDEOS
        "shorts" -> ChannelTabKind.SHORTS
        "streams", "live" -> ChannelTabKind.LIVE
        "playlists" -> ChannelTabKind.PLAYLISTS
        "community", "posts" -> ChannelTabKind.POSTS
        else -> null
    }
    val fromIdentifier = named(identifier)
    val uri = endpointUrl?.let { runCatching { URI(it) }.getOrNull() }
    val allowed = uri != null && uri.rawUserInfo == null && uri.fragment == null &&
        (uri.rawAuthority == null && uri.scheme == null && uri.path?.startsWith('/') == true ||
            uri.scheme == "https" && uri.host in setOf("www.youtube.com", "youtube.com", "m.youtube.com") && uri.port in listOf(-1, 443))
    val path = uri?.path.orEmpty().trim('/').split('/')
    val channelSegments = when {
        path.firstOrNull()?.startsWith('@') == true -> 1
        path.firstOrNull() in setOf("channel", "c", "user") && path.getOrNull(1)?.isNotBlank() == true -> 2
        else -> -1
    }
    val fromUrl = if (allowed && channelSegments >= 0) when (path.size) {
        channelSegments -> ChannelTabKind.HOME
        channelSegments + 1 -> named(path.last())
        else -> null
    } else null
    return if (fromIdentifier != null && fromUrl != null && fromIdentifier != fromUrl) ChannelTabKind.UNKNOWN
        else fromIdentifier ?: fromUrl ?: ChannelTabKind.UNKNOWN
}

private fun <T> channelFailure(kind: RequestFailure, code: String) = ReadResult<T>(error = RequestError(kind, code))
