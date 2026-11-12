package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

private const val PREFERENCE_PATH = "/youtubei/v1/notification/modify_channel_preference"

/** Present protocol flags must have their declared boolean type; malformed does not mean omitted. */
internal fun noticeBooleanFlagsKnown(node: Json.Obj, vararg names: String): Boolean =
    names.all { it !in node.fields || node.fields[it] is Json.Bool }

/** Only the operation's top-level replacement can acknowledge a requested channel preference. */
internal fun noticePreferenceAcknowledged(root: Json.Obj, channelId: String, expected: Int): Boolean {
    if (root.text("channelId") != channelId) return false
    val replacement = root.obj("newNotificationButton") ?: return false
    if (replacement.fields.keys != setOf("subscriptionNotificationToggleButtonRenderer")) return false
    val button = replacement.obj("subscriptionNotificationToggleButtonRenderer") ?: return false
    if (!noticeBooleanFlagsKnown(button, "isDisabled") || button.bool("isDisabled") == true) return false
    fun id(node: Json.Obj, name: String) = (node.fields[name] as? Json.Num)?.text?.toIntOrNull()?.takeIf { it in setOf(0, 2, 3) }
    val current = id(button, "currentStateId") ?: return false
    if (current != expected) return false
    val raw = button.fields["states"] as? Json.Arr ?: return false
    if (raw.items.isEmpty()) return false
    val seen = HashSet<Int>()
    var matching = false
    for (value in raw.items) {
        val state = value as? Json.Obj ?: return false
        val stateId = id(state, "stateId") ?: return false
        if (!seen.add(stateId)) return false
        val renderer = state.path("state", "buttonRenderer") ?: return false
        if (!noticeBooleanFlagsKnown(state, "isSelected", "isDisabled") ||
            !noticeBooleanFlagsKnown(renderer, "isSelected", "isDisabled")) return false
        val selected = stateId == current
        if (listOf(state.bool("isSelected"), renderer.bool("isSelected")).any { it != null && it != selected }) return false
        if (selected) {
            if (state.bool("isDisabled") == true || renderer.bool("isDisabled") != false) return false
            matching = true
        }
    }
    return matching
}

/** Null means no modern subscription sheet. An existing sheet with no usable commands stays empty. */
internal fun modernNoticeStates(root: Json.Obj): List<NoticeState>? {
    val buttons = root.objects("subscribeButtonViewModel")
    if (buttons.isEmpty()) return null
    for (button in buttons) {
        val sheet = button.path("onShowSubscriptionOptions", "innertubeCommand", "showSheetCommand",
            "panelLoadingStrategy", "inlineContent", "sheetViewModel", "content", "listViewModel") ?: continue
        return sheet.arr("listItems").mapNotNull { value ->
            val item = (value as? Json.Obj)?.obj("listItemViewModel") ?: return@mapNotNull null
            val params = noticePreferenceParams(item.path("rendererContext", "commandContext", "onTap"))
                ?: return@mapNotNull null
            val images = item.obj("leadingImage")?.arr("sources").orEmpty().mapNotNull {
                (it as? Json.Obj)?.obj("clientResource")?.text("imageName")
            }.distinct()
            // Compatibility IDs used by the legacy renderer: all=2, personalized=3, off=0.
            // Unknown or contradictory images retain an unknown ID; labels and opaque params never select the kind.
            val stateId = images.singleOrNull()?.let { image -> when (image) {
                "NOTIFICATIONS_ACTIVE" -> 2
                "NOTIFICATIONS_NONE" -> 3
                "NOTIFICATIONS_OFF" -> 0
                else -> null
            } }
            NoticeState(item.obj("title")?.text("content"), stateId, params,
                item.bool("isSelected") == true, !noticeBooleanFlagsKnown(item, "isSelected", "isDisabled") ||
                    !noticeBooleanFlagsKnown(button, "disableNotificationBell", "disableSubscribeButton", "isSignedOut") ||
                    item.bool("isDisabled") == true ||
                    button.bool("disableNotificationBell") == true || button.bool("disableSubscribeButton") == true ||
                    button.bool("isSignedOut") == true)
        }
    }
    return emptyList()
}

/** A real modify command is required, and every declared wrapper destination must agree. */
internal fun noticePreferenceParams(node: Json.Obj?): String? {
    node ?: return null
    val command = node.obj("innertubeCommand") ?: node
    for (wrapper in listOf(node, command)) {
        val metadata = wrapper.path("commandMetadata", "webCommandMetadata") ?: continue
        if (!noticeBooleanFlagsKnown(metadata, "sendPost") || metadata.bool("sendPost") == false) return null
        val declared = metadata.text("apiUrl") ?: continue
        val valid = runCatching {
            val uri = URI(declared)
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port in listOf(-1, 443) &&
                (if (uri.isAbsolute) uri.scheme == "https" && uri.host == "www.youtube.com" else uri.rawAuthority == null) &&
                uri.path == PREFERENCE_PATH
        }.getOrDefault(false)
        if (!valid) return null
    }
    return command.obj("modifyChannelNotificationPreferenceEndpoint")?.text("params")?.takeIf(String::isNotBlank)
}
