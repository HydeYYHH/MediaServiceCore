package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

/** The host supplies a browser runtime; protocol and identity remain in the core. */
fun interface ActionAttestationProvider {
    fun snapshot(challenge: ActionAttestationChallenge): String
}

class ActionAttestationChallenge(
    val program: String,
    val globalName: String,
    val interpreterJavascript: String,
    val contentBinding: Map<String, String>,
    val userAgent: String,
    val generation: Long,
) {
    override fun toString() = "ActionAttestationChallenge(generation=$generation)"
}

internal fun actionAttestationCommands(root: Json.Obj): List<Json.Obj> {
    val pending = java.util.ArrayDeque<Json.Obj>()
    val commands = mutableListOf<Json.Obj>()
    pending.add(root)
    root.obj("command")?.let(pending::add)
    for (name in listOf("actions", "onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands"))
        root.arr(name).filterIsInstance<Json.Obj>().forEach(pending::add)
    while (pending.isNotEmpty()) {
        val command = pending.removeLast()
        command.obj("runAttestationCommand")?.let(commands::add)
        command.obj("innertubeCommand")?.let(pending::add)
        command.obj("commandExecutorCommand")?.arr("commands")?.filterIsInstance<Json.Obj>()?.forEach(pending::add)
    }
    return commands.distinctBy(Json.Obj::encode)
}

/** A post-mutation challenge is completed once; the mutation itself is never replayed. */
internal fun completeActionAttestation(commands: List<Json.Obj>, query: PlayerQuery,
    context: RequestContext?, http: HttpClient): RequestError? {
    val provider = context?.actionAttestationProvider
        ?: return RequestError(RequestFailure.VERIFICATION, "attestation_required")
    if (commands.size > 8 || !context.signedIn || jsonObject(query.body)?.path("context", "client")?.text("clientName") != "WEB")
        return RequestError(RequestFailure.VERIFICATION, "attestation_unsupported")
    return try {
        for (command in commands) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val type = command.text("engagementType")
            val ids = command.fields["ids"] as? Json.Arr
            if (!command.fields.containsKey("entityTrackingInfo") &&
                (type?.matches(Regex("ENGAGEMENT_TYPE_[A-Z0-9_]{1,100}")) != true || ids == null))
                return RequestError(RequestFailure.PARSE, "invalid_attestation_command")
            val fields = if (command.fields.containsKey("entityTrackingInfo"))
                mapOf("entityTrackingInfo" to command.fields.getValue("entityTrackingInfo"))
            else mapOf("engagementType" to Json.Str(type!!), "ids" to ids!!)
            val requestContext = jsonObject(query.body)?.obj("context")
                ?: return RequestError(RequestFailure.PARSE, "invalid_attestation_context")
            val get = query.copy(url = YOUTUBE_API + "att/get", body = Json.Obj(fields + ("context" to requestContext)).encode())
            val response = requestObject(get, http)
            if (!response.ok) return response.error
            val result = response.value!!
            val challenge = result.text("challenge")?.takeIf { it.isNotBlank() && it.length <= 131072 }
                ?: return RequestError(RequestFailure.PARSE, "missing_attestation_challenge")
            val legacy = result.obj("botguardData")
            val guard = legacy ?: result.obj("bgChallenge")
                ?: return RequestError(RequestFailure.PARSE, "missing_attestation_program")
            val program = guard.text("program")?.takeIf { it.isNotBlank() && it.length <= 2 * 1024 * 1024 }
                ?: return RequestError(RequestFailure.PARSE, "missing_attestation_program")
            val global = (guard.text("globalName") ?: if (legacy != null) "trayride" else null)
                ?.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")) }
                ?: return RequestError(RequestFailure.PARSE, "invalid_attestation_global")
            val agent = query.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
            val inline = guard.obj("interpreterSafeScript")?.text("privateDoNotAccessOrElseSafeScriptWrappedValue")
                ?: guard.text("interpreterScript")
                ?: guard.obj("interpreterJavascript")?.text("privateDoNotAccessOrElseSafeScriptWrappedValue")
                ?: guard.text("interpreterJavascript")
            val source = if (inline != null) inline else {
                val wrapped = guard.obj("interpreterSafeUrl") ?: guard.obj("interpreterUrl")
                val rawUrl = wrapped?.text("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue") ?: guard.text("interpreterUrl")
                    ?: return RequestError(RequestFailure.PARSE, "missing_attestation_interpreter")
                val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                val uri = try { URI(url) } catch (_: Exception) { null }
                if (uri?.scheme != "https" || uri.host !in setOf("www.youtube.com", "www.google.com", "www.gstatic.com") ||
                    uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawFragment != null)
                    return RequestError(RequestFailure.INVALID, "attestation_interpreter_origin")
                // Runtime source never receives account cookies or authorization.
                val downloaded = http.execute(HttpRequest("GET", url, mapOf("User-Agent" to agent)))
                if (downloaded.status != 200) return RequestError(RequestFailure.HTTP, "attestation_interpreter_http", downloaded.status)
                downloaded.body
            }
            if (source.isBlank() || source.length > 4 * 1024 * 1024)
                return RequestError(RequestFailure.PARSE, "invalid_attestation_interpreter")
            val snapshot = provider.snapshot(ActionAttestationChallenge(program, global, source,
                mapOf("atr_challenge" to challenge), agent, context.generation))
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (snapshot.isBlank() || snapshot.length > 2 * 1024 * 1024)
                return RequestError(RequestFailure.VERIFICATION, "invalid_attestation_response")
            val log = query.copy(url = YOUTUBE_API + "att/log", body = Json.Obj(mapOf(
                "context" to requestContext, "challenge" to Json.Str(challenge), "webResponse" to Json.Str(snapshot))).encode())
            val logged = requestObject(log, http)
            if (!logged.ok) return logged.error
            val ack = logged.value!!
            if (ack.fields.containsKey("error") || ack.bool("success") == false ||
                ack.text("status") in setOf("FAILED", "ERROR", "STATUS_FAILED", "STATUS_ERROR") || actionAttestationCommands(ack).isNotEmpty())
                return RequestError(RequestFailure.VERIFICATION, "attestation_rejected")
            val metadataAck = ack.fields.isNotEmpty() && ack.fields.all { (name, value) ->
                when (name) {
                    "responseContext" -> value is Json.Obj
                    "trackingParams" -> value is Json.Str && value.value.isNotBlank()
                    else -> false
                }
            }
            if (!metadataAck && ack.bool("success") != true &&
                ack.text("status") !in setOf("SUCCESS", "OK", "STATUS_SUCCESS"))
                return RequestError(RequestFailure.PARSE, "unknown_attestation_acknowledgement")
        }
        null
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        RequestError(RequestFailure.CANCELLED, "attestation_cancelled")
    } catch (failure: RequestException) {
        failure.failure
    } catch (_: Exception) {
        RequestError(RequestFailure.VERIFICATION, "attestation_failed")
    }
}
