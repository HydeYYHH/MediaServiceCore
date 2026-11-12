package com.liskovsoft.youtubeapi.videoinfo.V2

data class ActionEndpoint(val name: String, val payload: String, val apiUrl: String? = null)

/** Server-side menu semantics, derived from the endpoint payload and renderer icon; never from a title. */
enum class MenuActionKind { NotInterested, DontRecommendChannel }

data class PageAction(val title: String?, val endpoint: ActionEndpoint, val selected: Boolean = false,
    val kind: MenuActionKind? = null, val kindToken: String? = null)

// History-item menus carry a feedbackEndpoint whose token removes the item via removeWatchHistory.
fun PageAction.feedbackToken(): String? {
    if (endpoint.name !in setOf("feedbackEndpoint", "commandExecutorCommand")) return null
    val root = jsonObject(endpoint.payload) ?: return null
    var found: String? = null
    fun visit(node: Json) {
        if (found != null) return
        when (node) {
            is Json.Obj -> {
                node.text("feedbackToken")?.takeIf(String::isNotBlank)?.let { found = it }
                (node.fields["feedbackTokens"] as? Json.Arr)?.items?.forEach {
                    if (found == null && it is Json.Str && it.value.isNotBlank()) found = it.value
                }
                if (found == null) node.fields.values.forEach { visit(it) }
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(root)
    return found
}

internal fun actionEndpoint(command: Json.Obj?): ActionEndpoint? {
    if (command == null) return null
    val actual = command.obj("innertubeCommand") ?: command
    val name = actual.fields.keys.firstOrNull {
        it.endsWith("Endpoint") || it.endsWith("Command") || it.endsWith("Action")
    } ?: return null
    return ActionEndpoint(name, actual.fields.getValue(name).encode(),
        actual.path("commandMetadata", "webCommandMetadata")?.text("apiUrl"))
}

internal fun pageActions(root: Json): List<PageAction> {
    val result = ArrayList<PageAction>()
    val types = setOf("buttonRenderer", "gridButtonRenderer", "buttonViewModel", "toggleButtonRenderer", "menuServiceItemRenderer",
        "menuNavigationItemRenderer", "chipCloudChipRenderer", "listItemViewModel", "buttonCardViewModel")
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> for ((name, value) in node.fields) {
                val item = value as? Json.Obj
                if (name in types && item != null) {
                    directPageAction(item)?.let(result::add)
                    visit(value)
                } else if (name !in contentRenderers) visit(value)
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(root)
    return result.distinctBy { Triple(it.title, it.endpoint, it.kind) }
}

/** One renderer's own action; unlike [pageActions], this never promotes popup descendants. */
internal fun directPageAction(item: Json.Obj): PageAction? {
    val command = item.obj("serviceEndpoint") ?: item.obj("navigationEndpoint")
        ?: item.obj("onTap") ?: item.obj("defaultServiceEndpoint") ?: item.obj("command")
        ?: item.path("rendererContext", "commandContext", "onTap", "innertubeCommand")
    val endpoint = actionEndpoint(command) ?: return null
    val classified = menuActionKind(item, command)
    return PageAction(item.display("text") ?: item.display("title") ?: item.text("accessibilityText"), endpoint,
        item.bool("isSelected") == true || item.bool("isToggled") == true, classified?.first, classified?.second)
}

private class FeedbackSite(val token: String, val uiActions: Set<String>, val hidesContainer: Boolean)

/** Feedback sites under one command, with their uiActions keys. */
private fun feedbackSites(node: Json?, into: MutableList<FeedbackSite>) {
    when (node) {
        is Json.Obj -> {
            val feedback = node.obj("feedbackEndpoint")
            if (feedback != null) {
                val token = feedback.text("feedbackToken")?.takeIf(String::isNotBlank)
                    ?: (feedback.fields["feedbackTokens"] as? Json.Arr)?.items
                        ?.filterIsInstance<Json.Str>()?.firstOrNull { it.value.isNotBlank() }?.value
                val ui = feedback.obj("uiActions")
                if (token != null) into.add(FeedbackSite(token, ui?.fields?.keys.orEmpty(),
                    ui?.bool("hideEnclosingContainer") == true))
            }
            node.fields.values.forEach { feedbackSites(it, into) }
        }
        is Json.Arr -> node.items.forEach { feedbackSites(it, into) }
        else -> Unit
    }
}

private fun notInterestedIcon(item: Json.Obj): Boolean =
    listOf("icon", "leadingIcon", "iconImage").any { key ->
        val icon = item.obj(key)
        icon?.text("iconType") == "NOT_INTERESTED" || icon?.text("name") == "NOT_INTERESTED"
    }

private fun menuActionKind(item: Json.Obj, command: Json.Obj?): Pair<MenuActionKind, String?>? {
    val actual = command?.obj("innertubeCommand") ?: command ?: return null
    if (actual.obj("openPopupAction") != null) {
        return confirmedChannelToken(actual)?.let { MenuActionKind.DontRecommendChannel to it }
    }
    val sites = ArrayList<FeedbackSite>()
    feedbackSites(actual, sites)
    sites.firstOrNull { "hideUnhideUis" in it.uiActions }?.let { return MenuActionKind.NotInterested to it.token }
    sites.firstOrNull { "hideEnclosingContainerUis" in it.uiActions }?.let {
        return MenuActionKind.DontRecommendChannel to it.token
    }
    if (sites.size == 1 && sites.single().hidesContainer) {
        return MenuActionKind.NotInterested to sites.single().token
    }
    if (notInterestedIcon(item) && sites.size == 1) return MenuActionKind.NotInterested to sites.single().token
    return null
}

private fun confirmedChannelToken(command: Json.Obj): String? {
    val choices = command.path("openPopupAction", "popup", "overlaySectionRenderer", "overlay",
        "overlayTwoPanelRenderer", "actionPanel", "overlayPanelRenderer", "content",
        "overlayPanelItemListRenderer")?.arr("items") ?: return null
    if (choices.size != 2) return null
    val endpoints = choices.mapNotNull { (it as? Json.Obj)?.path("compactLinkRenderer", "serviceEndpoint") }
    if (endpoints.size != 2 || endpoints[1].obj("signalAction") == null) return null
    val commands = endpoints[0].obj("commandExecutorCommand")?.arr("commands") ?: return null
    if (commands.size != 2 || (commands[1] as? Json.Obj)?.obj("signalAction") == null) return null
    val feedback = (commands[0] as? Json.Obj)?.obj("feedbackEndpoint") ?: return null
    if (feedback.obj("uiActions")?.bool("hideEnclosingContainer") != true) return null
    if (feedback.arr("actions").none { (it as? Json.Obj)?.obj("removeItemAction")
            ?.text("childId")?.isNotBlank() == true }) return null
    return feedback.text("feedbackToken")?.takeIf(String::isNotBlank)
}
