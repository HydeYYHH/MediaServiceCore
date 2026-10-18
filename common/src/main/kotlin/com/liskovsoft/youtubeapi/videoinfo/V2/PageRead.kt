package com.liskovsoft.youtubeapi.videoinfo.V2

internal fun pageMessages(root: Json): List<String> =
    listOf("feedNudgeRenderer", "messageRenderer", "backgroundPromoRenderer", "alertRenderer").flatMap { name ->
        root.objects(name).mapNotNull { it.display("title") ?: it.display("text") ?: it.display("message") }
    }.distinct()

private fun emptyContent(node: Json): Boolean = when (node) {
    is Json.Obj -> node.fields.any { (name, value) ->
        (name in setOf("contents", "items", "continuationItems") && value is Json.Arr && value.items.isEmpty()) || emptyContent(value)
    }
    is Json.Arr -> node.items.any(::emptyContent)
    else -> false
}

internal fun readBrowsePage(root: Json.Obj): ReadResult<BrowsePage> {
    val page = browsePage(root.encode())
    val known = root.fields.isEmpty() || page.items.isNotEmpty() || page.tabs.isNotEmpty() || page.messages.isNotEmpty()
        || page.nextKey != null || emptyContent(root)
    return if (known) ReadResult(page) else ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_browse_page"))
}

internal fun readSearchPage(root: Json.Obj): ReadResult<SearchPage> {
    val page = searchPage(root.encode())
    val known = root.fields.isEmpty() || page.items.isNotEmpty() || page.messages.isNotEmpty()
        || page.nextKey != null || emptyContent(root)
    return if (known) ReadResult(page) else ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_search_page"))
}
