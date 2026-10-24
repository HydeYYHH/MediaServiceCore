package com.liskovsoft.youtubeapi.videoinfo.V2

enum class HomeSection { HOME, TRENDING, SHORTS, LIVE, MUSIC, GAMING, NEWS, SPORTS, MOVIES, KIDS }
fun homeQuery(section: HomeSection = HomeSection.HOME, context: RequestContext = RequestContext(), continuation: String? = null): PlayerQuery {
    if (section == HomeSection.SHORTS) return youtubeQuery(YOUTUBE_API + "reel/reel_watch_sequence",
        if (continuation != null) payload("sequenceParams" to continuation) else
            "\"disablePlayerResponse\":true,\"inputType\":\"REEL_WATCH_INPUT_TYPE_SEEDLESS\",\"params\":\"CA8%3D\"", context)
    val id = when (section) {
        HomeSection.HOME -> "default"
        HomeSection.TRENDING -> "FEtrending"
        HomeSection.KIDS -> "FEkids_home"
        else -> "FEtopics_" + section.name.toLowerCase(java.util.Locale.ROOT)
    }
    val fields = if (continuation != null) payload("continuation" to continuation)
        else payload("browseId" to id, "params" to if (section == HomeSection.TRENDING) "6gQJRkVleHBsb3Jl" else null)
    // Topic and trending ids are dead for the WEB client (HTTP 400); the TV client serves them.
    // FEkids_home fails on both (500/400) and stays on the legacy path.
    val query = if (section == HomeSection.KIDS)
        youtubeQuery(YOUTUBE_API + "browse", fields, context)
    else contextualQuery(tvQuery(YOUTUBE_API + "browse", fields, context.visitor), context)
    return query
}
fun loadHome(section: HomeSection = HomeSection.HOME, context: RequestContext = RequestContext(), continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<BrowsePage> {
    val result = requestObject(homeQuery(section, context, continuation), http)
    if (section == HomeSection.SHORTS && continuation == null && result.error?.status == 400)
        return loadShortsDocument(context, http)
    if (!result.ok) return ReadResult(error = result.error)
    return if (section == HomeSection.SHORTS) readShorts(result.value!!) else readBrowsePage(result.value!!)
}
