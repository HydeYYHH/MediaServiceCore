package com.liskovsoft.youtubeapi.videoinfo.V2

/** Put extracted parser-fixture slots in the actual WEB network response envelope. */
internal fun searchPrimaryFixture(body: String): String {
    val root = jsonObject(body)!!
    val slots = root.fields["contents"] as Json.Arr
    val section = Json.Obj(mapOf("sectionListRenderer" to Json.Obj(mapOf("contents" to slots))))
    val layout = Json.Obj(mapOf("primaryContents" to section))
    val contents = Json.Obj(mapOf("twoColumnSearchResultsRenderer" to layout))
    return Json.Obj(root.fields + ("contents" to contents)).encode()
}

/** An extracted filter belongs to the observed owned filter popup, rather than the full WEB root. */
internal fun searchPrimaryWithFilterFixture(body: String): String {
    return searchPrimaryFixture(searchOwnedFilterHeaderFixture(body))
}

internal fun searchOwnedFilterHeaderFixture(body: String): String {
    val root = jsonObject(body)!!
    val group = root.obj("searchFilterGroupRenderer") ?: return body
    val wrapper = Json.Obj(mapOf("searchFilterGroupRenderer" to group))
    val menu = Json.Obj(mapOf("items" to Json.Arr(listOf(wrapper))))
    val popup = Json.Obj(mapOf("popup" to Json.Obj(mapOf("menuRenderer" to menu))))
    val command = Json.Obj(mapOf("openPopupAction" to popup))
    val button = Json.Obj(mapOf("buttonRenderer" to Json.Obj(mapOf("command" to command))))
    val header = Json.Obj(mapOf("searchHeaderRenderer" to Json.Obj(mapOf("searchFilterButton" to button))))
    return Json.Obj((root.fields - "searchFilterGroupRenderer") + ("header" to header)).encode()
}
