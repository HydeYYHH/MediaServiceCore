package com.liskovsoft.youtubeapi.videoinfo.V2

private const val BIND_BASE = "https://www.youtube.com/api/lounge/bc/bind" +
    "?device=LOUNGE_SCREEN" +
    "&theme=cl" +
    "&capabilities=dsp%2Cmic%2Cdpa" +
    "&mdxVersion=2" +
    "&VER=8" +
    "&v=2" +
    "&t=1" +
    "&app=lb-v4" +
    "&AID=42" +
    "&zx=xxxxxxxxxxxx"
private const val BIND_URL = BIND_BASE + "&RID=1337"
private const val FORM = "application/x-www-form-urlencoded"
private const val HEX = "0123456789ABCDEF"

fun nowPlayingQuery(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    videoId: String?,
    positionMs: Long,
    durationMs: Long,
    state: Int,
    ctt: String?,
    playlistId: String?,
    playlistIndex: String?,
    ofs: Int
): PlayerQuery {
    val url = reportUrl(screenName, deviceId, loungeToken, sessionId, gSessionId)
    if (url.isEmpty()) return PlayerQuery("", emptyMap(), "")
    val fields = ArrayList<Pair<String, String>>()
    fields.add(Pair("count", "1"))
    fields.add(Pair("ofs", ofs.toString()))
    fields.add(Pair("req0__sc", "nowPlaying"))
    addText(fields, "req0_videoId", videoId)
    fields.add(Pair("req0_state", state.toString()))
    addTime(fields, positionMs, durationMs)
    addText(fields, "req0_ctt", ctt)
    addText(fields, "req0_listId", playlistId)
    addText(fields, "req0_currentIndex", playlistIndex)
    return formQuery(url, fields)
}

fun stateQuery(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    positionMs: Long,
    durationMs: Long,
    state: Int,
    ofs: Int
): PlayerQuery {
    val url = reportUrl(screenName, deviceId, loungeToken, sessionId, gSessionId)
    if (url.isEmpty()) return PlayerQuery("", emptyMap(), "")
    val position = if (positionMs < 0) durationMs else positionMs
    if (durationMs <= 0 || position > durationMs) return PlayerQuery("", emptyMap(), "")
    val fields = ArrayList<Pair<String, String>>()
    fields.add(Pair("count", "1"))
    fields.add(Pair("ofs", ofs.toString()))
    fields.add(Pair("req0__sc", "onStateChange"))
    fields.add(Pair("req0_state", state.toString()))
    fields.add(Pair("req0_cpn", "foo"))
    addTime(fields, position, durationMs)
    return formQuery(url, fields)
}

fun volumeQuery(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    volume: Int,
    ofs: Int
): PlayerQuery {
    if (volume == -1) return PlayerQuery("", emptyMap(), "")
    val url = reportUrl(screenName, deviceId, loungeToken, sessionId, gSessionId)
    if (url.isEmpty()) return PlayerQuery("", emptyMap(), "")
    val fields = ArrayList<Pair<String, String>>()
    fields.add(Pair("count", "1"))
    fields.add(Pair("ofs", ofs.toString()))
    fields.add(Pair("req0__sc", "onVolumeChanged"))
    fields.add(Pair("req0_volume", volume.toString()))
    fields.add(Pair("req0_muted", "false"))
    return formQuery(url, fields)
}

fun subtitleQuery(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    vssId: String?,
    languageCode: String?,
    ofs: Int
): PlayerQuery {
    val url = reportUrl(screenName, deviceId, loungeToken, sessionId, gSessionId)
    if (url.isEmpty()) return PlayerQuery("", emptyMap(), "")
    val fields = ArrayList<Pair<String, String>>()
    fields.add(Pair("count", "1"))
    fields.add(Pair("ofs", ofs.toString()))
    fields.add(Pair("req0__sc", "onSubtitlesTrackChanged"))
    val track = vssId?.trim()
    if (!track.isNullOrEmpty()) {
        fields.add(Pair("req0_vss_id", track))
        if (!languageCode.isNullOrEmpty()) {
            fields.add(Pair("req0_languageCode", languageCode))
            fields.add(Pair("req0_sourceLanguageCode", languageCode))
        }
    }
    return formQuery(url, fields)
}

fun previousNextQuery(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    ofs: Int
): PlayerQuery {
    val url = reportUrl(screenName, deviceId, loungeToken, sessionId, gSessionId)
    if (url.isEmpty()) return PlayerQuery("", emptyMap(), "")
    val fields = ArrayList<Pair<String, String>>()
    fields.add(Pair("count", "1"))
    fields.add(Pair("ofs", ofs.toString()))
    fields.add(Pair("req0__sc", "onHasPreviousNextChanged"))
    fields.add(Pair("req0_hasPrevious", "false"))
    fields.add(Pair("req0_hasNext", "false"))
    addTime(fields, -1, -1)
    return formQuery(url, fields)
}

fun postNowPlaying(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    videoId: String?,
    positionMs: Long,
    durationMs: Long,
    state: Int,
    ctt: String?,
    playlistId: String?,
    playlistIndex: String?,
    ofs: Int,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean {
    return posted(nowPlayingQuery(
        screenName,
        deviceId,
        loungeToken,
        sessionId,
        gSessionId,
        videoId,
        positionMs,
        durationMs,
        state,
        ctt,
        playlistId,
        playlistIndex,
        ofs
    ), post)
}

fun postState(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    positionMs: Long,
    durationMs: Long,
    state: Int,
    ofs: Int,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean {
    return posted(stateQuery(
        screenName,
        deviceId,
        loungeToken,
        sessionId,
        gSessionId,
        positionMs,
        durationMs,
        state,
        ofs
    ), post)
}

fun postVolume(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    volume: Int,
    ofs: Int,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean {
    return posted(volumeQuery(screenName, deviceId, loungeToken, sessionId, gSessionId, volume, ofs), post)
}

fun postSubtitle(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    vssId: String?,
    languageCode: String?,
    ofs: Int,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean {
    return posted(subtitleQuery(
        screenName,
        deviceId,
        loungeToken,
        sessionId,
        gSessionId,
        vssId,
        languageCode,
        ofs
    ), post)
}

fun postPreviousNext(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?,
    ofs: Int,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean {
    return posted(previousNextQuery(screenName, deviceId, loungeToken, sessionId, gSessionId, ofs), post)
}

private fun posted(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): Boolean {
    if (query.url.isEmpty()) return false
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return false
    }
    return http.first == 200
}

private fun reportUrl(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    sessionId: String?,
    gSessionId: String?
): String {
    if (screenName.isNullOrBlank() || deviceId.isNullOrBlank() || loungeToken.isNullOrBlank() ||
        sessionId.isNullOrBlank() || gSessionId.isNullOrBlank()
    ) {
        return ""
    }
    return BIND_URL +
        "&name=" + encode(screenName) +
        "&id=" + encode(deviceId) +
        "&loungeIdToken=" + encode(loungeToken) +
        "&SID=" + encode(sessionId) +
        "&gsessionid=" + encode(gSessionId)
}

private fun addTime(fields: MutableList<Pair<String, String>>, positionMs: Long, durationMs: Long) {
    val position = if (positionMs < 0) durationMs else positionMs
    if (position >= 0) fields.add(Pair("req0_currentTime", seconds(position)))
    if (durationMs > 0) {
        val text = seconds(durationMs)
        fields.add(Pair("req0_duration", text))
        fields.add(Pair("req0_seekableEndTime", text))
    }
    fields.add(Pair("req0_loadedTime", "0"))
    fields.add(Pair("req0_seekableStartTime", "0"))
}

private fun addText(fields: MutableList<Pair<String, String>>, name: String, value: String?) {
    if (value.isNullOrBlank()) return
    fields.add(Pair(name, value))
}

private fun seconds(ms: Long): String = (ms / 1000f).toString()

private fun formQuery(url: String, fields: List<Pair<String, String>>): PlayerQuery {
    val headers = linkedMapOf("Content-Type" to FORM)
    return PlayerQuery(url, headers, formBody(fields))
}

private fun formBody(fields: List<Pair<String, String>>): String {
    val out = StringBuilder()
    for (i in fields.indices) {
        if (i > 0) out.append('&')
        out.append(encode(fields[i].first))
        out.append('=')
        out.append(encode(fields[i].second))
    }
    return out.toString()
}

private fun encode(value: String): String {
    val out = StringBuilder()
    for (c in value) {
        val plain = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'
        if (plain) {
            out.append(c)
            continue
        }
        val bytes = c.toString().toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val hex = b.toInt() and 0xff
            out.append('%')
            out.append(HEX[hex ushr 4])
            out.append(HEX[hex and 0x0f])
        }
    }
    return out.toString()
}
