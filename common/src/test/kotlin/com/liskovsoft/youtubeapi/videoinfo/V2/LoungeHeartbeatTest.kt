package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class LoungeHeartbeatTest {
    @Test fun countsANoopFrameAsAHealthyConnection() {
        var binds = 0
        var streams = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                binds++
                return if (binds == 1) HttpReply(503, "") else HttpReply(200, "[[0,[\"c\",\"sid\"]],[1,[\"S\",\"gsid\"]]]")
            }
        }
        val transport = object : LoungeTransport {
            override fun open(request: HttpRequest): java.io.Reader {
                streams++
                val frame = if (streams == 1) "[[2,[\"noop\"]]]" else "[[2,[\"play\"]]]"
                return StringReader(frame.length.toString() + "\n" + frame)
            }
        }
        val connection = LoungeConnection("s", "d", { LoungeScreen("s", "t") }, http, transport, wait = {}, maxReconnects = 1)
        assertTrue(connection.listen { assertEquals("play", it.type); connection.close() }.ok)
        assertEquals(3, binds)
        assertEquals(2, streams)
    }
}
