package com.liskovsoft.youtubeapi.videoinfo.V2

// WEB_KIDS uses a dedicated client profile and anonymous requests.
internal fun kidsHomeQuery(context: RequestContext, continuation: String?): PlayerQuery {
    val fields = if (continuation == null) payload("browseId" to "FEkids_home") else payload("continuation" to continuation)
    val template = webQuery(YOUTUBE_API + "browse?prettyPrint=false&alt=json", fields, context.visitor)
    val root = jsonObject(template.body)!!
    val originalContext = root.obj("context")!!
    val client = originalContext.obj("client")!!
    val categories = listOf("approved_for_you", "black_joy", "camp", "collections", "earth", "explore",
        "favorites", "gaming", "halloween", "hero", "learning", "move", "music", "reading",
        "shared_by_parents", "shows", "soccer", "sports", "spotlight", "winter")
    val kidsAppInfo = Json.Obj(mapOf(
        "categorySettings" to Json.Obj(mapOf("enabledCategories" to Json.Arr(categories.map { Json.Str(it) }))),
        "contentSettings" to Json.Obj(mapOf("corpusPreference" to Json.Str("KIDS_CORPUS_PREFERENCE_YOUNGER"),
            "kidsNoSearchMode" to Json.Str("YT_KIDS_NO_SEARCH_MODE_OFF")))))
    val profile = Json.Obj(originalContext.fields + mapOf(
        "client" to Json.Obj(client.fields + mapOf("clientName" to Json.Str("WEB_KIDS"),
            "clientVersion" to Json.Str(KIDS_VERSION), "hl" to Json.Str(context.language), "gl" to Json.Str(context.region),
            "kidsAppInfo" to kidsAppInfo)),
        "user" to Json.Obj(mapOf("enableSafetyMode" to Json.Bool(false), "lockedSafetyMode" to Json.Bool(false))),
        "request" to Json.Obj(mapOf("useSsl" to Json.Bool(true), "internalExperimentFlags" to Json.Arr(emptyList())))))
    // Deliberately avoid contextualQuery/sessionHeaders: WEB_KIDS never receives login cookies or delegation.
    return template.copy(headers = template.headers + mapOf("X-Youtube-Client-Name" to "76",
        "X-Youtube-Client-Version" to KIDS_VERSION, "Accept" to "*/*", "Origin" to "https://www.youtube.com"),
        body = Json.Obj(root.fields + ("context" to profile)).encode())
}

internal fun kidsHomeContextError(context: RequestContext): RequestError? =
    if (context.signedIn || context.onBehalfOfUser != null) RequestError(RequestFailure.UNSUPPORTED,
        "kids_requires_explicit_anonymous_context") else null

private const val KIDS_VERSION = "2.20260205.00.00"
