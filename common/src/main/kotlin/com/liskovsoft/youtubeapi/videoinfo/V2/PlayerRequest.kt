package com.liskovsoft.youtubeapi.videoinfo.V2

private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
private const val WEB_VERSION = "2.20260907.06.00"
private const val WEB_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val SAFARI_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]+$")
private const val REEL_URL = "https://www.youtube.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false"
private const val REEL_AGENT = "com.google.android.youtube/21.03.36 (Linux; U; Android 16; US) gzip"
private const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val nonceRandom = java.security.SecureRandom()

internal fun nonce(length: Int): String {
    val builder = StringBuilder(length)
    repeat(length) { builder.append(NONCE_ALPHABET[nonceRandom.nextInt(NONCE_ALPHABET.length)]) }
    return builder.toString()
}

/**
 * ANDROID reel_item_watch posture. The reel response's muxed formats stay readable
 * without a poToken and outside the ~64 s window that gates pot-less mobile URLs,
 * which is why they serve as the VOD floor when the queue leaders answer adaptive-only.
 */
fun reelFloorQuery(videoId: String, visitor: String?, cpn: String, t: String): PlayerQuery {
    if (!VIDEO_ID.matches(videoId)) return PlayerQuery("", emptyMap(), "")
    val headers = linkedMapOf(
        "Content-Type" to "application/json",
        "User-Agent" to REEL_AGENT,
        "X-Youtube-Client-Name" to "3",
        "X-Youtube-Client-Version" to "21.03.36"
    )
    if (!visitor.isNullOrEmpty()) headers["X-Goog-Visitor-Id"] = visitor
    val visitorField = if (visitor.isNullOrEmpty()) "" else ""","visitorData":"$visitor""""
    val body = """{"context":{"client":{"clientName":"ANDROID","clientVersion":"21.03.36","androidSdkVersion":36,"hl":"en","gl":"US"$visitorField}},""" +
        """"playerRequest":{"videoId":"$videoId","cpn":"$cpn","contentCheckOk":true,"racyCheckOk":true},"disablePlayerResponse":false}"""
    return PlayerQuery("$REEL_URL&t=$t&id=$videoId&\$fields=playerResponse", headers, body)
}

data class PlayerQuery(val url: String, val headers: Map<String, String>, val body: String) {
    override fun toString() = "PlayerQuery(headerCount=${headers.size}, bodyLength=${body.length})"
}

private class ClientSpec(val name: String, val version: String, val id: String, val agent: String)

private val specs = mapOf(
    PlayerClient.VISIONOS to ClientSpec(
        "VISIONOS",
        "1.02",
        "101",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    ),
    PlayerClient.WEB to ClientSpec("WEB", WEB_VERSION, "1", WEB_AGENT),
    PlayerClient.WEB_EMBED to ClientSpec("WEB_EMBEDDED_PLAYER", "2.20260908.01.00", "56", SAFARI_AGENT),
    PlayerClient.WEB_SAFARI to ClientSpec("WEB", WEB_VERSION, "1", SAFARI_AGENT),
    PlayerClient.TV to ClientSpec(
        "TVHTML5",
        "7.20260901.15.00",
        "7",
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
    ),
    PlayerClient.TV_TIZEN to ClientSpec(
        "TVHTML5", "5.20260707", "7",
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"
    ),
    PlayerClient.ANDROID_VR to ClientSpec(
        "ANDROID_VR",
        "1.65.10",
        "28",
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    )
)

/** Media consumers must keep the issued URL's client posture aligned with /player. */
fun playerUserAgent(client: PlayerClient): String = specs.getValue(client).agent

fun playerQuery(
    client: PlayerClient,
    videoId: String,
    visitor: String?,
    signatureTimestamp: Int? = null
): PlayerQuery {
    if (!VIDEO_ID.matches(videoId)) return PlayerQuery("", emptyMap(), "")
    val spec = specs.getValue(client)
    val headers = linkedMapOf(
        "Content-Type" to "application/json",
        "User-Agent" to spec.agent,
        "X-Youtube-Client-Name" to spec.id,
        "X-Youtube-Client-Version" to spec.version
    )
    if (!visitor.isNullOrEmpty()) headers["X-Goog-Visitor-Id"] = visitor
    // This compatibility route needs both the device identity and playback capabilities.
    // Device fields alone return OK with media URLs that immediately reject reads.
    val tizen = client == PlayerClient.TV_TIZEN
    val device = if (tizen) ""","deviceMake":"Samsung","deviceModel":"UKS9800","osName":"Tizen","osVersion":"2.4.0","userAgent":"${spec.agent}","browserName":"Safari","browserVersion":"538.1","clientScreen":"WATCH"""" else ""
    val user = if (tizen) ""","user":{"enableSafetyMode":false,"lockedSafetyMode":false}""" else ""
    val playbackFields = if (tizen) ""","html5Preference":"HTML5_PREF_WANTS","lactMilliseconds":60000,"isInlinePlaybackNoAd":true""" else ""
    val capabilities = if (tizen) ""","devicePlaybackCapabilities":{"supportsVp9Encoding":true,"supportXhr":false}""" else ""
    val playback = if (signatureTimestamp == null) "" else
        """"playbackContext":{"contentPlaybackContext":{"signatureTimestamp":$signatureTimestamp$playbackFields}$capabilities},"""
    val body = """{"context":{"client":{"clientName":"${spec.name}","clientVersion":"${spec.version}","hl":"en","gl":"US"$device}$user},${playback}"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
    return PlayerQuery(PLAYER_URL, headers, body)
}

internal fun videoIdAccepted(videoId: String) = VIDEO_ID.matches(videoId)

internal fun webQuery(url: String, payload: String, visitor: String?): PlayerQuery {
    val spec = specs.getValue(PlayerClient.WEB)
    val headers = linkedMapOf(
        "Content-Type" to "application/json",
        "User-Agent" to spec.agent,
        "X-Youtube-Client-Name" to spec.id,
        "X-Youtube-Client-Version" to spec.version
    )
    if (!visitor.isNullOrEmpty()) headers["X-Goog-Visitor-Id"] = visitor
    val body = """{"context":{"client":{"clientName":"${spec.name}","clientVersion":"${spec.version}","hl":"en","gl":"US"}},$payload}"""
    return PlayerQuery(url, headers, body)
}

fun fetchPlayer(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): PlayerAnswer {
    if (query.url.isEmpty()) return transportFailure()
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return transportFailure()
    }
    if (http.first != 200) return transportFailure()
    return playerAnswer(http.second)
}

private fun transportFailure() = PlayerAnswer(true, null, null, false, false, false, false)
