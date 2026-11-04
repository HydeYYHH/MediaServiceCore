package com.liskovsoft.youtubeapi.videoinfo.V2

data class AccessToken(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) {
    override fun toString() = "AccessToken(expiresAtMs=$expiresAtMs)"
}
internal fun tokenQuery(client: SignInClient, fields: String) = PlayerQuery(
    SIGN_IN_URL + "token", mapOf("Content-Type" to "application/json"),
    "{"+payload("client_id" to client.clientId, "client_secret" to client.clientSecret)+"," + fields + "}")
internal fun readAccessToken(root: Json.Obj, nowMs: Long, refresh: String? = null): AccessToken? {
    val access = root.text("access_token")?.takeIf { it.isNotBlank() } ?: return null
    val lifetime = root.long("expires_in")?.takeIf { it > 0 && it <= 31536000 } ?: return null
    return AccessToken(access, root.text("refresh_token") ?: refresh, nowMs + lifetime * 1000)
}
internal fun tokenReply(query: PlayerQuery, http: HttpClient): ReadResult<Json.Obj> {
    val reply = try { http.execute(query.request()) } catch (error: Exception) {
        return ReadResult(error = transportError(error))
    }
    val root = jsonObject(reply.body) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_token_reply", reply.status))
    val code = root.text("error")
    if (code != null || reply.status !in 200..299) return ReadResult(error = RequestError(RequestFailure.AUTH, code ?: "http_${reply.status}", reply.status))
    return ReadResult(root)
}
fun pollSignIn(code: SignInCode, client: SignInClient, http: HttpClient = JvmHttpClient(),
    now: () -> Long = System::currentTimeMillis, wait: (Long) -> Unit = Thread::sleep, cancelled: () -> Boolean = { false }): ReadResult<AccessToken> {
    var interval = code.intervalMs
    while (now() < code.expiresAtMs) {
        if (cancelled() || Thread.currentThread().isInterrupted) return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        val reply = tokenReply(tokenQuery(client, payload("code" to code.deviceCode, "grant_type" to "http://oauth.net/grant_type/device/1.0")), http)
        if (reply.ok) return readAccessToken(reply.value!!, now())?.let { ReadResult(it) }
            ?: ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_access_token"))
        when (reply.error?.code) {
            "authorization_pending" -> Unit
            "slow_down" -> interval += 5000
            else -> return ReadResult(error = reply.error)
        }
        val delay = minOf(interval, code.expiresAtMs - now())
        if (delay > 0) try { wait(delay) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        }
    }
    return ReadResult(error = RequestError(RequestFailure.AUTH, "expired_token"))
}
