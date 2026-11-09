package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Locale

data class PlayerScript(val url: String, val source: String)
data class PlayerChallengeRequest(val script: PlayerScript, val signatures: Set<String>, val throttles: Set<String>)
interface PlayerSolver { fun solve(request: PlayerChallengeRequest): PlayerChallenge }
enum class TokenPurpose { PLAYER, MEDIA, SUBTITLE }
data class PlayerTokenRequest(val client: PlayerClient, val videoId: String, val context: RequestContext, val purpose: TokenPurpose)
data class PlayerToken(val value: String, val expiresAtMs: Long) { override fun toString() = "PlayerToken(expiresAtMs=$expiresAtMs)" }
interface PlayerTokenProvider { fun token(request: PlayerTokenRequest): PlayerToken? }

enum class PlayerAccessRestriction { PAYMENT_REQUIRED, LOGIN_REQUIRED }

internal fun playerAccessRestriction(status: String?, reason: String?): PlayerAccessRestriction? {
    if (status == "OK" || status == null) return null
    val text = reason.orEmpty().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    // Only explicit purchase/rental messages establish a paywall. Generic
    // UNPLAYABLE, sign-in, age and bot checks do not establish one.
    val paid = listOf("requires payment to watch", "purchase this video", "rent this video",
        "purchase or rent", "buy or rent", "需付费观看", "需要付费才能观看", "需付費觀看",
        "需要付費才能觀看", "购买此视频", "購買這部影片", "租借這部影片")
        .any(text::contains)
    return when {
        paid -> PlayerAccessRestriction.PAYMENT_REQUIRED
        status == "LOGIN_REQUIRED" -> PlayerAccessRestriction.LOGIN_REQUIRED
        else -> null
    }
}

data class PlayerLoadAttempt(val client: PlayerClient, val status: String?, val error: RequestError?, val elapsedMs: Long,
    val restriction: PlayerAccessRestriction? = null, val authenticated: Boolean = false)

@JvmOverloads
fun loadPlayer(videoId: String, context: RequestContext, http: HttpClient = JvmHttpClient(),
    script: PlayerScript? = null, solver: PlayerSolver? = null, tokens: PlayerTokenProvider? = null,
    queues: PlayerClientQueues = PlayerClientQueues(), excluded: Set<PlayerClient> = emptySet(),
    now: () -> Long = System::currentTimeMillis,
    preference: PlaybackPreference = PlaybackPreference(),
    onAttempt: ((PlayerLoadAttempt) -> Unit)? = null,
    deferred: Set<PlayerClient> = emptySet(),
    scriptLoader: (() -> PlayerScript?)? = null): ReadResult<PlayerLoad> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    var answer: PlayerAnswer? = null
    var lastError: RequestError? = null
    var verification: RequestError? = null
    val signIn = if (context.signedIn) SignIn.SIGNED_IN else SignIn.SIGNED_OUT
    var tokenDeadline: Long? = null
    val activeScript by lazy { scriptLoader?.invoke() ?: script }
    fun token(client: PlayerClient, purpose: TokenPurpose, identity: RequestContext): String? {
        val value = tokens?.token(PlayerTokenRequest(client, videoId, identity, purpose))
            ?.takeIf { it.value.isNotBlank() && it.expiresAtMs > now() } ?: return null
        tokenDeadline = minOf(tokenDeadline ?: Long.MAX_VALUE, value.expiresAtMs)
        return value.value
    }
    val result = PlayerAttempt(queues).runCancellable(signIn, deferred, excluded,
        { lastError?.kind == RequestFailure.CANCELLED }) run@ { client ->
        if (Thread.currentThread().isInterrupted) {
            lastError = RequestError(RequestFailure.CANCELLED, "cancelled")
            return@run PlayerAnswer(true, null, null, false, false, false, false)
        }
        val started = now()
        var status: String? = null
        var restriction: PlayerAccessRestriction? = null
        var failure: RequestError? = null
        fun rejected(error: RequestError, answer: PlayerAnswer = PlayerAnswer(true, null, null, false, false, false, false)): PlayerAnswer {
            lastError = error
            failure = error
            if (error.kind == RequestFailure.VERIFICATION) verification = error
            return answer
        }
        tokenDeadline = null
        val identity = when (client) {
            PlayerClient.TV, PlayerClient.TV_TIZEN -> context
            PlayerClient.WEB, PlayerClient.WEB_SAFARI, PlayerClient.WEB_EMBED ->
                context.copy(accountId = null, accessToken = null, onBehalfOfUser = null)
            else -> context.anonymous()
        }
        try {
            val requestScript = if (client == PlayerClient.VISIONOS || client == PlayerClient.ANDROID_VR) script else activeScript
            var query = contextualQuery(playerQuery(client, videoId, identity.visitor,
                requestScript?.let { signatureTimestamp(it.source) }), identity)
            val pot = token(client, TokenPurpose.PLAYER, identity)
            if (pot != null) {
                val root = jsonObject(query.body)!!
                query = query.copy(body = Json.Obj(root.fields + ("serviceIntegrityDimensions" to Json.Obj(mapOf("poToken" to Json.Str(pot))))).encode())
            }
            val response = requestObject(query, http)
            if (!response.ok) {
                return@run rejected(response.error ?: RequestError(RequestFailure.PARSE, "empty_player_response"))
            }
            val receivedAt = now()
            var fetched = playerAnswer(response.value!!)
            status = fetched.status?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) }
            if (isBotCheck(fetched.reason)) {
                return@run rejected(RequestError(RequestFailure.VERIFICATION, "playability_bot_check"), fetched)
            }
            restriction = playerAccessRestriction(status, fetched.reason)
            fetched = openBoundFormats(fetched, script, solver) { activeScript }
            // Tokens decorate usable URLs; they cannot create a missing source or
            // satisfy an unsupported format preference. Reject before costly minting.
            if (!selectPlayback(fetched, preference).hasSource()) {
                val status = fetched.status?.takeIf { it != "OK" && it.matches(Regex("[A-Z_]{1,48}")) }
                return@run rejected(RequestError(RequestFailure.UNSUPPORTED,
                    status?.let { "playability_${it.lowercase(Locale.ROOT)}" } ?: "no_matching_format"),
                    fetched.copy(hasDirectUrl = false, hasHls = false, hasMuxed = false,
                        hlsUrl = null, dashUrl = null, sabrUrl = null))
            }
            val mediaPot = token(client, TokenPurpose.MEDIA, identity)
            if (mediaPot != null) {
                fun attach(formats: List<PlayerFormat>) = formats.map { it.copy(url = it.url?.let { url -> urlWithParameter(url, "pot", mediaPot) }) }
                fetched = fetched.copy(muxedFormats = attach(fetched.muxedFormats), adaptiveFormats = attach(fetched.adaptiveFormats),
                    muxedUrls = fetched.muxedUrls.map { urlWithParameter(it, "pot", mediaPot) },
                    adaptiveUrls = fetched.adaptiveUrls.map { urlWithParameter(it, "pot", mediaPot) },
                    hlsUrl = fetched.hlsUrl?.let { urlWithParameter(it, "pot", mediaPot) })
            }
            val subtitlePot = if (fetched.captions.any { !it.url.isNullOrBlank() })
                token(client, TokenPurpose.SUBTITLE, identity) else null
            if (subtitlePot != null) fetched = fetched.copy(captions = fetched.captions.map {
                it.copy(url = it.url?.let { url -> urlWithParameter(url, "pot", subtitlePot) })
            })
            val deadlines = listOfNotNull(tokenDeadline, fetched.expiresInSeconds?.let { receivedAt + it.coerceIn(0, 31536000) * 1000 })
            fetched = fetched.copy(expiresAtMs = if (deadlines.isEmpty()) null else deadlines.min(),
                mediaAvailableAtMs = if (fetched.mediaWaitMs > 0) receivedAt + fetched.mediaWaitMs else 0)
            if (fetched.expiresAtMs?.let { it <= now() } == true) {
                return@run rejected(RequestError(RequestFailure.UNSUPPORTED, "expired_player_source"),
                    PlayerAnswer(false, null, null, false, false, false, fetched.live))
            }
            answer = fetched
            fetched
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            rejected(RequestError(RequestFailure.CANCELLED, "cancelled"))
        } catch (failure: PlayerScriptException) {
            rejected(failure.error)
        } catch (error: Exception) {
            rejected(RequestError(RequestFailure.UNSUPPORTED, "challenge_failed", cause = error.javaClass.simpleName))
        } finally {
            onAttempt?.invoke(PlayerLoadAttempt(client, status, failure, (now() - started).coerceAtLeast(0), restriction, identity.signedIn))
        }
    }
    if (result.client == null) return ReadResult(error = lastError?.takeIf { it.kind == RequestFailure.CANCELLED }
        ?: verification ?: lastError ?: RequestError(RequestFailure.UNSUPPORTED, "no_playable_source"))
    return ReadResult(PlayerLoad(result.form, result.client, answer))
}
