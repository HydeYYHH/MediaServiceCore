package com.liskovsoft.youtubeapi.videoinfo.V2

data class LoungeCommand(
    val type: String,
    val videoId: String? = null,
    val playlistId: String? = null,
    val index: Int? = null,
    val seconds: String? = null,
    val ctt: String? = null,
    val volume: Int? = null,
    val delta: Int? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val key: String? = null,
    val voiceStarted: Boolean? = null,
    val languageCode: String? = null
)

fun loungeCommands(body: String): List<LoungeCommand> {
    val rows = commandRows(body) ?: return emptyList()
    val commands = ArrayList<LoungeCommand>()
    for (item in rows.items) {
        val command = readCommand(item) ?: continue
        commands.add(command)
    }
    return commands
}

class LoungeListen {
    private val pending = StringBuilder()

    fun push(line: String): List<LoungeCommand>? {
        pending.append(line)
        pending.append('\n')
        if (line != "]") return null
        if (pending.endsWith("\"noop\"]\n]\n")) return null
        val text = pending.toString()
        pending.setLength(0)
        return loungeCommands(text)
    }
}

private fun readCommand(item: Json): LoungeCommand? {
    val row = item as? Json.Arr ?: return null
    if (row.items.size < 2) return null
    val command = row.items[1] as? Json.Arr ?: return null
    if (command.items.isEmpty()) return null
    val type = (command.items[0] as? Json.Str)?.value ?: return null
    if (type.isEmpty()) return null
    val params = if (command.items.size > 1) command.items[1] as? Json.Obj else null
    if (type == "setPlaylist" || type == "updatePlaylist") {
        return LoungeCommand(
            type,
            params?.raw("videoId"),
            params?.raw("listId"),
            whole(params?.raw("currentIndex")),
            params?.raw("currentTime"),
            params?.raw("ctt")
        )
    }
    if (type == "seekTo") return LoungeCommand(type, seconds = params?.raw("newTime"))
    if (type == "setVolume") {
        return LoungeCommand(type, volume = whole(params?.raw("volume")), delta = whole(params?.raw("delta")))
    }
    if (type == "remoteConnected" || type == "remoteDisconnected") {
        return LoungeCommand(type, deviceId = params?.raw("id"), deviceName = params?.raw("name"))
    }
    if (type == "dpadCommand") return LoungeCommand(type, key = params?.raw("key"))
    if (type == "voiceCommand") return LoungeCommand(type, voiceStarted = params?.raw("status") == "INITIATED")
    if (type == "setSubtitlesTrack") {
        return LoungeCommand(type, videoId = params?.raw("videoId"), languageCode = params?.raw("languageCode"))
    }
    return LoungeCommand(type)
}

private fun commandRows(body: String): Json.Arr? {
    val json = jsonBody(body) ?: return null
    return try {
        JsonReader(json).parse() as? Json.Arr
    } catch (failure: JsonEnd) {
        null
    }
}

private fun jsonBody(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    val newline = trimmed.indexOf('\n')
    if (newline < 0) return trimmed
    val first = trimmed.substring(0, newline).trim()
    if (first.isNotEmpty() && first.all { it in '0'..'9' }) {
        val rest = trimmed.substring(newline + 1).trim()
        return if (rest.isEmpty()) null else rest
    }
    return trimmed
}

private fun Json.Obj.raw(name: String): String? {
    val value = fields[name] ?: return null
    val text = when (value) {
        is Json.Str -> value.value
        is Json.Num -> value.text
        else -> null
    }
    return if (text.isNullOrEmpty()) null else text
}

private fun whole(text: String?): Int? {
    if (text.isNullOrEmpty()) return null
    val digits = if (text.startsWith("-")) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return text.toIntOrNull()
}
