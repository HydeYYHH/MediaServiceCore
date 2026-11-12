package com.liskovsoft.youtubeapi.videoinfo.V2

/** A successful management read can still leave the current recording state unknown. */
data class HistoryManagementState(val paused: Boolean?)

/** Missing or contradictory management commands leave the recording state unknown. */
fun watchHistoryManagement(body: String): HistoryManagementState = historyManagement(jsonObject(body))

private fun historyManagement(root: Json.Obj?): HistoryManagementState {
    val controls = historyControls(root)
    val states = controls.keys.mapNotNull { when (it) {
        WatchHistoryCommand.PAUSE -> false
        WatchHistoryCommand.RESUME -> true
        WatchHistoryCommand.CLEAR -> null
    } }.toSet()
    return HistoryManagementState(states.singleOrNull())
}

internal enum class WatchHistoryCommand(val icon: String, val commandType: String) {
    PAUSE("PAUSE_OUTLINED", "LOCAL_WATCH_HISTORY_COMMAND_TYPE_PAUSE"),
    RESUME("PLAY_OUTLINED", "LOCAL_WATCH_HISTORY_COMMAND_TYPE_RESUME"),
    CLEAR("DELETE", "LOCAL_WATCH_HISTORY_COMMAND_TYPE_CLEAR")
}

/** Only the management panel may supply these capabilities; history-item menus cannot. */
private fun historyControls(root: Json.Obj?): Map<WatchHistoryCommand, Json.Obj> {
    val controls = root?.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "browseFeedActionsRenderer")
        ?: root?.path("secondaryContents", "browseFeedActionsRenderer") ?: return emptyMap()
    val found = mutableMapOf<WatchHistoryCommand, MutableList<Json.Obj>>()
    for (item in controls.arr("contents")) {
        val button = (item as? Json.Obj)?.obj("buttonRenderer") ?: continue
        if (button.bool("isDisabled") == true || button.bool("disabled") == true) continue
        val command = WatchHistoryCommand.values().firstOrNull { it.icon == button.obj("icon")?.text("iconType") } ?: continue
        val dialog = button.path("navigationEndpoint", "confirmDialogEndpoint", "content", "confirmDialogRenderer")
            ?: button.path("serviceEndpoint", "confirmDialogEndpoint", "content", "confirmDialogRenderer") ?: continue
        val confirmButton = dialog.path("confirmButton", "buttonRenderer")
        if (confirmButton?.bool("isDisabled") == true || confirmButton?.bool("disabled") == true) continue
        val endpoint = dialog.path("confirmEndpoint", "feedbackEndpoint")
            ?: confirmButton?.path("serviceEndpoint", "feedbackEndpoint") ?: continue
        if (endpoint.text("feedbackToken").isNullOrBlank()) continue
        val declared = endpoint.objects("localWatchHistoryCommand").mapNotNull { it.text("localWatchHistoryCommandType") }.toSet()
        if (declared != setOf(command.commandType)) continue
        found.getOrPut(command) { mutableListOf() }.add(endpoint)
    }
    return found.mapNotNull { (command, endpoints) ->
        endpoints.distinctBy { it.text("feedbackToken") }.singleOrNull()?.let { command to it }
    }.toMap()
}

private fun readHistoryManagement(context: RequestContext, http: HttpClient): ReadResult<Json.Obj> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return requestObject(browseQuery(LibrarySection.HISTORY.browseId, context).withCompactResponse(), http)
}

/** Uncached management metadata for the captured account, independent of history entries. */
fun loadWatchHistoryManagement(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<HistoryManagementState> {
    val result = readHistoryManagement(context, http)
    return if (result.ok) ReadResult(historyManagement(result.value)) else ReadResult(error = result.error)
}

internal fun performWatchHistoryManagement(command: WatchHistoryCommand, context: RequestContext,
    http: HttpClient): ReadResult<ActionReply> {
    val management = readHistoryManagement(context, http)
    if (!management.ok) return ReadResult(error = management.error)
    val controls = historyControls(management.value)
    if (WatchHistoryCommand.PAUSE in controls && WatchHistoryCommand.RESUME in controls)
        return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "ambiguous_history_controls"))
    val endpoint = controls[command]
        ?: return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "missing_history_control"))
    return submitHistoryFeedback(endpoint, context, http)
}

internal fun submitHistoryFeedback(endpoint: Json.Obj, context: RequestContext, http: HttpClient): ReadResult<ActionReply> {
    val token = endpoint.text("feedbackToken")?.takeIf(String::isNotBlank)
        ?: return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_feedback_token"))
    val extra = endpoint.arr("extraFeedbackTokens").mapNotNull { (it as? Json.Str)?.value?.takeIf(String::isNotBlank) }
    val tokens = listOf(token) + extra
    var fields = "\"feedbackTokens\":" + Json.Arr(tokens.map { Json.Str(it) }).encode() +
        ",\"shouldMerge\":" + (endpoint.bool("shouldMerge") == true || extra.isNotEmpty()) +
        ",\"isFeedbackTokenUnencrypted\":" + (endpoint.bool("isFeedbackTokenUnencrypted") == true)
    endpoint.text("cpn")?.takeIf(String::isNotBlank)?.let { fields += ",\"feedbackContext\":{${payload("cpn" to it)}}" }
    val result = signedAction("feedback", fields, context, http)
    if (!result.ok) return result
    val action = result.value!!
    return if (feedbackReply(action.body).processed) ReadResult(action.copy(state = ActionState.CONFIRMED, responseShape = "feedbackResponses",
        metadata = action.metadata + ("confirmation" to "feedback_processed")))
    else ReadResult(error = RequestError(RequestFailure.HTTP, "feedback_not_processed", status = action.httpStatus))
}
