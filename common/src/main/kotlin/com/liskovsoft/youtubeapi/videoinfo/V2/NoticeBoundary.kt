package com.liskovsoft.youtubeapi.videoinfo.V2

/** Network responses must own their menu; unrelated nested renderers cannot make an empty inbox. */
internal fun notificationResponsePage(root: Json.Obj): NoticePage? = NotificationBoundary().read(root)

private class NotificationBoundary {
    private val entries = ArrayList<Json>()
    private var next: String? = null
    private var visited = 0
    private val metadata = setOf("trackingParams", "clickTrackingParams", "commandMetadata")
    private val menuKinds = setOf("multiPageMenuRenderer", "multiPageMenuNotificationSectionRenderer", "notificationSectionRenderer", "messageRenderer")
    private val itemKinds = menuKinds + setOf("notificationRenderer", "continuationItemRenderer")
    private val commandKinds = setOf("openPopupAction", "appendContinuationItemsAction", "reloadContinuationItemsCommand")
    private val continuationKinds = setOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData")

    fun read(root: Json.Obj): NoticePage? {
        val direct = root.fields.keys.filter { it in menuKinds || it == "sections" }
        val commands = root.fields.keys.filter { it in setOf("actions", "onResponseReceivedEndpoints", "onResponseReceivedActions", "onResponseReceivedCommands") }
        if (direct.size + commands.size != 1) return null
        val valid = if (direct.isNotEmpty()) {
            val kind = direct.single()
            if (kind == "sections") list(root.fields[kind], 0) else menu(kind, root.fields[kind], 0)
        } else {
            val actions = root.fields[commands.single()] as? Json.Arr ?: return null
            actions.items.isNotEmpty() && actions.items.size <= 2_000 && actions.items.all { command(it, 0) }
        }
        if (!valid) return null
        val scope = Json.Obj(mapOf("sections" to Json.Arr(entries)))
        return NoticePage(notices(scope.encode()), next)
    }

    private fun list(value: Json?, depth: Int): Boolean {
        val array = value as? Json.Arr ?: return false
        if (depth > 24 || array.items.size > 2_000) return false
        return array.items.all { item(it, depth + 1) }
    }

    private fun item(value: Json, depth: Int): Boolean {
        if (depth > 24 || ++visited > 4_000) return false
        val node = value as? Json.Obj ?: return false
        val kinds = node.fields.keys.filter { it in itemKinds }
        if (kinds.size != 1 || node.fields.keys.any { it !in itemKinds && it !in metadata }) return false
        val kind = kinds.single()
        val body = node.fields[kind] as? Json.Obj ?: return false
        return when (kind) {
            "notificationRenderer" -> {
                if (body.display("shortMessage").isNullOrBlank() && body.text("notificationId").isNullOrBlank()) false
                else { entries.add(Json.Obj(mapOf(kind to body))); true }
            }
            "continuationItemRenderer" -> {
                val token = body.path("continuationEndpoint", "continuationCommand")?.text("token")?.takeIf(String::isNotBlank)
                    ?: return false
                acceptCursor(token)
            }
            else -> menu(kind, body, depth + 1)
        }
    }

    private fun menu(kind: String, value: Json?, depth: Int): Boolean {
        val body = value as? Json.Obj ?: return false
        if (depth > 24) return false
        return when (kind) {
            "multiPageMenuRenderer" -> {
                val sections = body.fields["sections"] as? Json.Arr ?: return false
                val only = sections.items.singleOrNull() as? Json.Obj
                if (only?.fields?.containsKey("backgroundPromoRenderer") == true) emptyNotificationMenu(body, only)
                else list(sections, depth + 1)
            }
            "multiPageMenuNotificationSectionRenderer", "notificationSectionRenderer" ->
                list(body.fields["items"], depth + 1) && continuations(body.fields["continuations"])
            "messageRenderer" -> !body.display("text").isNullOrBlank()
            else -> false
        }
    }

    private fun acceptCursor(token: String): Boolean {
        if (next != null && next != token) return false
        next = token
        return true
    }

    private fun continuations(value: Json?): Boolean {
        if (value == null) return true
        val array = value as? Json.Arr ?: return false
        if (array.items.size > 2_000) return false
        return array.items.all { entry ->
            if (++visited > 4_000) return false
            val node = entry as? Json.Obj ?: return false
            val kind = node.fields.keys.filter { it in continuationKinds }.singleOrNull() ?: return false
            if (node.fields.keys.any { it !in continuationKinds && it !in metadata }) return false
            val token = node.obj(kind)?.text("continuation")?.takeIf(String::isNotBlank) ?: return false
            acceptCursor(token)
        }
    }

    /** The observed empty-inbox banner is valid only as the menu's sole section. */
    private fun emptyNotificationMenu(menu: Json.Obj, section: Json.Obj): Boolean {
        if (menu.fields.keys.any { it !in setOf("sections", "header", "style") && it !in metadata } || !validMetadata(menu)) return false
        if (menu.fields.containsKey("style") && menu.text("style") != "MULTI_PAGE_MENU_STYLE_TYPE_NOTIFICATIONS") return false
        if (menu.fields.containsKey("header")) {
            val header = menu.obj("header") ?: return false
            if (header.fields.keys.any { it != "simpleMenuHeaderRenderer" && it !in metadata } ||
                !validMetadata(header) || header.obj("simpleMenuHeaderRenderer") == null) return false
        }
        if (section.fields.keys.any { it != "backgroundPromoRenderer" && it !in metadata } || !validMetadata(section)) return false
        val promo = section.obj("backgroundPromoRenderer") ?: return false
        if (promo.fields.keys.any { it !in setOf("title", "bodyText", "icon") && it !in metadata } || !validMetadata(promo)) return false
        val icon = promo.obj("icon") ?: return false
        return icon.fields.keys == setOf("iconType") && icon.text("iconType") == "NOTIFICATIONS" &&
            promoText(promo.fields["title"]) && promoText(promo.fields["bodyText"])
    }

    private fun validMetadata(node: Json.Obj): Boolean = metadata.all { field ->
        val value = node.fields[field]
        value == null || if (field == "commandMetadata") value is Json.Obj else value is Json.Str
    }

    private fun promoText(value: Json?): Boolean {
        val text = value as? Json.Obj ?: return false
        if (text.fields.keys.any { it !in setOf("simpleText", "runs", "accessibility") } ||
            text.fields["accessibility"]?.let { it !is Json.Obj } == true) return false
        val content = text.fields.keys.filter { it in setOf("simpleText", "runs") }.singleOrNull() ?: return false
        if (content == "simpleText") return !text.text("simpleText").isNullOrBlank()
        val runs = text.fields["runs"] as? Json.Arr ?: return false
        return runs.items.isNotEmpty() && runs.items.size <= 2_000 && runs.items.all {
            it is Json.Obj && it.fields.keys == setOf("text") && it.fields["text"] is Json.Str
        } && runs.items.any { !(it as Json.Obj).text("text").isNullOrBlank() }
    }

    private fun command(value: Json, depth: Int): Boolean {
        val node = value as? Json.Obj ?: return false
        val kinds = node.fields.keys.filter { it in commandKinds }
        if (kinds.size != 1 || node.fields.keys.any { it !in commandKinds && it !in metadata }) return false
        val kind = kinds.single()
        val body = node.fields[kind] as? Json.Obj ?: return false
        return if (kind == "openPopupAction") {
            val popup = body.obj("popup") ?: return false
            val menus = popup.fields.keys.filter { it in menuKinds }
            menus.size == 1 && menu(menus.single(), popup.fields[menus.single()], depth + 1)
        } else list(body.fields["continuationItems"], depth + 1)
    }
}
