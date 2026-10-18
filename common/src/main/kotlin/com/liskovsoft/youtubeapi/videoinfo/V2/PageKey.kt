package com.liskovsoft.youtubeapi.videoinfo.V2

private val pageGroups = setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")
private val pageExcluded = setOf("header", "menu", "sortFilterSubMenuRenderer", "subMenuItems", "chips",
    "secondaryContents", "engagementPanels", "replies", "commentRepliesRenderer", "liveChatRenderer",
    "serviceEndpoint", "navigationEndpoint", "onTap", "onSelectCommand")

internal fun pageKey(root: Json): String? {
    val pending = java.util.ArrayDeque<Json>()
    val groupKeys = ArrayList<String>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeFirst()) {
            is Json.Obj -> {
                continuationKey(node)?.let { return it }
                for ((name, value) in node.fields) {
                    if (name in pageExcluded) continue
                    if (name in pageGroups) pageKey(value)?.let { groupKeys.add(it) }
                    else pending.add(value)
                }
            }
            is Json.Arr -> node.items.forEach { pending.add(it) }
            else -> Unit
        }
    }
    return groupKeys.distinct().singleOrNull()
}

private fun continuationKey(node: Json.Obj): String? {
    node.path("continuationEndpoint", "continuationCommand")?.text("token")?.let { return it }
    for (item in node.arr("continuations")) {
        val value = item as? Json.Obj ?: continue
        for (name in listOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData")) {
            value.obj(name)?.text("continuation")?.let { return it }
        }
    }
    return null
}
