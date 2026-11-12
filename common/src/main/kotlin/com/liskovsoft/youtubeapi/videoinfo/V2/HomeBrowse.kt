package com.liskovsoft.youtubeapi.videoinfo.V2

enum class HomeSection { HOME, SHORTS, LIVE, MUSIC, GAMING, NEWS, SPORTS, MOVIES, KIDS }
fun homeQuery(section: HomeSection = HomeSection.HOME, context: RequestContext = RequestContext(), continuation: String? = null): PlayerQuery {
    if (section == HomeSection.HOME) return browseQuery("FEwhat_to_watch", context, continuation = continuation)
    if (section == HomeSection.KIDS) return if (kidsHomeContextError(context) == null) kidsHomeQuery(context, continuation)
        else PlayerQuery("", emptyMap(), "")
    if (section == HomeSection.SHORTS) return youtubeQuery(YOUTUBE_API + "reel/reel_watch_sequence",
        if (continuation != null) payload("sequenceParams" to continuation) else
            "\"disablePlayerResponse\":true,\"inputType\":\"REEL_WATCH_INPUT_TYPE_SEEDLESS\",\"params\":\"CA8%3D\"", context)
    val id = "FEtopics_" + section.name.lowercase(java.util.Locale.ROOT)
    val fields = if (continuation != null) payload("continuation" to continuation)
        else payload("browseId" to id)
    // Topic ids are dead for the WEB client (HTTP 400); the TV client serves them.
    return contextualQuery(tvQuery(YOUTUBE_API + "browse", fields, context.visitor), context)
}
fun loadHome(section: HomeSection = HomeSection.HOME, context: RequestContext = RequestContext(), continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<BrowsePage> {
    if (section == HomeSection.KIDS) kidsHomeContextError(context)?.let { return ReadResult(error = it) }
    // Seedless reel sequence calls are rejected (400); use the working document
    // directly. Sequence continuations still use their original API lane.
    val document = if (section == HomeSection.SHORTS && continuation == null) {
        loadShortsDocument(context, http).also {
            if (it.ok || it.error?.kind != RequestFailure.PARSE) return it
        }
    } else null
    val query = homeQuery(section, context, continuation)
    val result = requestObject(if (section == HomeSection.SHORTS) query else query.withCompactResponse(), http)
    if (!result.ok && document != null) return document
    if (!result.ok) return ReadResult(error = result.error)
    return if (section == HomeSection.SHORTS) readShorts(result.value!!) else readBrowsePage(result.value!!, continuation != null)
}
