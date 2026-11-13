package com.liskovsoft.youtubeapi.videoinfo.V2

/** [params] is absent for a selected display-only option with no navigation capability. */
data class SearchFilter @JvmOverloads constructor(val title: String?, val group: String?, val params: String?, val selected: Boolean,
    /** A declared query is checked against the network read before this option can be issued. */
    val query: String? = null, val groupIndex: Int = 0) {
    val state: SearchFilterState? by lazy { params?.let(::searchFilterState) }
}
fun searchFilters(body: String): List<SearchFilter> = searchFilters(jsonObject(body))
internal fun searchFilters(root: Json.Obj?): List<SearchFilter> {
    root ?: return emptyList()
    val legacy = pureOwnedSearchCollection(root)?.legacy == true
    if (!legacy && ownedSearchActions(root)?.valid == false) return emptyList()
    return ownedSearchFilters(root, legacy)
}
fun searchSuggestions(body: String): List<String>? {
    val text = body.trim().removeSuffix(";")
    // The YouTube suggestion client returns this fixed JSONP envelope. Parse the
    // enclosed JSON as data; never evaluate callback text or accept arbitrary JS.
    val json = if (text.startsWith("window.google.ac.h(") && text.endsWith(")"))
        text.substring("window.google.ac.h(".length, text.length - 1) else text
    val root = jsonValue(json) as? Json.Arr ?: return null
    val suggestions = root.items.getOrNull(1) as? Json.Arr ?: return emptyList()
    return suggestions.items.mapNotNull { item ->
        when (item) { is Json.Str -> item.value; is Json.Arr -> (item.items.firstOrNull() as? Json.Str)?.value; else -> null }
    }.distinct()
}
fun loadSearchSuggestions(text: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<List<String>> {
    var url = "https://suggestqueries.google.com/complete/search?client=youtube&ds=yt"
    for ((key, value) in listOf("q" to text, "hl" to context.language, "gl" to context.region)) url = urlWithParameter(url, key, value)
    val reply = requestBody(HttpRequest("GET", url), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    return searchSuggestions(reply.value!!)?.let { ReadResult(it) }
        ?: ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_suggestions"))
}
