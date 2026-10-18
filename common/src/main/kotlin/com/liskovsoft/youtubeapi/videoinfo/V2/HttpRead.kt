package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String = "")
data class HttpReply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}
enum class RequestFailure { INVALID, AUTH, HTTP, TRANSPORT, PARSE, CANCELLED, UNSUPPORTED }
data class RequestError(val kind: RequestFailure, val code: String, val status: Int? = null, val retryAfterMs: Long? = null)
data class ReadResult<T>(val value: T? = null, val error: RequestError? = null) {
    val ok: Boolean get() = error == null && value != null
}
interface HttpClient { fun execute(request: HttpRequest): HttpReply }
class JvmHttpClient(private val timeoutMs: Int = 15000, private val maxBodyBytes: Int = 8 * 1024 * 1024) : HttpClient {
    override fun execute(request: HttpRequest): HttpReply {
        require(request.method in listOf("GET", "POST", "HEAD"))
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "MediaServiceCore")
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            if (request.method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val out = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    if (out.size() + count > maxBodyBytes) throw java.io.IOException("response_too_large")
                    out.write(bytes, 0, count)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }.orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
            return HttpReply(status, body, headers)
        } finally { connection.disconnect() }
    }
}
internal fun requestBody(request: HttpRequest, http: HttpClient): ReadResult<String> {
    if (request.url.isEmpty()) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_request"))
    val reply = try { http.execute(request) } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
    } catch (_: Exception) { return ReadResult(error = RequestError(RequestFailure.TRANSPORT, "transport_failed")) }
    if (reply.status !in 200..299) return ReadResult(error = RequestError(
        if (reply.status == 401 || reply.status == 403) RequestFailure.AUTH else RequestFailure.HTTP,
        "http_${reply.status}", reply.status, reply.header("Retry-After")?.toLongOrNull()?.times(1000)))
    return ReadResult(reply.body)
}
internal fun requestObject(query: PlayerQuery, http: HttpClient): ReadResult<Json.Obj> {
    val response = requestBody(query.request(), http)
    if (!response.ok) return ReadResult(error = response.error)
    val root = jsonObject(response.value!!) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_json"))
    val error = root.obj("error")
    if (root.fields.containsKey("error")) return ReadResult(error = RequestError(
        if (error?.int("code") in listOf(401, 403)) RequestFailure.AUTH else RequestFailure.HTTP,
        error?.text("status") ?: root.text("error") ?: "api_error", error?.int("code")))
    return ReadResult(root)
}
