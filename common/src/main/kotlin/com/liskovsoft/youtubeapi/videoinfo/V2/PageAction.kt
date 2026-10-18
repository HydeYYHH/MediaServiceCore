package com.liskovsoft.youtubeapi.videoinfo.V2

data class ActionEndpoint(val name: String, val payload: String, val apiUrl: String? = null)
data class PageAction(val title: String?, val endpoint: ActionEndpoint, val selected: Boolean = false)

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
    val types = setOf("buttonRenderer", "buttonViewModel", "toggleButtonRenderer", "menuServiceItemRenderer",
        "menuNavigationItemRenderer", "chipCloudChipRenderer", "listItemViewModel")
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> for ((name, value) in node.fields) {
                val item = value as? Json.Obj
                if (name in types && item != null) {
                    val command = item.obj("serviceEndpoint") ?: item.obj("navigationEndpoint")
                        ?: item.obj("onTap") ?: item.obj("defaultServiceEndpoint")
                    val endpoint = actionEndpoint(command)
                    if (endpoint != null) result.add(PageAction(item.display("text") ?: item.display("title")
                        ?: item.text("accessibilityText"), endpoint, item.bool("isSelected") == true || item.bool("isToggled") == true))
                    else visit(value)
                } else if (name !in contentRenderers) visit(value)
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(root)
    return result.distinctBy { it.title to it.endpoint }
}
