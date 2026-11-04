package com.liskovsoft.youtubeapi.videoinfo.V2

data class MenuEndpoint(val panelId: String, val params: String) {
    override fun toString() = "MenuEndpoint"
}

fun loadMenu(endpoint: MenuEndpoint, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient()): ReadResult<List<PageAction>> {
    if (endpoint.panelId.isBlank() || endpoint.panelId.length > 256 ||
        endpoint.params.isBlank() || endpoint.params.length > 32768)
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_menu_endpoint"))
    val query = contextualQuery(tvQuery(YOUTUBE_API + "get_panel",
        payload("panelId" to endpoint.panelId, "params" to endpoint.params), context.visitor), context)
    val response = requestObject(query, http)
    val root = response.value ?: return ReadResult(error = response.error)
    val content = root.path("content", "engagementPanelSectionListRenderer", "content", "listViewModel")
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_menu_panel"))
    if (content.fields["listItems"] !is Json.Arr)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_menu_items"))
    return ReadResult(pageActions(content))
}
