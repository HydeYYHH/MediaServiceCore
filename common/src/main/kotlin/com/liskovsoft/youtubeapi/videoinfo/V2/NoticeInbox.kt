package com.liskovsoft.youtubeapi.videoinfo.V2

private const val MENU_URL =
    "https://www.youtube.com/youtubei/v1/notification/get_notification_menu?prettyPrint=false"
private const val HIDE_URL =
    "https://www.youtube.com/youtubei/v1/notification/record_interactions?prettyPrint=false"
private const val STATE_URL =
    "https://www.youtube.com/youtubei/v1/notification/modify_channel_preference?prettyPrint=false"

data class Notice(
    val videoId: String?,
    val message: String?,
    val author: String?,
    val title: String?,
    val published: String?,
    val thumbnail: String?,
    val hideKey: String?
)

data class NoticeState(
    val title: String?,
    val stateId: Int?,
    val params: String,
    val selected: Boolean
)

fun noticeQuery(visitor: String?): PlayerQuery {
    return webQuery(MENU_URL, """"notificationsMenuRequestType":"NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX"""", visitor)
}

fun notices(body: String): List<Notice> {
    val root = jsonObject(body) ?: return emptyList()
    val parsed = ArrayList<Notice>()
    for (renderer in root.objects("notificationRenderer")) {
        val message = displayText(renderer.obj("shortMessage"))
        val parts = splitMessage(message)
        parsed.add(Notice(
            renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.text("videoId")?.takeIf { it.isNotEmpty() },
            message,
            parts.first,
            parts.second,
            displayText(renderer.obj("sentTimeText")),
            firstThumbnail(renderer.obj("videoThumbnail")),
            hideKey(renderer.obj("contextualMenu"))
        ))
    }
    return parsed
}

fun loadNotices(visitor: String?, post: (PlayerQuery) -> Pair<Int, String>): List<Notice> {
    val http = try {
        post(noticeQuery(visitor))
    } catch (failure: Exception) {
        return emptyList()
    }
    if (http.first != 200) return emptyList()
    return notices(http.second)
}

fun hideNotice(token: String?, visitor: String?, post: (PlayerQuery) -> Pair<Int, String>): Boolean {
    if (token.isNullOrEmpty()) return false
    val query = webQuery(
        HIDE_URL,
        """"serializedRecordNotificationInteractionsRequest":"${escapeJson(token)}"""",
        visitor
    )
    return posted(query, post)
}

fun noticeStates(body: String): List<NoticeState> {
    val root = jsonObject(body) ?: return emptyList()
    val button = findButton(root) ?: return emptyList()
    val current = wholeNumber(button, "currentStateId")
    val states = button.fields["states"] as? Json.Arr ?: return emptyList()
    val parsed = ArrayList<NoticeState>()
    for (item in states.items) {
        val state = item as? Json.Obj ?: continue
        val params = state.obj("inlineMenuButton")
            ?.obj("buttonRenderer")
            ?.obj("serviceEndpoint")
            ?.obj("modifyChannelNotificationPreferenceEndpoint")
            ?.text("params")
            ?.takeIf { it.isNotEmpty() } ?: continue
        val stateId = wholeNumber(state, "stateId")
        val title = displayText(state.obj("inlineMenuButton")?.obj("buttonRenderer")?.obj("text"))
        parsed.add(NoticeState(title, stateId, params, stateId != null && stateId == current))
    }
    return parsed
}

fun setNoticeState(params: String?, visitor: String?, post: (PlayerQuery) -> Pair<Int, String>): Boolean {
    if (params.isNullOrBlank()) return false
    return posted(webQuery(STATE_URL, """"params":"${escapeJson(params)}"""", visitor), post)
}

private fun posted(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): Boolean {
    if (query.url.isEmpty()) return false
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return false
    }
    return http.first == 200
}

private fun splitMessage(message: String?): Pair<String?, String?> {
    if (message == null) return Pair(null, null)
    val colon = message.indexOf(':')
    if (colon < 0) return Pair(message, message)
    return Pair(message.substring(0, colon), message.substring(colon + 1).trim())
}

private fun hideKey(menu: Json.Obj?): String? {
    if (menu == null) return null
    return firstHideKey(menu)
}

private fun firstHideKey(node: Json): String? = when (node) {
    is Json.Obj -> {
        val token = node.obj("recordNotificationInteractionsEndpoint")?.text("serializedInteractionsRequest")
        if (!token.isNullOrEmpty()) token
        else node.fields.values.asSequence().mapNotNull { firstHideKey(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { firstHideKey(it) }.firstOrNull()
    else -> null
}

private fun findButton(node: Json): Json.Obj? = when (node) {
    is Json.Obj -> node.obj("subscriptionNotificationToggleButtonRenderer")
        ?: node.fields.values.asSequence().mapNotNull { findButton(it) }.firstOrNull()
    is Json.Arr -> node.items.asSequence().mapNotNull { findButton(it) }.firstOrNull()
    else -> null
}

private fun displayText(node: Json.Obj?): String? {
    if (node == null) return null
    val simple = node.text("simpleText")
    if (simple != null && simple.isNotEmpty()) return simple
    val runs = node.fields["runs"] as? Json.Arr ?: return null
    val out = StringBuilder()
    for (run in runs.items) {
        val text = (run as? Json.Obj)?.text("text")
        if (text != null) out.append(text)
    }
    return if (out.isEmpty()) null else out.toString()
}

private fun firstThumbnail(node: Json.Obj?): String? {
    val thumbs = node?.fields?.get("thumbnails") as? Json.Arr ?: return null
    for (item in thumbs.items) {
        val url = (item as? Json.Obj)?.text("url")
        if (url != null && url.isNotEmpty()) return url
    }
    return null
}

private fun wholeNumber(node: Json.Obj, name: String): Int? {
    val text = (node.fields[name] as? Json.Num)?.text ?: return null
    val digits = if (text.startsWith("-")) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return text.toIntOrNull()
}

private fun escapeJson(value: String): String {
    val out = StringBuilder()
    val chars: CharArray = value.toCharArray()
    for (c in chars) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(c)
        }
    }
    return out.toString()
}
