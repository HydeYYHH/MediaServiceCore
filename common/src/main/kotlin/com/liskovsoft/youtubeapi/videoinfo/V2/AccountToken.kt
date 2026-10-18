package com.liskovsoft.youtubeapi.videoinfo.V2

fun refreshAccessToken(refreshToken: String, client: SignInClient, http: HttpClient = JvmHttpClient(),
    nowMs: Long = System.currentTimeMillis()): ReadResult<AccessToken> {
    if (refreshToken.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_refresh_token"))
    val reply = tokenReply(tokenQuery(client, payload("refresh_token" to refreshToken, "grant_type" to "refresh_token")), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    return readAccessToken(reply.value!!, nowMs, refreshToken)?.let { ReadResult(it) }
        ?: ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_access_token"))
}
class AccountToken(initial: AccessToken, private val client: SignInClient, private val http: HttpClient = JvmHttpClient(),
    private val now: () -> Long = System::currentTimeMillis) {
    private var current = initial
    @Synchronized fun token(): ReadResult<AccessToken> {
        if (current.expiresAtMs > now() + 30000) return ReadResult(current)
        val refresh = current.refreshToken ?: return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
        val result = refreshAccessToken(refresh, client, http, now())
        if (result.ok) current = result.value!!
        return result
    }
}
