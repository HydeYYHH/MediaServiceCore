package com.liskovsoft.youtubeapi.videoinfo.V2

enum class HomeSection { HOME, TRENDING, SHORTS, LIVE, MUSIC, GAMING, NEWS, SPORTS, MOVIES, KIDS }
fun homeQuery(section: HomeSection = HomeSection.HOME, context: RequestContext = RequestContext(), continuation: String? = null): PlayerQuery {
    if (section == HomeSection.SHORTS) return youtubeQuery(YOUTUBE_API + "reel/reel_watch_sequence",
        if (continuation != null) payload("sequenceParams" to continuation) else
            "\"disablePlayerResponse\":true,\"inputType\":\"REEL_WATCH_INPUT_TYPE_SEEDLESS\",\"params\":\"CA8%3D\"", context)
    val id = when (section) {
        HomeSection.HOME -> if (context.accessToken == null) "FEwhat_to_watch" else "default"
        HomeSection.TRENDING -> "FEtrending"
        HomeSection.KIDS -> "FEkids_home"
        else -> "FEtopics_" + section.name.toLowerCase(java.util.Locale.ROOT)
    }
    return browseQuery(id, context, if (section == HomeSection.TRENDING) "6gQJRkVleHBsb3Jl" else null, continuation)
}
fun loadHome(section: HomeSection = HomeSection.HOME, context: RequestContext = RequestContext(), continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<BrowsePage> {
    val result = requestObject(homeQuery(section, context, continuation), http)
    if (!result.ok) return ReadResult(error = result.error)
    return if (section == HomeSection.SHORTS) readShorts(result.value!!) else ReadResult(browsePage(result.value!!.encode()))
}
