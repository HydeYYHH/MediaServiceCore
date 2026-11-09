package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String = "")
data class HttpReply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}
enum class RequestFailure { INVALID, AUTH, HTTP, VERIFICATION, TRANSPORT, PARSE, CANCELLED, UNSUPPORTED }
data class RequestError(
    val kind: RequestFailure, val code: String, val status: Int? = null, val retryAfterMs: Long? = null,
    /** Challenge redirect target for [RequestFailure.VERIFICATION], otherwise unused. */
    val detail: String? = null,
    /** Exception class and a bounded, non-sensitive transport reason. */
    val cause: String? = null,
)
/** A deliberate request policy failure must survive transport normalization. */
open class RequestException(val failure: RequestError) : IOException(failure.code)
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
                val out = ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    if (out.size() + count > maxBodyBytes)
                        throw RequestException(RequestError(RequestFailure.PARSE, "response_too_large"))
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
    val reply = try { http.execute(request) } catch (error: Exception) {
        val transport = transportError(error)
        return ReadResult(error = transport)
    }
    if (reply.status in listOf(301, 302, 303, 307, 308)) {
        // Redirects are not followed; a bounce to /sorry is the traffic challenge,
        // an ordinary redirect is just a failed request.
        reply.header("Location")?.let { location ->
            runCatching { URI(request.url).resolve(location).toString() }.getOrNull()
        }?.takeIf(::isTrafficChallengeLocation)?.let { location ->
            return ReadResult(error = RequestError(RequestFailure.VERIFICATION, "traffic_challenge", reply.status, detail = location))
        }
    }
    if (reply.status !in 200..299) return ReadResult(error = RequestError(
        if (reply.status == 401 || reply.status == 403) RequestFailure.AUTH else RequestFailure.HTTP,
        "http_${reply.status}", reply.status, reply.header("Retry-After")?.toLongOrNull()?.times(1000)))
    return ReadResult(reply.body)
}

/** The traffic challenge lives on Google/YouTube hosts under `/sorry`; everything else is not one. */
private fun isTrafficChallengeLocation(location: String): Boolean = try {
    val uri = URI(location)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        (host == "www.google.com" || host == "google.com" || host.endsWith(".youtube.com") || host == "youtube.com") &&
        (uri.path == "/sorry" || uri.path.orEmpty().startsWith("/sorry/"))
} catch (_: Exception) { false }

/**
 * Classifies a transport exception into a [RequestError] instead of collapsing every
 * failure into one opaque code. DNS, connect, TLS, timeout, reset and cancellation
 * stay distinguishable so callers can decide between retry, auth repair or reporting.
 */
fun transportError(error: Exception): RequestError {
    if (error is RequestException) return error.failure
    if (error is InterruptedException) {
        Thread.currentThread().interrupt()
        return RequestError(RequestFailure.CANCELLED, "cancelled", cause = describe(error))
    }
    val message = error.message?.lowercase().orEmpty()
    if (message == "canceled" || message == "cancelled" || message == "session_changed_or_cancelled") {
        return RequestError(RequestFailure.CANCELLED, "cancelled", cause = describe(error))
    }
    val code = when (error) {
        is UnknownHostException -> "transport_dns"
        is ConnectException -> "transport_connect"
        is SSLException -> "transport_tls"
        is SocketTimeoutException -> "transport_timeout"
        is NoRouteToHostException, is PortUnreachableException -> "transport_unreachable"
        is HttpRetryException, is ProtocolException -> "transport_protocol"
        is SocketException -> "transport_socket"
        is IOException -> "transport_io"
        else -> "transport_failed"
    }
    return RequestError(RequestFailure.TRANSPORT, code, cause = describe(error))
}

/** Raw exception messages may contain credentials or signed URLs. */
private fun describe(error: Exception): String {
    val message = error.message?.lowercase().orEmpty()
    val reason = listOf("connection reset", "refused", "timed out", "unexpected end of stream", "socket closed")
        .firstOrNull(message::contains)
    return listOfNotNull(error.javaClass.simpleName, reason).joinToString(": ").take(160)
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
