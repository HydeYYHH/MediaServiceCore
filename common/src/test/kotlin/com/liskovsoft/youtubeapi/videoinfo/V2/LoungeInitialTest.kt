package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class LoungeInitialTest {
    @Test fun deliversBindCommandsBeforeOpeningTheStream() {
        val rows = "[\n[0,[\"c\",\"sid\"]],[1,[\"S\",\"gsid\"]],[2,[\"play\"]]\n]"
        for (body in listOf(rows, rows.length.toString() + "\n" + rows)) {
            var opened = false
            val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
            val transport = object : LoungeTransport {
                override fun open(request: HttpRequest): java.io.Reader { opened = true; return StringReader("") }
            }
            val connection = LoungeConnection("screen", "device", { LoungeScreen("screen", "token") }, http, transport)
            val commands = ArrayList<String>()
            assertTrue(connection.listen {
                commands.add(it.type)
                assertEquals(2L, connection.identity()!!.acknowledged)
                connection.close()
            }.ok)
            assertEquals(listOf("play"), commands)
            assertFalse(opened)
        }
    }

    @Test fun doesNotRepeatAnAcknowledgedBindCommand() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, "[[0,[\"c\",\"sid\"]],[1,[\"S\",\"gsid\"]],[2,[\"play\"]]]")
        }
        val transport = object : LoungeTransport {
            override fun open(request: HttpRequest): java.io.Reader {
                assertEquals("2", urlParameter(request.url, "AID"))
                val rows = "[[2,[\"play\"]],[3,[\"pause\"]]]"
                return StringReader(rows.length.toString() + "\n" + rows)
            }
        }
        val connection = LoungeConnection("s", "d", { LoungeScreen("s", "t") }, http, transport)
        val commands = ArrayList<String>()
        connection.listen { commands.add(it.type); if (it.type == "pause") connection.close() }
        assertEquals(listOf("play", "pause"), commands)
    }
}
