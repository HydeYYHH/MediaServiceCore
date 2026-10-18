package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerScript(val url: String, val source: String)
data class PlayerChallengeRequest(val script: PlayerScript, val signatures: Set<String>, val throttles: Set<String>)
interface PlayerSolver { fun solve(request: PlayerChallengeRequest): PlayerChallenge }
enum class TokenPurpose { PLAYER, MEDIA, SUBTITLE }
data class PlayerTokenRequest(val client: PlayerClient, val videoId: String, val context: RequestContext, val purpose: TokenPurpose)
data class PlayerToken(val value: String, val expiresAtMs: Long) { override fun toString() = "PlayerToken(expiresAtMs=$expiresAtMs)" }
interface PlayerTokenProvider { fun token(request: PlayerTokenRequest): PlayerToken? }

fun loadPlayer(videoId: String, context: RequestContext, http: HttpClient = JvmHttpClient(),
    script: PlayerScript? = null, solver: PlayerSolver? = null, tokens: PlayerTokenProvider? = null,
    queues: PlayerClientQueues = PlayerClientQueues(), excluded: Set<PlayerClient> = emptySet(),
    now: () -> Long = System::currentTimeMillis): ReadResult<PlayerLoad> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    var answer: PlayerAnswer? = null
    var lastError: RequestError? = null
    val signIn = if (context.accessToken == null) SignIn.SIGNED_OUT else SignIn.SIGNED_IN
    fun token(client: PlayerClient, purpose: TokenPurpose): String? =
        tokens?.token(PlayerTokenRequest(client, videoId, context, purpose))?.takeIf { it.expiresAtMs > now() }?.value
    val result = PlayerAttempt(queues).run(signIn) { client ->
        if (client in excluded) return@run PlayerAnswer(true, null, null, false, false, false, false)
        val identity = if (client == PlayerClient.TV) context else context.anonymous()
        var query = contextualQuery(playerQuery(client, videoId, identity.visitor, script?.let { signatureTimestamp(it.source) }), identity)
        try {
            val pot = token(client, TokenPurpose.PLAYER)
            if (pot != null) {
                val root = jsonObject(query.body)!!
                query = query.copy(body = Json.Obj(root.fields + ("serviceIntegrityDimensions" to Json.Obj(mapOf("poToken" to Json.Str(pot))))).encode())
            }
            val response = requestObject(query, http)
            if (!response.ok) {
                lastError = response.error
                return@run PlayerAnswer(true, null, null, false, false, false, false)
            }
            var fetched = playerAnswer(response.value!!.encode())
            if (solver != null && script != null) fetched = openFormats(fetched) { signatures, throttles ->
                solver.solve(PlayerChallengeRequest(script, signatures, throttles))
            }
            val mediaPot = token(client, TokenPurpose.MEDIA)
            if (mediaPot != null) {
                fun attach(formats: List<PlayerFormat>) = formats.map { it.copy(url = it.url?.let { url -> urlWithParameter(url, "pot", mediaPot) }) }
                fetched = fetched.copy(muxedFormats = attach(fetched.muxedFormats), adaptiveFormats = attach(fetched.adaptiveFormats),
                    muxedUrls = fetched.muxedUrls.map { urlWithParameter(it, "pot", mediaPot) },
                    adaptiveUrls = fetched.adaptiveUrls.map { urlWithParameter(it, "pot", mediaPot) },
                    hlsUrl = fetched.hlsUrl?.let { urlWithParameter(it, "pot", mediaPot) })
            }
            val subtitlePot = token(client, TokenPurpose.SUBTITLE)
            if (subtitlePot != null) fetched = fetched.copy(captions = fetched.captions.map {
                it.copy(url = it.url?.let { url -> urlWithParameter(url, "pot", subtitlePot) })
            })
            answer = fetched
            lastError = RequestError(RequestFailure.UNSUPPORTED, fetched.status ?: "no_playable_source")
            fetched
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = RequestError(RequestFailure.CANCELLED, "cancelled")
            PlayerAnswer(true, null, null, false, false, false, false)
        } catch (_: Exception) {
            lastError = RequestError(RequestFailure.UNSUPPORTED, "challenge_failed")
            PlayerAnswer(true, null, null, false, false, false, false)
        }
    }
    if (result.client == null) return ReadResult(error = lastError ?: RequestError(RequestFailure.UNSUPPORTED, "no_playable_source"))
    return ReadResult(PlayerLoad(result.form, result.client, answer))
}
