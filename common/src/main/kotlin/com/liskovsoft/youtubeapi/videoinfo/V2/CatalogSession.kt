package com.liskovsoft.youtubeapi.videoinfo.V2

/** Server-issued navigation. Neither the client profile nor continuation text is an App concern. */
class CatalogCursor internal constructor(internal val owner: CatalogSession, internal val generation: Long,
    internal val route: CatalogRoute, internal val key: String?) {
    override fun toString() = "CatalogCursor(generation=$generation)"
}

class CatalogFilter internal constructor(val title: String?, val group: String?, val selected: Boolean,
    val available: Boolean, internal val owner: CatalogSession, internal val generation: Long,
    internal val query: String, internal val parameters: String?) {
    override fun toString() = "CatalogFilter(selected=$selected, available=$available)"
}

class CatalogGroup @JvmOverloads internal constructor(val title: String?, val items: List<PreparedMediaItem>,
    val actions: List<PageAction> = emptyList()) {
    /** Only a proven group-owned route can issue a cursor. Raw group tokens are not routes. */
    val next: CatalogCursor? get() = null
    var continuationUnavailableReason: String? = null
        private set
    internal fun withoutContinuationRoute(): CatalogGroup = apply {
        continuationUnavailableReason = "continuation_route_unavailable"
    }
}

/** A complete preparation pass; absent artwork has an explicit reason and causes no lazy IO. */
class CatalogPage @JvmOverloads internal constructor(val items: List<PreparedMediaItem>, val groups: List<CatalogGroup>,
    val filters: List<CatalogFilter>, val next: CatalogCursor?, val messages: List<String>,
    val preparationRequestCount: Int, val actions: List<PageAction> = emptyList()) {
    val empty: Boolean get() = items.isEmpty() && groups.all { it.items.isEmpty() }
}

internal sealed class CatalogRoute {
    object Home : CatalogRoute()
    data class Topic(val section: HomeSection) : CatalogRoute()
    data class Search(val query: String, val params: String?) : CatalogRoute()
    data class Library(val section: LibrarySection) : CatalogRoute()
    data class Related(val videoId: String) : CatalogRoute()
    // Retain the actual low-level issuer and cursor; no channel continuation is reconstructed from text.
    data class ChannelTab(val cursor: ChannelTabCursor) : CatalogRoute()
    data class ChannelPosts(val cursor: ChannelPostsCursor) : CatalogRoute()
}

/**
 * Native feeds, routed continuations, canonical server filters, and prepared card authors.
 * HOME always uses WEB FEwhat_to_watch; topic feeds keep their explicit supported profile.
 * An empty upstream home stays empty. Choosing a discovery/mixed feed is host product policy.
 */
class CatalogSession @JvmOverloads constructor(private val session: AccountSession,
    private val http: HttpClient = JvmHttpClient(), private val authorRequestBudget: Int = 0) {
    init { require(authorRequestBudget in 0..32) }
    private val preparation = PagePreparation(http)
    private val issuedCursors = java.util.WeakHashMap<CatalogCursor, Unit>()
    private val issuedFilters = java.util.WeakHashMap<CatalogFilter, Unit>()

    @JvmOverloads fun home(section: HomeSection = HomeSection.HOME): ReadResult<CatalogPage> =
        open(if (section == HomeSection.HOME) CatalogRoute.Home else CatalogRoute.Topic(section))

    fun search(query: String): ReadResult<CatalogPage> {
        if (query.isBlank()) return catalogError(RequestFailure.INVALID, "missing_search_query")
        return open(CatalogRoute.Search(query, null))
    }

    fun library(section: LibrarySection): ReadResult<CatalogPage> = open(CatalogRoute.Library(section))

    fun related(videoId: String): ReadResult<CatalogPage> {
        if (!videoIdAccepted(videoId)) return catalogError(RequestFailure.INVALID, "invalid_video_id")
        return open(CatalogRoute.Related(videoId))
    }

    /** Prepared channel cards or typed posts, using the semantic routes discovered by ChannelSession. */
    fun channel(channelIdOrHandle: String, kind: ChannelTabKind): ReadResult<CatalogPage> {
        if (kind == ChannelTabKind.UNKNOWN) return catalogError(RequestFailure.UNSUPPORTED, "unknown_channel_tab")
        val generation = session.snapshot().generation
        return session.runBound(generation) { context ->
            val bound = SessionBoundHttp(session, generation, http)
            val opened = ChannelSession(session, bound).open(channelIdOrHandle)
            if (!opened.ok) return@runBound ReadResult(error = opened.error)
            val handle = opened.value!!
            if (kind == ChannelTabKind.POSTS) channelPosts(handle.openPosts(), context, bound, handle.preparedAuthor())
            else channelTab(handle.openTab(kind), context, bound, handle.preparedAuthor())
        }
    }

    /** Loads one owned post document and prepares its author using this catalog's cache and budget. */
    fun post(postId: String): ReadResult<PreparedPost> {
        val generation = session.snapshot().generation
        return session.runBound(generation) { context ->
            val bound = SessionBoundHttp(session, generation, http)
            val loaded = loadPost(postId, context, bound)
            if (!loaded.ok) return@runBound ReadResult(error = loaded.error)
            val post = loaded.value!!.posts.singleOrNull()?.takeIf { it.postId == postId }
                ?: return@runBound catalogError(RequestFailure.PARSE, "post_identity_mismatch")
            val prepared = preparation.prepareBound(listOf(post.preparationItem()), context, authorRequestBudget, bound)
            if (!prepared.ok) ReadResult(error = prepared.error)
            else ReadResult(prepared.value!!.items.single().post!!)
        }
    }

    fun next(cursor: CatalogCursor): ReadResult<CatalogPage> {
        if (cursor.owner !== this || synchronized(issuedCursors) { !issuedCursors.containsKey(cursor) })
            return catalogError(RequestFailure.INVALID, "foreign_catalog_cursor")
        return load(cursor.route, cursor.key, cursor.generation)
    }

    fun filter(option: CatalogFilter): ReadResult<CatalogPage> {
        if (option.owner !== this || synchronized(issuedFilters) { !issuedFilters.containsKey(option) })
            return catalogError(RequestFailure.INVALID, "foreign_catalog_filter")
        if (!option.available || option.parameters.isNullOrBlank())
            return catalogError(RequestFailure.UNSUPPORTED, "filter_not_navigable")
        return load(CatalogRoute.Search(option.query, option.parameters), null, option.generation)
    }

    /** Drops only this catalog's public author-artwork cache; never rotates identity or preferences. */
    fun clearPreparedData() = preparation.clear()

    private fun open(route: CatalogRoute): ReadResult<CatalogPage> = load(route, null, session.snapshot().generation)

    private fun load(route: CatalogRoute, key: String?, generation: Long): ReadResult<CatalogPage> =
        session.runBound(generation) { context ->
            val bound = SessionBoundHttp(session, generation, http)
            when (route) {
                CatalogRoute.Home -> browse(loadBrowse("FEwhat_to_watch", context, continuation = key, http = bound), route, context, bound)
                is CatalogRoute.Topic -> browse(loadHome(route.section, context, key, bound), route, context, bound)
                is CatalogRoute.Library -> browse(loadLibrary(route.section, context, key, bound), route, context, bound)
                is CatalogRoute.Search -> {
                    val loaded = loadSearch(route.query, context, route.params, key, bound)
                    if (!loaded.ok) ReadResult(error = loaded.error) else {
                        val page = loaded.value!!
                        val filters = page.filters.map { filter -> CatalogFilter(filter.title, filter.group, filter.selected,
                            !filter.params.isNullOrBlank(), this, generation, route.query, filter.params).also {
                            synchronized(issuedFilters) { issuedFilters[it] = Unit }
                        } }
                        prepare(page.items, page.groups, page.nextKey, page.messages, filters, route, context, bound, page.actions)
                    }
                }
                is CatalogRoute.Related -> {
                    val loaded = loadWatch(route.videoId, context, key, bound)
                    if (!loaded.ok) ReadResult(error = loaded.error) else {
                        val page = loaded.value!!
                        prepare(page.related, emptyList(), page.nextKey, emptyList(), emptyList(), route, context, bound)
                    }
                }
                is CatalogRoute.ChannelTab -> channelTab(route.cursor.owner.next(route.cursor), context, bound, route.cursor.owner.preparedAuthor())
                is CatalogRoute.ChannelPosts -> channelPosts(route.cursor.owner.nextPosts(route.cursor), context, bound, route.cursor.owner.preparedAuthor())
            }
        }

    private fun channelTab(loaded: ReadResult<ChannelTabPage>, context: RequestContext, bound: HttpClient,
        author: AuthorInfo? = null): ReadResult<CatalogPage> {
        if (!loaded.ok) return ReadResult(error = loaded.error)
        val value = loaded.value!!
        val next = value.next?.let { issue(context.generation, CatalogRoute.ChannelTab(it), null) }
        return prepare(value.page.items, value.page.groups, null, value.page.messages, emptyList(), null,
            context, bound, value.page.actions, next, author, value.ownerDisplayItems)
    }

    private fun channelPosts(loaded: ReadResult<ChannelPostsPage>, context: RequestContext, bound: HttpClient,
        author: AuthorInfo? = null): ReadResult<CatalogPage> {
        if (!loaded.ok) return ReadResult(error = loaded.error)
        val value = loaded.value!!
        val next = value.next?.let { issue(context.generation, CatalogRoute.ChannelPosts(it), null) }
        return prepare(value.page.posts.map { it.preparationItem() }, emptyList(), null, emptyList(), emptyList(), null,
            context, bound, next = next, author = author)
    }

    private fun browse(loaded: ReadResult<BrowsePage>, route: CatalogRoute, context: RequestContext,
        bound: HttpClient): ReadResult<CatalogPage> {
        if (!loaded.ok) return ReadResult(error = loaded.error)
        val page = loaded.value!!
        return prepare(page.items, page.groups, page.nextKey, page.messages, emptyList(), route, context, bound, page.actions)
    }

    private fun prepare(items: List<MediaItem>, groups: List<MediaGroup>, key: String?, messages: List<String>,
        filters: List<CatalogFilter>, route: CatalogRoute?, context: RequestContext, bound: HttpClient,
        actions: List<PageAction> = emptyList(), next: CatalogCursor? = null, author: AuthorInfo? = null,
        ownerDisplayItems: Set<MediaItem> = emptySet()): ReadResult<CatalogPage> {
        val all = (items + groups.flatMap { it.items }).distinct()
        // Reuse one instance's cache while fencing every enrichment request with this page's identity.
        val prepared = preparation.prepareBound(all, context, authorRequestBudget, bound, author)
        if (!prepared.ok) return ReadResult(error = prepared.error)
        val value = prepared.value!!
        val byItem = value.items.associateBy { it.item }
        val displayOwner = author?.takeIf { it.channelId != null && (it.avatar != null || !it.name.isNullOrBlank()) }
        return ReadResult(CatalogPage(items.map { item ->
            val card = byItem.getValue(item)
            if (displayOwner != null && item in ownerDisplayItems && item.channelId == null && card.author.avatar == null &&
                (item.author.isNullOrBlank() || item.author.trim() == displayOwner.name?.trim())) card.withCollectionOwner(displayOwner)
            else card
        }, groups.map {
            val group = CatalogGroup(it.title, it.items.map { item -> byItem.getValue(item) }, it.actions.toList())
            if (!it.nextKey.isNullOrBlank()) group.withoutContinuationRoute() else group
        }, filters.toList(), next ?: key?.takeIf { it.isNotBlank() && route != null }?.let { issue(context.generation, route!!, it) },
            messages.toList(), value.requestCount, actions.toList()))
    }

    private fun issue(generation: Long, route: CatalogRoute, key: String?): CatalogCursor =
        CatalogCursor(this, generation, route, key).also { synchronized(issuedCursors) { issuedCursors[it] = Unit } }
}

private fun <T> catalogError(kind: RequestFailure, code: String): ReadResult<T> = ReadResult(error = RequestError(kind, code))
