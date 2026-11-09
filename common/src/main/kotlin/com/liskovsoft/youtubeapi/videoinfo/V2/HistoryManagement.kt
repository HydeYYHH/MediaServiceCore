package com.liskovsoft.youtubeapi.videoinfo.V2

/** A successful management read can still leave the current recording state unknown. */
data class HistoryManagementState(val paused: Boolean?)

/**
 * An enabled pause command means recording is active; an enabled resume command means
 * it is paused. Missing or contradictory controls do not establish either state.
 */
fun watchHistoryManagement(body: String): HistoryManagementState = historyManagement(jsonObject(body))

private fun historyManagement(root: Json.Obj?): HistoryManagementState {
    val states = mutableSetOf<Boolean>()
    fun visit(value: Json) {
        when (value) {
            is Json.Obj -> {
                if (value.bool("isDisabled") == true || value.bool("disabled") == true) return
                if (value.obj("pauseWatchHistoryEndpoint") != null) states += false
                if (value.obj("resumeWatchHistoryEndpoint") != null) states += true
                when (value.path("commandMetadata", "webCommandMetadata")?.text("apiUrl")?.substringBefore('?')) {
                    "/youtubei/v1/history/pause_watch_history", YOUTUBE_API + "history/pause_watch_history" -> states += false
                    "/youtubei/v1/history/resume_watch_history", YOUTUBE_API + "history/resume_watch_history" -> states += true
                }
                value.fields.values.forEach(::visit)
            }
            is Json.Arr -> value.items.forEach(::visit)
            else -> Unit
        }
    }
    root?.let(::visit)
    return HistoryManagementState(states.singleOrNull())
}

/** Uncached management metadata for the captured account, independent of history entries. */
fun loadWatchHistoryManagement(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<HistoryManagementState> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val result = requestObject(browseQuery(LibrarySection.HISTORY.browseId, context).withCompactResponse(), http)
    return if (result.ok) ReadResult(historyManagement(result.value)) else ReadResult(error = result.error)
}
