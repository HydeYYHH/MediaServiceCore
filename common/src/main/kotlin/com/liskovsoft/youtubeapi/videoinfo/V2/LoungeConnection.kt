package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.Reader
import java.io.IOException

interface LoungeTransport { fun open(request: HttpRequest): Reader }
data class LoungeIdentity(val screenName: String, val deviceId: String, val screen: LoungeScreen, val session: LoungeSession,
    val acknowledged: Long, val requestId: Int) {
    override fun toString() = "LoungeIdentity(acknowledged=$acknowledged, requestId=$requestId)"
}
internal class LoungeFrames(initialId: Long = -1, private val maxChars: Int = 1024 * 1024) {
    private val pending = StringBuilder()
    var lastId = initialId
        private set
    fun push(chunk: String): List<LoungeCommand> {
        pending.append(chunk)
        if (pending.length > maxChars) throw IOException("lounge_frame_too_large")
        val commands = ArrayList<LoungeCommand>()
        while (pending.isNotEmpty()) {
            while (pending.isNotEmpty() && pending[0] in "\r\n") pending.deleteCharAt(0)
            if (pending.isEmpty()) break
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val size = pending.substring(0,newline).trim().toIntOrNull() ?: throw IOException("invalid_lounge_frame")
            if (size < 0 || size > maxChars) throw IOException("invalid_lounge_length")
            if (pending.length - newline - 1 < size) break
            val frame = pending.substring(newline+1,newline+1+size)
            pending.delete(0,newline+1+size)
            val rows = jsonValue(frame) as? Json.Arr ?: throw IOException("invalid_lounge_json")
            for (row in rows.items) {
                val values = row as? Json.Arr ?: continue
                val id = (values.items.firstOrNull() as? Json.Num)?.text?.toLongOrNull() ?: continue
                if (id <= lastId) continue
                val command = loungeCommands(Json.Arr(listOf(row)).encode()).firstOrNull()
                lastId = id
                if (command != null && command.type !in listOf("noop","c","S")) commands.add(command)
            }
        }
        return commands
    }
    fun finish() { if (pending.toString().isNotBlank()) throw IOException("truncated_lounge_frame") }
}
class LoungeConnection(private val screenName: String, private val deviceId: String, private val screen: () -> LoungeScreen,
    private val http: HttpClient, private val transport: LoungeTransport, private val wait: (Long) -> Unit = Thread::sleep,
    private val maxReconnects: Int = 3) : java.io.Closeable {
    @Volatile private var closed = false
    @Volatile private var reader: Reader? = null
    @Volatile private var current: LoungeIdentity? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    private val requests = java.util.concurrent.atomic.AtomicInteger()
    init { require(screenName.isNotBlank() && deviceId.isNotBlank() && maxReconnects >= 0) }
    fun identity() = current
    internal fun nextRequestId() = requests.incrementAndGet()
    override fun close() { closed = true; try { reader?.close() } catch (_: IOException) {} }
    fun listen(receive: (LoungeCommand) -> Unit): ReadResult<Boolean> {
        if (!running.compareAndSet(false,true)) return ReadResult(error=RequestError(RequestFailure.INVALID,"already_listening"))
        var failures = 0
        try {
            while (!closed && !Thread.currentThread().isInterrupted) {
                if (failures > maxReconnects) return ReadResult(error=RequestError(RequestFailure.TRANSPORT,"lounge_reconnect_exhausted"))
                if (failures > 0) wait(minOf(5000L, failures * 1000L))
                if (closed) break
                try {
                    val screen = screen()
                    val bind = bindQuery(screenName,deviceId,screen.loungeToken)
                    val url = urlWithParameter(urlWithParameter(bind.url,"RID",requests.incrementAndGet().toString()),"AID","0")
                    val response = requestBody(bind.copy(url=url).request(),http)
                    if (!response.ok) { failures++; continue }
                    val session = loungeSession(response.value!!,screenName,deviceId,screen.loungeToken)
                    if (session == null) { failures++; continue }
                    val text = response.value.trim()
                    val firstLine = text.substringBefore('\n').trim()
                    val frame = if (firstLine.isNotEmpty() && firstLine.all { it in '0'..'9' }) text.substringAfter('\n') else text
                    val frames = LoungeFrames()
                    val commands = frames.push(frame.length.toString() + "\n" + frame)
                    frames.finish()
                    current = LoungeIdentity(screenName,deviceId,screen,session,frames.lastId,requests.get())
                    for (command in commands) { if (!closed) receive(command) }
                    if (closed) break
                    val listenUrl = urlWithParameter(session.listenUrl,"AID",maxOf(0,frames.lastId).toString())
                    val active = transport.open(HttpRequest("GET",listenUrl))
                    reader = active
                    if (closed) { active.close(); break }
                    active.use {
                        val buffer = CharArray(4096)
                        while (!closed) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            val commands = frames.push(String(buffer,0,count))
                            current = current!!.copy(acknowledged=frames.lastId)
                            if (commands.isNotEmpty()) failures = 0
                            for (command in commands) { if (!closed) receive(command) }
                        }
                        if (!closed) frames.finish()
                    }
                    reader = null
                    if (!closed) failures++
                } catch (_: IOException) { if (!closed) failures++ }
            }
            return if (Thread.currentThread().isInterrupted) ReadResult(error=RequestError(RequestFailure.CANCELLED,"cancelled")) else ReadResult(true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error=RequestError(RequestFailure.CANCELLED,"cancelled"))
        } finally { try { reader?.close() } catch (_: IOException) {}; reader=null; current=null; running.set(false) }
    }
}
