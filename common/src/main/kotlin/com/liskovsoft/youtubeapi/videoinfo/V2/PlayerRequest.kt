package com.liskovsoft.youtubeapi.videoinfo.V2

private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
private const val WEB_VERSION = "2.20260907.06.00"
private const val WEB_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val SAFARI_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]+$")

data class PlayerQuery(val url: String, val headers: Map<String, String>, val body: String)

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
    PlayerClient.ANDROID_VR to ClientSpec(
        "ANDROID_VR",
        "1.65.10",
        "28",
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    )
)

fun playerQuery(client: PlayerClient, videoId: String, visitor: String?): PlayerQuery {
    if (!VIDEO_ID.matches(videoId)) return PlayerQuery("", emptyMap(), "")
    val spec = specs.getValue(client)
    val headers = linkedMapOf(
        "Content-Type" to "application/json",
        "User-Agent" to spec.agent,
        "X-Youtube-Client-Name" to spec.id,
        "X-Youtube-Client-Version" to spec.version
    )
    if (!visitor.isNullOrEmpty()) headers["X-Goog-Visitor-Id"] = visitor
    val body = """{"context":{"client":{"clientName":"${spec.name}","clientVersion":"${spec.version}","hl":"en","gl":"US"}},"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
    return PlayerQuery(PLAYER_URL, headers, body)
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
