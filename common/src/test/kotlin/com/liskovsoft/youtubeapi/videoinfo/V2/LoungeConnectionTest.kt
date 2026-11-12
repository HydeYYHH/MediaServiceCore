package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class LoungeConnectionTest {
    @Test fun readsFragmentedFramesAndDropsDuplicateCommands() {
        val frames=LoungeFrames()
        val body="""[[1,["noop"]],[2,["seekTo",{"newTime":"12.5"}]]]"""
        val wire=body.length.toString()+"\n"+body
        val commands=wire.flatMap { frames.push(it.toString()) }
        assertEquals("seekTo",commands.single().type);assertEquals(2L,frames.lastId)
        assertTrue(frames.push(wire).isEmpty());frames.finish()
    }
    @Test fun reconnectsWithANewTokenAndClosesTheInjectedStream() {
        var tokens=0;var binds=0;var closed=false
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            binds++
            return if(binds==1) HttpReply(403,"") else HttpReply(200,"[[0,[\"c\",\"sid\"]],[1,[\"S\",\"gsid\"]]]")
        }}
        val transport=object:LoungeTransport { override fun open(request:HttpRequest):java.io.Reader {
            assertEquals("token2",urlParameter(request.url,"loungeIdToken"))
            val body="[[2,[\"play\"]]]"
            return object:StringReader(body.length.toString()+"\n"+body){override fun close(){closed=true;super.close()}}
        }}
        val connection=LoungeConnection("screen","device",{tokens++;LoungeScreen("screen","token$tokens")},http,transport,wait={})
        assertTrue(connection.listen { assertEquals("play",it.type);connection.close() }.ok)
        assertEquals(2,tokens);assertTrue(closed)
    }
    @Test fun boundsConsecutiveConnectionFailures() {
        val http=object:HttpClient { override fun execute(request:HttpRequest)=HttpReply(503,"") }
        val transport=object:LoungeTransport { override fun open(request:HttpRequest)=StringReader("") }
        val c=LoungeConnection("s","d",{LoungeScreen("s","t")},http,transport,wait={},maxReconnects=1)
        assertEquals("lounge_reconnect_exhausted",c.listen{}.error!!.code)
    }

    @Test fun androidClosedReaderStateReconnectsAndRepeatedCloseCannotEscape() {
        var binds = 0; var opens = 0; var closes = 0; var received = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            binds++
            return HttpReply(200, """[[0,["c","sid$binds"]],[1,["S","gsid$binds"]]]""")
        } }
        val transport = object : LoungeTransport { override fun open(request: HttpRequest): java.io.Reader {
            val first = ++opens == 1
            val body = """[[2,["play"]]]"""
            val input = StringReader("${body.length}\n$body")
            return object : java.io.Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    if (first) throw IllegalStateException("reader already closed")
                    return input.read(buffer, offset, length)
                }
                override fun close() { closes++; input.close(); throw IllegalStateException("reader already closed") }
            }
        } }
        val connection = LoungeConnection("screen", "device", { LoungeScreen("screen", "token") }, http, transport, wait = {}, maxReconnects = 1)
        assertTrue(connection.listen { received++; assertEquals("play", it.type); connection.close() }.ok)
        connection.close()
        assertEquals(2, binds); assertEquals(2, opens); assertEquals(1, received)
        assertTrue(closes >= 2); assertNull(connection.identity())
    }

    @Test fun closingImmediatelyRejectsReportsWhileTheReaderIsStillBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binds = AtomicInteger()
        val reports = AtomicInteger()
        val closes = AtomicInteger()
        val received = AtomicInteger()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            if (request.body == "count=0") binds.incrementAndGet() else reports.incrementAndGet()
            return HttpReply(200, """[[0,["c","session"]],[1,["S","g-session"]]]""")
        } }
        val transport = object : LoungeTransport { override fun open(request: HttpRequest): java.io.Reader =
            object : java.io.Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    release.await()
                    // A read already in progress can finish after close. The
                    // internal identity must remain safe for its final frame.
                    val body = """[[2,["play"]]]"""
                    val frame = "${body.length}\n$body"
                    frame.toCharArray().copyInto(buffer, offset)
                    return frame.length
                }
                override fun close() { closes.incrementAndGet() }
            }
        }
        val connection = LoungeConnection("screen", "device", { LoungeScreen("screen", "token") }, http, transport)
        val reporter = LoungeReporter(connection, http)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val listening = pool.submit(Callable { connection.listen { received.incrementAndGet() } })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotNull(connection.identity())
            connection.close()
            assertFalse("The test must exercise the window before listener cleanup", listening.isDone)
            assertNull(connection.identity())
            assertEquals("lounge_not_connected", reporter.volume(40).error?.code)
            assertEquals(1, binds.get())
            assertEquals(0, reports.get())
            release.countDown()
            assertTrue(listening.get(2, TimeUnit.SECONDS).ok)
            assertEquals(0, received.get())
            assertNull(connection.identity())
            assertEquals(0, reports.get())
            assertTrue(closes.get() >= 1)
        } finally {
            release.countDown()
            connection.close()
            pool.shutdownNow()
        }
    }

    @Test fun aLostStreamRejectsReportsDuringBackoffAndNewBindRestoresOnlyItsIdentity() {
        var binds = 0; var opens = 0; var reports = 0; var waits = 0; var received = 0
        var reportSession: String? = null
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            if (request.body == "count=0") {
                binds++
                return HttpReply(200, """[[0,["c","session$binds"]],[1,["S","g-session$binds"]]]""")
            }
            reports++
            reportSession = urlParameter(request.url, "SID")
            return HttpReply(200, "")
        } }
        val transport = object : LoungeTransport { override fun open(request: HttpRequest): java.io.Reader {
            if (++opens == 1) return object : java.io.Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw IOException("lost_stream")
                override fun close() { }
            }
            val body = """[[2,["play"]]]"""
            return StringReader("${body.length}\n$body")
        } }
        lateinit var connection: LoungeConnection
        lateinit var reporter: LoungeReporter
        connection = LoungeConnection("screen", "device", { LoungeScreen("screen", "token") }, http, transport, wait = {
            waits++
            assertEquals(1000L, it)
            assertEquals(1, binds)
            assertNull(connection.identity())
            assertEquals("lounge_not_connected", reporter.volume(40).error?.code)
            assertEquals(0, reports)
        }, maxReconnects = 1)
        reporter = LoungeReporter(connection, http)
        try {
            assertTrue(connection.listen {
                received++
                assertEquals("play", it.type)
                assertEquals("session2", connection.identity()?.session?.sessionId)
                assertTrue(reporter.volume(50).ok)
                connection.close()
            }.ok)
            assertEquals(2, binds); assertEquals(2, opens); assertEquals(1, waits)
            assertEquals(1, received); assertEquals(1, reports); assertEquals("session2", reportSession)
            assertNull(connection.identity())
        } finally { connection.close() }
    }
}
