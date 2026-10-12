package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.HttpURLConnection
import java.net.URL

private const val TIMEOUT_MS = 15000

fun postPlayer(query: PlayerQuery): Pair<Int, String> {
    val connection = URL(query.url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.doOutput = true
        for ((name, value) in query.headers) {
            connection.setRequestProperty(name, value)
        }
        connection.outputStream.use { stream ->
            stream.write(query.body.toByteArray(Charsets.UTF_8))
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return code to body
    } finally {
        connection.disconnect()
    }
}
