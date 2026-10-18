package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class RequestContext(
    val accountId: String? = null, val accessToken: String? = null,
    val visitor: String? = null, val language: String = "en", val region: String = "US",
    val generation: Long = 0, val onBehalfOfUser: String? = null
) {
    fun anonymous() = copy(accountId = null, accessToken = null, onBehalfOfUser = null)
    internal fun scope() = listOf(accountId, visitor, generation.toString(), onBehalfOfUser).joinToString("\u0000") { if (it == null) "-" else it.length.toString() + ":" + it }
    override fun toString() = "RequestContext(language=$language, region=$region, generation=$generation)"
}
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
fun PlayerQuery.request() = HttpRequest("POST", url, headers, body)
fun legacyPost(http: HttpClient): (PlayerQuery) -> Pair<Int, String> = { query ->
    http.execute(query.request()).let { it.status to it.body }
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
internal fun Json.encode(): String = when (this) {
    Json.Null -> "null"
    is Json.Bool -> value.toString()
    is Json.Str -> jsonQuote(value)
    is Json.Num -> text
    is Json.Arr -> items.joinToString(",", "[", "]") { it.encode() }
    is Json.Obj -> fields.entries.joinToString(",", "{", "}") { jsonQuote(it.key) + ":" + it.value.encode() }
}
internal fun payload(vararg fields: Pair<String, String?>) = fields.filter { it.second != null }.joinToString(",") { jsonQuote(it.first) + ":" + jsonQuote(it.second!!) }
internal fun contextualQuery(query: PlayerQuery, context: RequestContext): PlayerQuery {
    val root = jsonObject(query.body) ?: return query
    val client = root.path("context", "client") ?: return query
    val clientFields = client.fields + mapOf("hl" to Json.Str(context.language), "gl" to Json.Str(context.region))
    val contextFields = root.obj("context")!!.fields + ("client" to Json.Obj(clientFields))
    val headers = query.headers.toMutableMap()
    val origin = try { URI(query.url) } catch (_: Exception) { null }
    val api = origin?.scheme == "https" && origin.host == "www.youtube.com" && origin.path.startsWith("/youtubei/")
    headers.keys.filter { it.equals("Cookie",true) || it.equals("Authorization",true) }.toList().forEach { headers.remove(it) }
    if (api && context.accessToken != null) headers["Authorization"] = "Bearer " + context.accessToken
    if (context.visitor != null) headers["X-Goog-Visitor-Id"] = context.visitor
    val user = context.onBehalfOfUser?.let { Json.Obj(mapOf("onBehalfOfUser" to Json.Str(it))) }
    val finalContext = if (user == null) contextFields else contextFields + ("user" to user)
    return query.copy(headers = headers, body = Json.Obj(root.fields + ("context" to Json.Obj(finalContext))).encode())
}
internal fun youtubeQuery(endpoint: String, fields: String, context: RequestContext): PlayerQuery {
    val template = if (context.accessToken == null) webQuery(endpoint, fields, context.visitor) else {
        val player = playerQuery(PlayerClient.TV, "context", context.visitor)
        val client = jsonObject(player.body)!!.obj("context")!!
        PlayerQuery(endpoint, player.headers, "{\"context\":${client.encode()}${if (fields.isEmpty()) "" else "," + fields}}")
    }
    return contextualQuery(template, context)
}
internal const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/"
