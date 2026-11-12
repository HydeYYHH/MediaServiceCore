package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Collections
import java.util.WeakHashMap

enum class PlaylistPresence { PRESENT, ABSENT, UNKNOWN }
enum class PlaylistMembershipOutcome { ALREADY_MATCHED, VERIFIED, UNKNOWN }

/** Opens the service's save-to-playlist directory for one video, without inventing target playlists. */
class PlaylistMembershipSession @JvmOverloads constructor(private val session: AccountSession,
    private val http: HttpClient = JvmHttpClient()) {
    private val issuedDirectories = Collections.synchronizedMap(WeakHashMap<PlaylistMembershipDirectory, Boolean>())
    private val mutationLock = Any()
    private val writeIntents = HashMap<String, MembershipIntent>()
    private var mutationGeneration: Long? = null
    internal fun issued(directory: PlaylistMembershipDirectory): Boolean = issuedDirectories.containsKey(directory)

    /** All directories from this business session share one bounded, generation-scoped write ledger. */
    @JvmSynthetic
    internal fun <T> serializedMutation(directory: PlaylistMembershipDirectory, generation: Long,
        operation: (MutableMap<String, MembershipIntent>) -> ReadResult<T>): ReadResult<T> = synchronized(mutationLock) {
        if (!issued(directory)) return@synchronized membershipFailure(RequestFailure.INVALID, "unissued_playlist_membership_directory")
        session.snapshot(generation).error?.let { return@synchronized ReadResult(error = it) }
        if (mutationGeneration != generation) {
            writeIntents.clear()
            mutationGeneration = generation
        }
        operation(writeIntents)
    }

    fun open(videoId: String): ReadResult<PlaylistMembershipDirectory> {
        val generation = session.snapshot().generation
        return session.runBound(generation) { context ->
            if (!context.signedIn) return@runBound membershipFailure(RequestFailure.AUTH, "sign_in_required")
            if (!videoIdAccepted(videoId)) return@runBound membershipFailure(RequestFailure.INVALID, "invalid_video_id")
            val bound = SessionBoundHttp(session, generation, http)
            val choices = membershipChoices(videoId, context, bound)
            if (!choices.ok) ReadResult(error = choices.error)
            else ReadResult(PlaylistMembershipDirectory(session, generation, bound, videoId, choices.value!!, this).also {
                issuedDirectories[it] = true
            })
        }
    }
}

/** Display metadata plus an opaque directory-owned target capability. Presence is the opening snapshot. */
class PlaylistMembershipEntry internal constructor(internal val owner: PlaylistMembershipDirectory,
    val playlistId: String, val title: String?, val presence: PlaylistPresence, val editable: Boolean) {
    override fun toString() = "PlaylistMembershipEntry(presence=$presence, editable=$editable)"
}

/** A fresh read and at most one write attempt. VERIFIED requires both an explicit ACK and matching readback. */
class PlaylistMembershipUpdate internal constructor(val desired: Boolean, val before: PlaylistPresence,
    val after: PlaylistPresence, val outcome: PlaylistMembershipOutcome,
    /** True means an edit was attempted; it is not proof that the remote service received it. */
    val submitted: Boolean, val serverConfirmed: Boolean, val action: PlaylistActionReply?,
    val writeError: RequestError?, val readError: RequestError?) {
    override fun toString() = "PlaylistMembershipUpdate(outcome=$outcome, submitted=$submitted, serverConfirmed=$serverConfirmed)"
}

/** Writes across this business session are serialized without holding the AccountSession monitor during IO. */
class PlaylistMembershipDirectory @JvmOverloads internal constructor(private val session: AccountSession, private val generation: Long,
    private val http: HttpClient, private val videoId: String, choices: List<MembershipChoice>,
    private val issuer: PlaylistMembershipSession? = null) {
    val entries: List<PlaylistMembershipEntry> = Collections.unmodifiableList(choices.map {
        PlaylistMembershipEntry(this, it.choice.id, it.choice.title, presence(it.choice.containsState), it.editable)
    })
    fun setContains(entry: PlaylistMembershipEntry, desired: Boolean): ReadResult<PlaylistMembershipUpdate> {
        // An internal Kotlin constructor is JVM-public. Copying a real issuer cannot register another directory.
        if (issuer?.issued(this) != true)
            return membershipFailure(RequestFailure.INVALID, "unissued_playlist_membership_directory")
        return issuer.serializedMutation(this, generation) { writeIntents -> session.runBound(generation) { context ->
            if (entry.owner !== this || entries.none { it === entry })
                return@runBound membershipFailure(RequestFailure.INVALID, "foreign_playlist_membership_entry")
            if (!entry.editable) return@runBound membershipFailure(RequestFailure.UNSUPPORTED, "playlist_not_editable")
            val before = observe(entry, context)
            if (before.error?.kind == RequestFailure.CANCELLED) return@runBound ReadResult(error = before.error)
            if (before.editable == false) return@runBound membershipFailure(RequestFailure.UNSUPPORTED, "playlist_not_editable")
            val mutationKey = "$videoId:${entry.playlistId}"
            val previous = writeIntents[mutationKey]
            if (previous != null && before.state != presence(previous.desired)) {
                // A stale snapshot and an external edit are indistinguishable. Retain the last
                // submitted intent even after verification, rather than append the video again
                // or treat an opposite request as an already-matched no-op.
                return@runBound ReadResult(update(desired, before, before, readError = before.error
                    ?: RequestError(RequestFailure.UNSUPPORTED, if (previous.verified)
                        "membership_readback_regressed" else "membership_write_unresolved")))
            }
            if (before.state == PlaylistPresence.UNKNOWN) return@runBound ReadResult(update(desired, before, before))
            if (before.state == presence(desired)) return@runBound ReadResult(update(desired, before, before,
                outcome = PlaylistMembershipOutcome.ALREADY_MATCHED))
            if (previous == null && writeIntents.size >= 256)
                return@runBound membershipFailure(RequestFailure.UNSUPPORTED, "membership_pending_limit")

            // No retry path: a lost response must not duplicate an append.
            writeIntents[mutationKey] = MembershipIntent(desired, verified = false)
            val edit = editPlaylist(entry.playlistId, listOf(if (desired) PlaylistChange.Add(videoId)
                else PlaylistChange.RemoveByVideoId(videoId)), context, http)
            if (edit.error?.kind == RequestFailure.CANCELLED) return@runBound ReadResult(error = edit.error)
            session.snapshot(generation).error?.let { return@runBound ReadResult(error = it) }
            val after = observe(entry, context)
            if (after.error?.kind == RequestFailure.CANCELLED) return@runBound ReadResult(error = after.error)
            val confirmed = edit.value?.action?.confirmed == true
            val verified = confirmed && after.error == null && after.state == presence(desired)
            if (verified) writeIntents[mutationKey] = MembershipIntent(desired, verified = true)
            ReadResult(update(desired, before, after, if (verified) PlaylistMembershipOutcome.VERIFIED else PlaylistMembershipOutcome.UNKNOWN,
                submitted = true, serverConfirmed = confirmed, action = edit.value, writeError = edit.error))
        } }
    }

    private fun observe(entry: PlaylistMembershipEntry, context: RequestContext): MembershipObservation {
        val choices = membershipChoices(videoId, context, http)
        if (!choices.ok) return MembershipObservation(PlaylistPresence.UNKNOWN, error = choices.error)
        val matches = choices.value!!.filter { it.choice.id == entry.playlistId }
        if (matches.size > 1) return MembershipObservation(PlaylistPresence.UNKNOWN,
            error = RequestError(RequestFailure.PARSE, "ambiguous_playlist_membership"))
        val current = matches.singleOrNull()
        if (current?.editable == false) return MembershipObservation(PlaylistPresence.UNKNOWN, false)

        // Save-to-playlist flags can lag a successful edit. They are selection/permission metadata,
        // never the membership oracle: every decision reads only the target this directory discovered.
        val seen = HashSet<String>()
        var cursor: String? = null
        var modernCollection = false
        repeat(8) {
            var collection: MembershipCollection? = null
            val observing = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply = http.execute(request).also { reply ->
                    val root = jsonObject(reply.body)
                    collection = root?.let { membershipCollection(it, cursor != null, entry.playlistId, modernCollection) }
                }
            }
            val page = loadPlaylist(entry.playlistId, context, cursor, observing)
            if (!page.ok) return MembershipObservation(PlaylistPresence.UNKNOWN, current?.editable, page.error)
            val value = collection ?: return MembershipObservation(PlaylistPresence.UNKNOWN,
                current?.editable, RequestError(RequestFailure.PARSE, "membership_collection_not_explicit"))
            if (!value.complete) return MembershipObservation(PlaylistPresence.UNKNOWN, current?.editable,
                RequestError(RequestFailure.PARSE, "membership_collection_incomplete"))
            if (value.videoIds.contains(videoId)) return MembershipObservation(PlaylistPresence.PRESENT, current?.editable)
            modernCollection = value.modern
            val next = value.next
                ?: return MembershipObservation(PlaylistPresence.ABSENT, current?.editable)
            if (!seen.add(next)) return MembershipObservation(PlaylistPresence.UNKNOWN, current?.editable,
                RequestError(RequestFailure.PARSE, "membership_cursor_loop"))
            cursor = next
        }
        return MembershipObservation(PlaylistPresence.UNKNOWN, current?.editable,
            RequestError(RequestFailure.UNSUPPORTED, "membership_scan_limit"))
    }

    private fun update(desired: Boolean, before: MembershipObservation, after: MembershipObservation,
        outcome: PlaylistMembershipOutcome = PlaylistMembershipOutcome.UNKNOWN, submitted: Boolean = false,
        serverConfirmed: Boolean = false, action: PlaylistActionReply? = null, writeError: RequestError? = null,
        readError: RequestError? = after.error) = PlaylistMembershipUpdate(desired, before.state, after.state,
            outcome, submitted, serverConfirmed, action, writeError, readError)
    override fun toString() = "PlaylistMembershipDirectory(entryCount=${entries.size})"
}

internal data class MembershipChoice(val choice: PlaylistChoice, val editable: Boolean)
internal data class MembershipIntent(val desired: Boolean, val verified: Boolean)
private data class MembershipObservation(val state: PlaylistPresence, val editable: Boolean? = null, val error: RequestError? = null)

private data class MembershipCollection(val videoIds: List<String>, val next: String?, val complete: Boolean, val modern: Boolean)

/** Scope membership to the playlist's explicit collection, excluding recommendations and unrelated cards. */
private fun membershipCollection(root: Json.Obj, continuation: Boolean, playlistId: String,
    modernContinuation: Boolean = false): MembershipCollection? {
    val collections = ArrayList<Json.Obj>()
    val target = playlistId.removePrefix("VL")
    var modern = modernContinuation
    var invalidModern = false
    fun knownWrapper(value: Json?, kinds: Set<String>): Boolean {
        val wrapper = value as? Json.Obj ?: return false
        return wrapper.fields.keys.all { it in kinds || it in setOf("trackingParams", "clickTrackingParams") } &&
            kinds.count { it in wrapper.fields } == 1 && kinds.any { wrapper.obj(it) != null }
    }
    if (continuation) {
        root.obj("continuationContents")?.obj("playlistVideoListContinuation")?.let { collections.add(it) }
        for (name in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
            for (action in root.arr(name)) for (key in listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand")) {
                (action as? Json.Obj)?.obj(key)?.let { collections.add(it) }
            }
        }
    } else {
        fun visit(node: Json) {
            when (node) {
                is Json.Obj -> {
                    node.obj("playlistVideoListRenderer")?.let { collections.add(it); return }
                    node.obj("sectionListRenderer")?.takeIf { it.text("targetId")?.startsWith("VL") == true }?.let { section ->
                        val wrappers = (section.fields["contents"] as? Json.Arr)?.items
                        val sections = wrappers?.mapNotNull { (it as? Json.Obj)?.obj("itemSectionRenderer") }
                        val inner = sections?.singleOrNull()
                        val innerItems = inner?.fields?.get("contents") as? Json.Arr
                        val legacyWrapper = innerItems?.items?.singleOrNull() as? Json.Obj
                        val legacy = legacyWrapper?.obj("playlistVideoListRenderer")
                        if (!knownWrapper(node, setOf("sectionListRenderer")) || wrappers == null ||
                            section.text("targetId") != "VL$target" || inner == null || innerItems == null) invalidModern = true
                        else if (legacy != null) {
                            // Observed WEB legacy list inside a VL section. The item section has no target;
                            // both identities on the list itself must match. Its outer sibling cursor is
                            // outside this collection and must never become a playlist pagination token.
                            if (!knownWrapper(legacyWrapper, setOf("playlistVideoListRenderer")) ||
                                legacy.text("playlistId") != target || legacy.text("targetId") != target ||
                                ("targetId" in inner.fields && inner.text("targetId") != target) ||
                                wrappers.any { !knownWrapper(it, setOf("itemSectionRenderer", "continuationItemRenderer")) } ||
                                wrappers.count { (it as? Json.Obj)?.obj("continuationItemRenderer") != null } > 1) invalidModern = true
                            else collections.add(legacy)
                        } else {
                            // Modern WEB collection: VL target -> one item-section target -> direct lockups.
                            modern = true
                            if (inner.text("targetId") != target ||
                                wrappers.any { !knownWrapper(it, setOf("itemSectionRenderer", "continuationItemViewModel")) }) invalidModern = true
                            else collections.add(Json.Obj(mapOf("playlistId" to Json.Str(target), "contents" to Json.Arr(
                                innerItems.items + wrappers.filter { (it as? Json.Obj)?.obj("continuationItemViewModel") != null }))))
                        }
                        return
                    }
                    for ((key, child) in node.fields) if (key !in setOf("menu", "header", "metadata", "secondaryContents",
                        "shelfRenderer", "engagementPanels", "navigationEndpoint")) visit(child)
                }
                is Json.Arr -> node.items.forEach(::visit)
                else -> Unit
            }
        }
        root.fields["contents"]?.let(::visit)
    }
    if (invalidModern) return null
    val collection = collections.singleOrNull() ?: return null
    collection.text("playlistId")?.let { if (it.removePrefix("VL") != playlistId.removePrefix("VL")) return null }
    if ("targetId" in collection.fields && collection.text("targetId") !in setOf(target, "VL$target")) return null
    if ("contents" in collection.fields && "continuationItems" in collection.fields) return null
    val items = (collection.fields[if ("continuationItems" in collection.fields) "continuationItems" else "contents"] as? Json.Arr)
        ?.items ?: return null
    val ids = ArrayList<String>()
    val tokens = ArrayList<String>()
    var complete = true
    for (item in items) {
        val card = item as? Json.Obj
        val kinds = setOf("playlistVideoRenderer", "tileRenderer", "lockupViewModel", "continuationItemRenderer", "continuationItemViewModel")
        if (card == null || card.fields.keys.any { it !in kinds + setOf("trackingParams", "clickTrackingParams") } ||
            kinds.count { it in card.fields } != 1) { complete = false; continue }
        if (modern && card.fields.keys.none { it in setOf("lockupViewModel", "continuationItemViewModel") }) {
            complete = false; continue
        }
        val lockup = card.obj("lockupViewModel")
        val watch = lockup?.path("rendererContext", "commandContext", "onTap", "innertubeCommand", "watchEndpoint")
        if (lockup != null && (watch == null || lockup.text("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO" ||
                watch.text("playlistId") != target || watch.text("videoId").isNullOrBlank() ||
                lockup.text("contentId")?.let { it != watch.text("videoId") } == true)) {
            complete = false; continue
        }
        val video = card?.obj("playlistVideoRenderer")?.text("videoId")
            ?: card?.obj("tileRenderer")?.path("onSelectCommand", "watchEndpoint")?.text("videoId")
            ?: watch?.text("videoId")
        val continuationItem = card?.obj("continuationItemRenderer")
        val continuationModel = card?.obj("continuationItemViewModel")
        when {
            video != null && videoIdAccepted(video) -> ids.add(video)
            continuationItem != null -> {
                val token = continuationItem.path("continuationEndpoint", "continuationCommand")?.text("token")
                if (token.isNullOrBlank()) complete = false else tokens.add(token)
            }
            continuationModel != null -> {
                val command = continuationModel.path("continuationCommand", "innertubeCommand", "continuationCommand")
                val token = command?.text("token")
                if (command == null || token.isNullOrBlank() || command.text("request") != "CONTINUATION_REQUEST_TYPE_BROWSE") complete = false
                else tokens.add(token)
            }
            else -> complete = false
        }
    }
    if ("continuations" in collection.fields) {
        val continuations = collection.fields["continuations"] as? Json.Arr
        if (continuations == null) complete = false else for (item in continuations.items) {
            val token = (item as? Json.Obj)?.obj("nextContinuationData")?.text("continuation")
            if (token.isNullOrBlank()) complete = false else tokens.add(token)
        }
    }
    if (tokens.size > 1) complete = false
    return MembershipCollection(ids, tokens.singleOrNull(), complete, modern)
}

/** Reuses the public loader and parser, retaining only the option's editing permission from its response. */
private fun membershipChoices(videoId: String, context: RequestContext, http: HttpClient): ReadResult<List<MembershipChoice>> {
    var options: List<Json.Obj>? = null
    val observing = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply = http.execute(request).also { reply ->
            val root = jsonObject(reply.body)
            val renderer = (root?.fields?.get("contents") as? Json.Arr)?.items?.firstOrNull()
                ?.let { it as? Json.Obj }?.obj("addToPlaylistRenderer")
            options = (renderer?.fields?.get("playlists") as? Json.Arr)?.items?.mapNotNull { (it as? Json.Obj)?.obj("playlistAddToOptionRenderer") }
        }
    }
    val loaded = loadPlaylistChoices(videoId, context, observing)
    if (!loaded.ok) return ReadResult(error = loaded.error)
    val raw = options ?: return membershipFailure(RequestFailure.PARSE, "unknown_playlist_choices")
    val permitted = raw.groupBy { it.text("playlistId") }.mapValues { (_, values) ->
        values.size == 1 && values.single().let { option ->
            fun allows(name: String, expected: Boolean) = name !in option.fields || option.bool(name) == expected
            // Missing flags are the original selectable-option protocol; explicit/malformed restrictions win.
            allows("canEdit", true) && allows("isDisabled", false) && allows("disabled", false) && allows("isSupported", true)
        }
    }
    val parsed = loaded.value!!
    if (parsed.any { !videoIdAccepted(it.id) }) return membershipFailure(RequestFailure.PARSE, "invalid_playlist_choice_identity")
    return ReadResult(parsed.map { MembershipChoice(it, permitted[it.id] == true) })
}

private fun presence(value: Boolean?) = when (value) { true -> PlaylistPresence.PRESENT; false -> PlaylistPresence.ABSENT; null -> PlaylistPresence.UNKNOWN }
private fun <T> membershipFailure(kind: RequestFailure, code: String) = ReadResult<T>(error = RequestError(kind, code))
