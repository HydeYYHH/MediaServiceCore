package com.liskovsoft.youtubeapi.videoinfo.V2

/** A guide update can acknowledge only the playlist save operation that requested it. */
internal data class ExpectedPlaylistSave(val playlistId: String, val saved: Boolean)

internal enum class PlaylistSaveAcknowledgement(val metadata: String) {
    NONE("missing"), SAVED("guide_add"), REMOVED("guide_remove"), REJECTED("conflicting_or_unknown");

    val confirmed: Boolean get() = this == SAVED || this == REMOVED
}

/** Only direct response actions own guide updates; presentation trees are never searched for them. */
internal fun playlistSaveAcknowledgement(root: Json.Obj, expected: ExpectedPlaylistSave,
    viewer: ViewerEntityState?): PlaylistSaveAcknowledgement {
    val desired = if (expected.saved) "LIKE" else "INDIFFERENT"
    if (viewer != null && (viewer.kind != ViewerEntityKind.RATING || viewer.mutation != "replace" || viewer.state != desired))
        return PlaylistSaveAcknowledgement.REJECTED
    if (!validConfirmationControls(root)) return PlaylistSaveAcknowledgement.REJECTED

    val rawActions = root.fields["actions"] ?: return PlaylistSaveAcknowledgement.NONE
    val actions = rawActions as? Json.Arr ?: return PlaylistSaveAcknowledgement.REJECTED
    if (actions.items.size > 32) return PlaylistSaveAcknowledgement.REJECTED
    val guideKinds = setOf("addToGuideSectionAction", "removeFromGuideSectionAction")
    val candidates = actions.items.filterIsInstance<Json.Obj>().filter { action -> action.fields.keys.any { it in guideKinds } }
    if (candidates.isEmpty()) return PlaylistSaveAcknowledgement.NONE
    if (candidates.size != 1 || actions.items.any { value ->
        val action = value as? Json.Obj
        action == null || !validGuideActionWrapper(action)
    }) return PlaylistSaveAcknowledgement.REJECTED

    val command = candidates.single()
    val kind = command.fields.keys.singleOrNull { it !in guideMetadata } ?: return PlaylistSaveAcknowledgement.REJECTED
    val target = expected.playlistId
    if (!videoIdAccepted(target)) return PlaylistSaveAcknowledgement.REJECTED
    return when {
        expected.saved && kind == "addToGuideSectionAction" -> {
            val action = command.obj(kind) ?: return PlaylistSaveAcknowledgement.REJECTED
            if ("guideEntryId" in action.fields || action.fields.keys.any { it in guideKinds })
                return PlaylistSaveAcknowledgement.REJECTED
            val items = action.fields["items"] as? Json.Arr ?: return PlaylistSaveAcknowledgement.REJECTED
            val item = items.items.singleOrNull() as? Json.Obj ?: return PlaylistSaveAcknowledgement.REJECTED
            if (item.fields.keys != setOf("guideEntryRenderer")) return PlaylistSaveAcknowledgement.REJECTED
            val entry = item.obj("guideEntryRenderer") ?: return PlaylistSaveAcknowledgement.REJECTED
            val endpoint = entry.obj("navigationEndpoint") ?: return PlaylistSaveAcknowledgement.REJECTED
            if (!validGuideMetadata(endpoint) || endpoint.fields.keys - guideMetadata != setOf("browseEndpoint"))
                return PlaylistSaveAcknowledgement.REJECTED
            val browse = endpoint.obj("browseEndpoint") ?: return PlaylistSaveAcknowledgement.REJECTED
            if (browse.fields.keys == setOf("browseId") && browse.text("browseId") == "VL$target")
                PlaylistSaveAcknowledgement.SAVED else PlaylistSaveAcknowledgement.REJECTED
        }
        !expected.saved && kind == "removeFromGuideSectionAction" -> {
            val action = command.obj(kind) ?: return PlaylistSaveAcknowledgement.REJECTED
            if ("items" in action.fields || action.fields.keys.any { it in guideKinds })
                return PlaylistSaveAcknowledgement.REJECTED
            if (action.text("guideEntryId") == target) PlaylistSaveAcknowledgement.REMOVED
            else PlaylistSaveAcknowledgement.REJECTED
        }
        else -> PlaylistSaveAcknowledgement.REJECTED
    }
}

private val guideMetadata = setOf("trackingParams", "clickTrackingParams", "commandMetadata")

private fun validGuideMetadata(node: Json.Obj): Boolean =
    listOf("trackingParams", "clickTrackingParams").all { it !in node.fields || node.fields[it] is Json.Str } &&
        ("commandMetadata" !in node.fields || node.fields["commandMetadata"] is Json.Obj)

private fun validGuideActionWrapper(action: Json.Obj): Boolean {
    if (!validGuideMetadata(action)) return false
    val kind = (action.fields.keys - guideMetadata).singleOrNull() ?: return false
    return kind in setOf("openPopupAction", "addToGuideSectionAction", "removeFromGuideSectionAction") && action.fields[kind] is Json.Obj
}

/** A malformed control must not disappear through typed accessors and make a guide update look final. */
private fun validConfirmationControls(root: Json.Obj): Boolean {
    fun controls(node: Json.Obj) = ("status" !in node.fields || node.fields["status"] is Json.Str) &&
        ("success" !in node.fields || node.fields["success"] is Json.Bool)
    if (!controls(root)) return false
    if ("actionResults" in root.fields) {
        val results = root.fields["actionResults"] as? Json.Arr ?: return false
        if (results.items.size > 32 || results.items.any { it !is Json.Obj || !controls(it) }) return false
    }
    if ("actionResult" in root.fields) {
        val result = root.fields["actionResult"] as? Json.Obj ?: return false
        if (!controls(result)) return false
    }
    return true
}
