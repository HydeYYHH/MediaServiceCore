package com.liskovsoft.youtubeapi.videoinfo.V2

private val pageGroups = setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")
private val pageExcluded = setOf("header", "menu", "sortFilterSubMenuRenderer", "subMenuItems", "chips",
    "secondaryContents", "engagementPanels", "replies", "commentRepliesRenderer", "liveChatRenderer",
    "serviceEndpoint", "navigationEndpoint", "onTap", "onSelectCommand")

internal fun pageKey(root: Json, forwardOnly: Boolean = false): String? {
    val pending = java.util.ArrayDeque<Json>()
    val groupKeys = ArrayList<String>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeFirst()) {
            is Json.Obj -> {
                continuationKey(node, forwardOnly)?.let { return it }
                for ((name, value) in node.fields) {
                    if (name in pageExcluded) continue
                    // A card may embed a preview list; its cursor never appends to the feed.
                    if (forwardOnly && name in contentRenderers) continue
                    if (name in pageGroups) pageKey(value, forwardOnly)?.let { groupKeys.add(it) }
                    else pending.add(value)
                }
            }
            is Json.Arr -> node.items.forEach { pending.add(it) }
            else -> Unit
        }
    }
    return groupKeys.distinct().singleOrNull()
}

private fun continuationKey(node: Json.Obj, forwardOnly: Boolean): String? {
    node.path("continuationEndpoint", "continuationCommand")?.text("token")?.takeIf(String::isNotBlank)?.let { return it }
    for (item in node.arr("continuations")) {
        val value = item as? Json.Obj ?: continue
        for (name in listOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData")) {
            // Reload/poll tokens refresh existing content rather than advance a browse list.
            // Comments and chat still need those tokens through the default mode.
            if (forwardOnly && name != "nextContinuationData") continue
            value.obj(name)?.text("continuation")?.takeIf(String::isNotBlank)?.let { return it }
        }
    }
    return null
}
