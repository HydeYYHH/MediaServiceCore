package com.liskovsoft.youtubeapi.videoinfo.V2

private val pageGroups = setOf("shelfRenderer", "richShelfRenderer")
private val pageExcluded = setOf("header", "menu", "sortFilterSubMenuRenderer", "subMenuItems", "chips",
    "secondaryContents", "engagementPanels", "replies", "commentRepliesRenderer", "liveChatRenderer",
    "serviceEndpoint", "navigationEndpoint", "onTap", "onSelectCommand")

internal fun pageKey(root: Json, forwardOnly: Boolean = false): String? = pageKeyResult(root, forwardOnly).key

private data class PageKeyResult(val key: String?, val ambiguous: Boolean = false)

private fun pageKeyResult(root: Json, forwardOnly: Boolean): PageKeyResult {
    val pending = java.util.ArrayDeque<Json>()
    val outerKeys = ArrayList<String>()
    val sectionKeys = ArrayList<String>()
    var sectionAmbiguous = false
    pending.add(root)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeFirst()) {
            is Json.Obj -> {
                continuationKey(node, forwardOnly)?.let(outerKeys::add)
                for ((name, value) in node.fields) {
                    if (name in pageExcluded) continue
                    // A card may embed a preview list; its cursor never appends to the feed.
                    if (forwardOnly && name in contentRenderers) continue
                    // A shelf owns its cursor even when it is the page's only shelf.
                    if (name in pageGroups) continue
                    // Primary result/comment item-sections remain list owners, not shelves.
                    if (name == "itemSectionRenderer") {
                        val section = pageKeyResult(value, forwardOnly)
                        sectionAmbiguous = sectionAmbiguous || section.ambiguous
                        section.key?.let(sectionKeys::add)
                    } else pending.add(value)
                }
            }
            is Json.Arr -> node.items.forEach { pending.add(it) }
            else -> Unit
        }
    }
    return when {
        outerKeys.size == 1 -> PageKeyResult(outerKeys.single())
        outerKeys.size > 1 || sectionAmbiguous || sectionKeys.size > 1 -> PageKeyResult(null, ambiguous = true)
        else -> PageKeyResult(sectionKeys.singleOrNull())
    }
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
