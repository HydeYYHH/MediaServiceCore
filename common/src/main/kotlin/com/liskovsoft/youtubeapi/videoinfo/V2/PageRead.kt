package com.liskovsoft.youtubeapi.videoinfo.V2

/** Large page reads need the JSON data, without response formatting whitespace. */
internal fun PlayerQuery.withCompactResponse(): PlayerQuery {
    if (!url.startsWith(YOUTUBE_API)) return this
    val base = url.substringBefore('#')
    val parameters = base.substringAfter('?', "").split('&')
    if (parameters.any { parameter ->
        val name = parameter.substringBefore('=')
        name == "prettyPrint" || runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrNull() == "prettyPrint"
    }) return this
    val separator = when {
        '?' !in base -> "?"
        base.endsWith('?') || base.endsWith('&') -> ""
        else -> "&"
    }
    return copy(url = base + separator + "prettyPrint=false" + url.removePrefix(base))
}

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

/** Bookkeeping-only envelope: the server answered a continuation with no content — the list ended. */
private val bookkeepingFields = setOf("responseContext", "trackingParams", "frameworkUpdates")

internal fun readBrowsePage(root: Json.Obj): ReadResult<BrowsePage> {
    val page = browsePage(root)
    // A recognized continuation envelope is answered in full even when its contents hold
    // no media (e.g. button shelves): the list ended, the parse did not break.
    val continued = root.obj("continuationContents") != null
    val known = root.fields.isEmpty() || root.fields.keys.all { it in bookkeepingFields }
        || continued || page.items.isNotEmpty() || page.groups.isNotEmpty() || page.tabs.isNotEmpty() || page.messages.isNotEmpty()
        || page.nextKey != null || emptyContent(root)
    return if (known) ReadResult(page) else ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_browse_page"))
}

internal fun readSearchPage(root: Json.Obj): ReadResult<SearchPage> {
    val page = searchPage(root)
    val known = root.fields.isEmpty() || page.items.isNotEmpty() || page.messages.isNotEmpty()
        || page.nextKey != null || emptyContent(root)
    return if (known) ReadResult(page) else ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_search_page"))
}
