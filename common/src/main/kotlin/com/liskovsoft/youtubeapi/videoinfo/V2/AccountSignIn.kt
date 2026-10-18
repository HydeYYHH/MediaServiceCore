package com.liskovsoft.youtubeapi.videoinfo.V2

// Wire contract: yuliskov/MediaServiceCore@7c2451ea, auth/V2/AuthApiHelper.
data class SignInClient(val clientId: String, val clientSecret: String, val deviceId: String,
    val modelName: String = "ytlr::", val scope: String = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content") {
    override fun toString() = "SignInClient(modelName=$modelName)"
}
data class SignInCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresAtMs: Long, val intervalMs: Long) {
    override fun toString() = "SignInCode(expiresAtMs=$expiresAtMs)"
}
internal const val SIGN_IN_URL = "https://www.youtube.com/o/oauth2/"
fun signInQuery(client: SignInClient): PlayerQuery = if (client.clientId.isBlank() || client.deviceId.isBlank()) PlayerQuery("", emptyMap(), "") else
    PlayerQuery(SIGN_IN_URL + "device/code", mapOf("Content-Type" to "application/json"),
        "{"+payload("client_id" to client.clientId, "device_id" to client.deviceId, "model_name" to client.modelName, "scope" to client.scope)+"}")
fun readSignInCode(body: String, nowMs: Long): SignInCode? {
    val root = jsonObject(body) ?: return null
    val device = root.text("device_code")?.takeIf { it.isNotBlank() } ?: return null
    val user = root.text("user_code")?.takeIf { it.isNotBlank() } ?: return null
    val url = root.text("verification_url") ?: root.text("verification_uri") ?: return null
    val expires = root.long("expires_in")?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 - nowMs / 1000 } ?: return null
    val interval = root.long("interval")?.takeIf { it > 0 && it <= 3600 } ?: 5
    return SignInCode(device, user, url, nowMs + expires * 1000, interval * 1000)
}
fun loadSignInCode(client: SignInClient, http: HttpClient = JvmHttpClient(), nowMs: Long = System.currentTimeMillis()): ReadResult<SignInCode> {
    val response = requestObject(signInQuery(client), http)
    if (!response.ok) return ReadResult(error = response.error)
    return readSignInCode(response.value!!.encode(), nowMs)?.let { ReadResult(it) }
        ?: ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_sign_in_code"))
}
