package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Collections
import java.util.WeakHashMap

/** App-facing video discussions: the core owns routing and all server action parameters. */
class DiscussionSession @JvmOverloads constructor(private val session: AccountSession,
    private val http: HttpClient = JvmHttpClient()) {
    private val issuedThreads = Collections.synchronizedMap(WeakHashMap<CommentThread, Boolean>())
    internal fun issued(thread: CommentThread): Boolean = issuedThreads.containsKey(thread)

    /** Reuses routing attached by loadWatch; parser-only/copy openings obtain a fresh service opening. */
    @JvmOverloads fun openVideo(videoId: String, opened: AccountResult<WatchPage>? = null,
        http: HttpClient = this.http): ReadResult<CommentThread> {
        if (!videoIdAccepted(videoId)) return discussionError(RequestFailure.INVALID, "invalid_video_id")
        val generation = session.snapshot().generation
        if (opened != null) {
            if (!opened.belongsTo(session)) return discussionError(RequestFailure.INVALID, "foreign_discussion_opening")
            if (opened.generation != generation) return discussionError(RequestFailure.CANCELLED, "session_changed")
            if (opened.value.videoId != videoId) return discussionError(RequestFailure.INVALID, "comment_video_mismatch")
        }
        return session.runBound(generation) { context ->
            val attached = opened?.value?.discussionOpening
            if (attached != null && !attached.matches(context))
                return@runBound discussionError(RequestFailure.INVALID, "foreign_discussion_opening")
            val root = attached?.result ?: readDiscussionOpening(videoId, context, SessionBoundHttp(session, generation, http))
            if (!root.ok) ReadResult(error = root.error)
            else ReadResult(CommentThread(session, http, generation, videoId, root.value!!, this).also {
                issuedThreads[it] = true
            })
        }
    }
}

/** Calls on one thread are serialized; the AccountSession monitor is never held during I/O. */
class CommentThread @JvmOverloads internal constructor(private val session: AccountSession, http: HttpClient,
    private val generation: Long, private val videoId: String, private var opening: DiscussionOpening?,
    private val issuer: DiscussionSession? = null) {
    private val http: HttpClient = http
    private class Entry(var comment: Comment, var revision: Long, var reply: DiscussionRoute?, var detail: DiscussionRoute?)
    private val entries = LinkedHashMap<Long, Entry>()
    private val identities = HashMap<String, Long>()
    // Kotlin internal constructors remain callable from Java bytecode. Membership proves actual issuance.
    private val issuedHandles = Collections.newSetFromMap(WeakHashMap<CommentHandle, Boolean>())
    private val issuedCursors = Collections.newSetFromMap(WeakHashMap<CommentCursor, Boolean>())
    private val issuedOrders = Collections.newSetFromMap(WeakHashMap<CommentSortOrder, Boolean>())
    private val pageCursors = LinkedHashMap<Pair<DiscussionRoute, Boolean>, CommentCursor>()
    private var serial = 0L
    private var revision = 0L
    private var createKey: String? = null
    private var total: String? = null
    private var turnedOff = false
    private var orders = emptyList<CommentSortOrder>()

    private fun <T> bound(http: HttpClient, operation: (RequestContext, HttpClient) -> ReadResult<T>): ReadResult<T> =
        if (issuer?.issued(this) != true) discussionError(RequestFailure.INVALID, "unissued_comment_thread")
        else session.runBound(generation) { context -> operation(context, SessionBoundHttp(session, generation, http)) }

    /** A second load refreshes the watch opening as well as the visible comments and their permissions. */
    @JvmOverloads @Synchronized fun load(http: HttpClient = this.http): ReadResult<CommentThreadPage> = loadRoot(null, http)

    /** Selects a position in the service's ordered sort menu, without matching translated labels. */
    @JvmOverloads @Synchronized fun load(sortIndex: Int, http: HttpClient = this.http): ReadResult<CommentThreadPage> {
        if (sortIndex < 0) return discussionError(RequestFailure.INVALID, "invalid_comment_sort_index")
        val loaded = loadRoot(sortIndex, http)
        if (!loaded.ok) return loaded
        val order = loaded.value!!.sortOrders.getOrNull(sortIndex)
        return if (order != null && !order.selected) sort(order, http) else loaded
    }

    private fun loadRoot(sortIndex: Int?, http: HttpClient): ReadResult<CommentThreadPage> = bound(http) { context, http ->
        val initial = opening
        opening = null
        val fresh = if (initial != null) ReadResult(initial) else readDiscussionOpening(videoId, context, http)
        if (!fresh.ok) return@bound ReadResult(error = fresh.error)
        var root = fresh.value!!
        val choice = sortIndex?.let { root.watch.commentSortOptions.getOrNull(it) }
        val route = if (choice == null) root.route else {
            val selected = discussionRoute(root.root, choice.key, YOUTUBE_API + "next")
                ?: return@bound discussionError(RequestFailure.UNSUPPORTED, "unsupported_comment_sort_route")
            root = root.copy(watch = root.watch.copy(commentSortOptions = root.watch.commentSortOptions.map {
                it.copy(selected = it === choice)
            }))
            selected
        }
        if (route == null) {
            publish(DiscussionDocument(root.root, CommentPage(emptyList(), null, root.watch.commentCreateKey,
                root.watch.commentSortOptions, root.watch.commentCount, root.watch.commentsTurnedOff), YOUTUBE_API + "next"), context, true)
        } else {
            val read = readDiscussion(route, context, http)
            if (!read.ok) return@bound ReadResult(error = read.error)
            val document = read.value!!
            val page = document.page.copy(createKey = document.page.createKey ?: root.watch.commentCreateKey,
                total = root.watch.commentCount, turnedOff = root.watch.commentsTurnedOff)
            publish(document.copy(page = page), context, true, root, consumedRoute = route)
        }
    }

    @JvmOverloads @Synchronized fun next(cursor: CommentCursor, http: HttpClient = this.http): ReadResult<CommentThreadPage> = bound(http) { context, http ->
        if (cursor.owner !== this) return@bound discussionError(RequestFailure.INVALID, "foreign_comment_cursor")
        if (cursor !in issuedCursors) return@bound discussionError(RequestFailure.INVALID, "unissued_comment_cursor")
        if (cursor.revision != revision) return@bound discussionError(RequestFailure.CANCELLED, "discussion_page_changed")
        val read = readDiscussion(cursor.route, context, http, replies = cursor.replies)
        if (!read.ok) ReadResult(error = read.error) else publish(read.value!!, context, false, replies = cursor.replies,
            consumedRoute = cursor.route)
    }

    @JvmOverloads @Synchronized fun sort(order: CommentSortOrder, http: HttpClient = this.http): ReadResult<CommentThreadPage> = bound(http) { context, http ->
        if (order.owner !== this) return@bound discussionError(RequestFailure.INVALID, "foreign_comment_sort")
        if (order !in issuedOrders) return@bound discussionError(RequestFailure.INVALID, "unissued_comment_sort")
        if (order.revision != revision) return@bound discussionError(RequestFailure.CANCELLED, "discussion_page_changed")
        val read = readDiscussion(order.route, context, http)
        if (!read.ok) ReadResult(error = read.error) else {
            val document = read.value!!
            val page = document.page.copy(createKey = document.page.createKey ?: createKey, total = document.page.total ?: total)
            publish(document.copy(page = page), context, true, inheritedOrders = orders, selectedOrder = order,
                consumedRoute = order.route)
        }
    }

    @JvmOverloads @Synchronized fun replies(handle: CommentHandle, http: HttpClient = this.http): ReadResult<CommentThreadPage> = bound(http) { context, http ->
        val entry = entry(handle) ?: return@bound handleError(handle)
        val route = entry.reply ?: return@bound discussionError(RequestFailure.UNSUPPORTED, "missing_comment_reply_continuation")
        val read = readDiscussion(route, context, http, replies = true)
        if (!read.ok) ReadResult(error = read.error) else publish(read.value!!, context, false, replies = true, consumedRoute = route)
    }

    @JvmOverloads @Synchronized fun create(text: String, http: HttpClient = this.http): ReadResult<CommentMutation> = bound(http) { context, http ->
        if (!context.signedIn) return@bound discussionError(RequestFailure.AUTH, "sign_in_required")
        if (text.isBlank()) return@bound discussionError(RequestFailure.INVALID, "invalid_comment_text")
        val key = createKey?.takeIf(String::isNotBlank)
            ?: return@bound discussionError(RequestFailure.UNSUPPORTED, "missing_comment_creation_capability")
        mutation(createComment(text, key, context, http), null)
    }

    @JvmOverloads @Synchronized fun reply(handle: CommentHandle, text: String, http: HttpClient = this.http): ReadResult<CommentMutation> = bound(http) { context, http ->
        if (!context.signedIn) return@bound discussionError(RequestFailure.AUTH, "sign_in_required")
        if (text.isBlank()) return@bound discussionError(RequestFailure.INVALID, "invalid_comment_text")
        val resolved = resolve(handle, context, http) { !it.replyCreateKey.isNullOrBlank() }
        if (!resolved.ok) return@bound ReadResult(error = resolved.error)
        val key = resolved.value!!.comment.replyCreateKey?.takeIf(String::isNotBlank)
            ?: return@bound discussionError(RequestFailure.UNSUPPORTED, "missing_comment_reply_capability")
        mutation(replyComment(text, key, context, http), null)
    }

    /** Fixed intent: a fresh detail read may supply tokens but cannot reinterpret LIKE as UNLIKE. */
    @JvmOverloads @Synchronized fun rate(handle: CommentHandle, vote: CommentVote, http: HttpClient = this.http): ReadResult<CommentMutation> = bound(http) { context, http ->
        if (!context.signedIn) return@bound discussionError(RequestFailure.AUTH, "sign_in_required")
        val resolved = resolve(handle, context, http) { !voteKey(it, vote).isNullOrBlank() }
        if (!resolved.ok) return@bound ReadResult(error = resolved.error)
        val comment = resolved.value!!.comment
        if (voteKey(comment, vote).isNullOrBlank()) return@bound discussionError(RequestFailure.UNSUPPORTED, "missing_comment_rating_capability")
        mutation(rateComment(comment.copy(detailKey = null), vote, context, http), handle)
    }

    @JvmOverloads @Synchronized fun delete(handle: CommentHandle, http: HttpClient = this.http): ReadResult<CommentMutation> = bound(http) { context, http ->
        if (!context.signedIn) return@bound discussionError(RequestFailure.AUTH, "sign_in_required")
        val resolved = resolve(handle, context, http) { !it.deleteKey.isNullOrBlank() }
        if (!resolved.ok) return@bound ReadResult(error = resolved.error)
        val key = resolved.value!!.comment.deleteKey?.takeIf(String::isNotBlank)
            ?: return@bound discussionError(RequestFailure.UNSUPPORTED, "missing_comment_delete_capability")
        // A returned comment entity alone does not prove that the comment was deleted.
        val reply = requestAction(queryInContext(commentActionQuery(key, context.visitor), context), http,
            entityScope = ActionEntityScope.None)
        if (!reply.ok) return@bound ReadResult(error = reply.error)
        val action = reply.value!!
        if (action.confirmed) entries[handle.entry]?.revision = -1
        ReadResult(CommentMutation(null, action.state, action.httpStatus))
    }

    @Synchronized internal fun content(handle: CommentHandle): DiscussionComment? =
        if (issuer?.issued(this) == true && handle in issuedHandles && session.snapshot(generation).ok)
            entries[handle.entry]?.comment?.let(::DiscussionComment) else null
    @Synchronized internal fun permissions(handle: CommentHandle): CommentPermissions {
        if (issuer?.issued(this) != true) return CommentPermissions(false, false, false, false, emptySet())
        val context = session.snapshot(generation).value
        val entry = entry(handle)
        if (context == null || entry == null) return CommentPermissions(false, false, false, false, emptySet())
        val signed = context.signedIn
        return CommentPermissions(signed && !entry.comment.replyCreateKey.isNullOrBlank(), signed && !entry.comment.deleteKey.isNullOrBlank(),
            entry.reply != null, entry.detail != null, if (signed) CommentVote.values().filter { !voteKey(entry.comment, it).isNullOrBlank() }.toSet() else emptySet())
    }

    private fun resolve(handle: CommentHandle, context: RequestContext, http: HttpClient, capable: (Comment) -> Boolean): ReadResult<Entry> {
        val found = entry(handle) ?: return handleError(handle)
        if (capable(found.comment)) return ReadResult(found)
        val route = found.detail ?: return ReadResult(found)
        val read = readDiscussion(route, context, http)
        if (!read.ok) return ReadResult(error = read.error)
        val page = read.value!!
        val target = page.page.comments.firstOrNull { it.id != null && it.id == found.comment.id }
            ?: return discussionError(RequestFailure.PARSE, "missing_comment_action_target")
        // A complete fresh renderer replaces permissions; omitted actions are never merged back from an older read.
        store(target, page)
        return ReadResult(entries[handle.entry]!!)
    }

    private fun mutation(result: ReadResult<CommentActionReply>, target: CommentHandle?): ReadResult<CommentMutation> {
        if (!result.ok) return ReadResult(error = result.error)
        val reply = result.value!!
        var delivered = target
        val comment = reply.comment
        if (reply.action.confirmed && comment != null) {
            if (target == null || comment.id == entries[target.entry]?.comment?.id) {
                val root = jsonObject(reply.action.body)?.let(::ownedCommentMutation)
                    ?: return ReadResult(CommentMutation(null, reply.action.state, reply.action.httpStatus))
                val previous = target?.let { entries[it.entry] }
                val id = if (target != null && previous != null) {
                    previous.comment = comment
                    target.entry
                } else store(comment, DiscussionDocument(root, CommentPage(listOf(comment), null, null), YOUTUBE_API + "next"))
                delivered = handle(id)
            }
        }
        return ReadResult(CommentMutation(delivered, reply.action.state, reply.action.httpStatus))
    }

    private fun publish(document: DiscussionDocument, context: RequestContext, reset: Boolean,
        opening: DiscussionOpening? = null, replies: Boolean = false,
        inheritedOrders: List<CommentSortOrder>? = null, selectedOrder: CommentSortOrder? = null,
        consumedRoute: DiscussionRoute? = null): ReadResult<CommentThreadPage> {
        val page = document.page
        val next = page.nextKey?.let { discussionRoute(commentSection(document.root), it, document.endpoint) }
        if (page.nextKey != null && next == null) return discussionError(RequestFailure.UNSUPPORTED, "unsupported_comment_continuation_route")
        if (reset) {
            // Keep same-comment handles fresh, but release entries absent from this replacement page.
            // Serial IDs never restart: a comment that disappears and later returns cannot revive an old grant.
            val visible = page.comments.mapNotNull { it.id }.toSet()
            val retained = identities.filterKeys { it in visible }.values.toSet()
            entries.keys.retainAll(retained)
            identities.keys.retainAll(visible)
            revision++
            pageCursors.clear()
            createKey = page.createKey?.takeIf(String::isNotBlank)
            total = page.total
            turnedOff = page.turnedOff
            val sourceOrders = if (page.sortOptions.isNotEmpty()) page.sortOptions else opening?.watch?.commentSortOptions.orEmpty()
            val source = if (page.sortOptions.isNotEmpty() || opening == null) document.root else opening.root
            val endpoint = if (page.sortOptions.isNotEmpty() || opening == null) document.endpoint else YOUTUBE_API + "next"
            orders = if (sourceOrders.isEmpty() && inheritedOrders != null) inheritedOrders.map {
                CommentSortOrder(this, revision, it.route, it.title, it === selectedOrder).also(issuedOrders::add)
            } else sourceOrders.mapNotNull { sort -> discussionRoute(source, sort.key, endpoint)?.let {
                CommentSortOrder(this, revision, it, sort.title, sort.selected).also(issuedOrders::add)
            } }
        }
        val comments = page.comments.map { handle(store(it, document)) }
        val cursor = next?.takeUnless { it == consumedRoute }?.let { route ->
            pageCursors.getOrPut(route to replies) {
                CommentCursor(this, revision, route, replies).also(issuedCursors::add)
            }.also { while (pageCursors.size > 128) pageCursors.remove(pageCursors.keys.first()) }
        }
        return ReadResult(CommentThreadPage(comments, cursor,
            if (replies) emptyList() else orders.toList(), if (replies) page.total else total,
            if (replies) page.turnedOff else turnedOff, context.signedIn && !createKey.isNullOrBlank() && !turnedOff))
    }

    private fun store(comment: Comment, document: DiscussionDocument): Long {
        val id = comment.id?.let { identities[it] } ?: ++serial
        comment.id?.let { identities[it] = id }
        val renderer = commentRenderers(document.root, commentSection(document.root)).firstOrNull { it.text("commentId") == comment.id }
        val thread = commentSection(document.root).objects("commentThreadRenderer").firstOrNull {
            it.path("comment", "commentRenderer")?.text("commentId") == comment.id
        }
        val source = thread ?: renderer ?: document.root
        entries[id] = Entry(comment, revision, discussionRoute(source, comment.replyKey, document.endpoint),
            discussionRoute(source, comment.detailKey, document.endpoint))
        return id
    }
    private fun handle(id: Long) = CommentHandle(this, id, DiscussionComment(entries.getValue(id).comment)).also(issuedHandles::add)
    private fun entry(handle: CommentHandle): Entry? = if (handle.owner !== this || handle !in issuedHandles) null
        else entries[handle.entry]?.takeIf { it.revision == revision }
    private fun <T> handleError(handle: CommentHandle): ReadResult<T> = when {
        handle.owner !== this -> discussionError(RequestFailure.INVALID, "foreign_comment_handle")
        handle !in issuedHandles -> discussionError(RequestFailure.INVALID, "unissued_comment_handle")
        else -> discussionError(RequestFailure.CANCELLED, "comment_handle_expired")
    }
    override fun toString() = "CommentThread"
}

private fun voteKey(comment: Comment, vote: CommentVote): String? = when (vote) {
    CommentVote.LIKE -> comment.likeKey
    CommentVote.UNLIKE -> comment.unlikeKey
    CommentVote.DISLIKE -> comment.dislikeKey
    CommentVote.UNDISLIKE -> comment.undislikeKey
}
private fun <T> discussionError(kind: RequestFailure, code: String): ReadResult<T> = ReadResult(error = RequestError(kind, code))
