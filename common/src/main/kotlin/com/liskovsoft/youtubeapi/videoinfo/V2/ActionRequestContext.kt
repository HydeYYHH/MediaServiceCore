package com.liskovsoft.youtubeapi.videoinfo.V2

/** TV actions use the upstream browse/action context, rather than the minimal player context. */
internal fun withTvActionContext(query: PlayerQuery): PlayerQuery {
    val root = jsonObject(query.body) ?: return query
    val context = root.obj("context") ?: return query
    val client = context.obj("client") ?: return query
    if (client.text("clientName") != "TVHTML5") return query

    val clientDefaults = linkedMapOf<String, Json>(
        "clientScreen" to Json.Str("WATCH"),
        "acceptLanguage" to Json.Str(client.text("hl") ?: "en"),
        "acceptRegion" to Json.Str(client.text("gl") ?: "US"),
    )
    query.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.let {
        clientDefaults["userAgent"] = Json.Str(it)
    }
    var contextFields = context.fields + ("client" to Json.Obj(actionDefaults(client.fields, clientDefaults)))
    val user = context.obj("user")
    if (user != null || !context.fields.containsKey("user")) {
        contextFields = contextFields + ("user" to Json.Obj(actionDefaults(user?.fields.orEmpty(), mapOf(
            "enableSafetyMode" to Json.Bool(false),
            "lockedSafetyMode" to Json.Bool(false),
        ))))
    }
    val fields = actionDefaults(root.fields + ("context" to Json.Obj(contextFields)), mapOf(
        "racyCheckOk" to Json.Bool(true),
        "contentCheckOk" to Json.Bool(true),
    ))
    return query.copy(body = Json.Obj(fields).encode())
}

private fun actionDefaults(fields: Map<String, Json>, defaults: Map<String, Json>): Map<String, Json> =
    fields + defaults.filterKeys { !fields.containsKey(it) }
