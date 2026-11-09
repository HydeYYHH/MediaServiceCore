package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.util.Base64

internal enum class ViewerEntityKind(val payload: String, val diagnosticName: String, val keyType: Int) {
    RATING("likeStatusEntity", "like_status", 62),
    SUBSCRIPTION("subscriptionStateEntity", "subscription_state", 51)
}

internal data class ExpectedViewerState(val kind: ViewerEntityKind, val target: String, val state: String)

/** Only target-bound replacement mutations provide current viewer state. */
internal data class ViewerEntityState(val kind: ViewerEntityKind, val state: String?, val mutation: String) {
    fun metadata() = mapOf("target_entity_kind" to kind.diagnosticName,
        "target_entity_state" to (state ?: "unknown"), "target_entity_mutation" to mutation)
}

internal fun viewerEntityState(root: Json.Obj, kind: ViewerEntityKind, target: String): ViewerEntityState? {
    var latest: ViewerEntityState? = null
    for (value in root.path("frameworkUpdates", "entityBatchUpdate")?.arr("mutations").orEmpty()) {
        val mutation = value as? Json.Obj ?: continue
        val key = mutation.text("entityKey") ?: continue
        if (!entityMatches(key, kind, target)) continue
        val payload = mutation.obj("payload")?.obj(kind.payload)
        if (payload != null) {
            val replacement = mutation.text("type") == "ENTITY_MUTATION_TYPE_REPLACE" && payload.text("key") == key
            val state = if (replacement) viewerState(payload, kind) else null
            latest = ViewerEntityState(kind, state, if (replacement) "replace" else "unknown")
        } else {
            // A later delete or unsupported mutation invalidates the earlier replacement.
            latest = ViewerEntityState(kind, null,
                if (mutation.text("type") == "ENTITY_MUTATION_TYPE_DELETE") "delete" else "unknown")
        }
    }
    return latest
}

/** Legacy inline state is separate from mutation payloads, which require their wrapper's key/type. */
internal fun inlineViewerEntityState(root: Json.Obj, kind: ViewerEntityKind, target: String?): String? {
    // Older read responses publish one unkeyed viewer entity directly under frameworkUpdates.
    // This legacy shape is not a mutation and cannot confirm an action request.
    for (container in listOfNotNull(root, root.obj("frameworkUpdates"))) {
        container.obj(kind.payload)?.takeIf { it.text("key") == null }?.let { return viewerState(it, kind) }
    }
    if (target == null) return null
    val inline = Json.Obj(root.fields - "frameworkUpdates")
    return inline.objects(kind.payload).firstNotNullOfOrNull { payload ->
        payload.text("key")?.takeIf { entityMatches(it, kind, target) }?.let { viewerState(payload, kind) }
    }
}

private fun viewerState(payload: Json.Obj, kind: ViewerEntityKind) = when (kind) {
    ViewerEntityKind.RATING -> payload.text("likeStatus")?.takeIf { it in setOf("LIKE", "DISLIKE", "INDIFFERENT") }
    ViewerEntityKind.SUBSCRIPTION -> payload.bool("subscribed")?.toString()
}

private fun entityMatches(key: String, kind: ViewerEntityKind, target: String): Boolean = try {
    if (key.length > 4096) false else {
        val decoded = URLDecoder.decode(key, "UTF-8").replace(' ', '+')
        val bytes = Base64.getUrlDecoder().decode(decoded.replace('+', '-').replace('/', '_'))
        // Entity-key protobuf fields 2 and 4 bind the identity and entity namespace.
        bytes.size <= 2048 && bytes.protobufField(2)?.toString(Charsets.UTF_8) == target &&
            bytes.protobufField(4, wireType = 0)?.singleOrNull()?.toInt() == kind.keyType
    }
} catch (_: IllegalArgumentException) {
    false
}

internal fun actionRequestMetadata(query: PlayerQuery): Map<String, String> {
    val root = jsonObject(query.body)
    fun header(name: String) = query.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    val client = root?.path("context", "client")
    val clientName = client?.text("clientName")
    val version = client?.text("clientVersion")
    val authorization = header("Authorization")
    return mapOf(
        "request_client" to (clientName?.takeIf { it in setOf("TVHTML5", "WEB", "WEB_EMBEDDED_PLAYER", "ANDROID", "ANDROID_VR", "VISIONOS") } ?: "other"),
        "request_client_version" to (version?.takeIf { it.length <= 64 && clientVersionPattern.matches(it) } ?: "other"),
        "request_action_context" to when {
            clientName != "TVHTML5" -> "other"
            root != null && client != null && completeTvActionContext(root, client) -> "complete"
            else -> "partial"
        },
        "request_auth" to when {
            authorization?.startsWith("Bearer ") == true -> "oauth"
            authorization?.startsWith("SAPISIDHASH ") == true -> "web"
            authorization.isNullOrBlank() -> "anonymous"
            else -> "other"
        },
        "request_delegated" to (!root?.path("context", "user")?.text("onBehalfOfUser").isNullOrBlank()
            || !header("X-Goog-PageId").isNullOrBlank()).toString(),
    )
}

/** Records field coverage without exposing the user agent or other request values. */
private fun completeTvActionContext(root: Json.Obj, client: Json.Obj): Boolean {
    val user = root.path("context", "user")
    return listOf("clientScreen", "acceptLanguage", "acceptRegion", "userAgent").all { !client.text(it).isNullOrBlank() } &&
        user?.bool("enableSafetyMode") != null && user.bool("lockedSafetyMode") != null &&
        root.bool("contentCheckOk") != null && root.bool("racyCheckOk") != null
}

internal fun actionResponseMetadata(root: Json.Obj): Map<String, String> {
    val response = root.obj("responseContext")
    val loggedOut = response?.obj("mainAppWebResponseContext")?.bool("loggedOut")
    val signedIn = loggedOut?.not() ?: response?.arr("serviceTrackingParams").orEmpty()
        .asSequence().filterIsInstance<Json.Obj>().flatMap { it.arr("params").asSequence() }
        .filterIsInstance<Json.Obj>().filter { it.text("key") == "logged_in" }
        .mapNotNull { when (it.text("value")) { "1" -> true; "0" -> false; else -> null } }.firstOrNull()
    return mapOf("response_signed_in" to (signedIn?.toString() ?: "unknown"))
}

private val clientVersionPattern = Regex("[0-9]+(?:\\.[0-9]+)*")
