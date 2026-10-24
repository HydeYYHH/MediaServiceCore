package com.liskovsoft.youtubeapi.videoinfo.V2

data class SearchFilter(val title: String?, val group: String?, val params: String, val selected: Boolean)
fun searchFilters(body: String): List<SearchFilter> = searchFilters(jsonObject(body))
internal fun searchFilters(root: Json.Obj?): List<SearchFilter> {
    root ?: return emptyList()
    return root.objects("searchFilterGroupRenderer").flatMap { group ->
        group.objects("searchFilterRenderer").mapNotNull { filter ->
            val params = filter.path("navigationEndpoint", "searchEndpoint")?.text("params") ?: return@mapNotNull null
            SearchFilter(filter.display("label"), group.display("title"), params, filter.text("status") == "FILTER_STATUS_SELECTED")
        }
    }
}
fun searchSuggestions(body: String): List<String>? {
    val root = jsonValue(body) as? Json.Arr ?: return null
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
