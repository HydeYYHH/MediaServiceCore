package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class ConnectionCloseTest {
    @Test fun closingWakesTheDefaultChatWaitAndPreventsAnotherRequest() {
        val entered = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, "{\"timedContinuationData\":{\"continuation\":\"next\",\"timeoutMs\":60000}}")
            }
        }
        val connection = ChatConnection(http = http)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit(Callable {
                worker.set(Thread.currentThread())
                connection.listen("first") { entered.countDown() }
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (worker.get().state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, worker.get().state)
            assertEquals("already_listening", connection.listen("other") {}.error!!.code)
            connection.close()
            assertTrue(result.get(2, TimeUnit.SECONDS).ok)
            assertEquals(1, calls)
        } finally { connection.close(); pool.shutdownNow() }
    }

    @Test fun closingDuringARequestDoesNotDeliverItsResponse() {
        lateinit var connection: ChatConnection
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { connection.close(); return HttpReply(200, "{}") }
        }
        connection = ChatConnection(http = http)
        assertTrue(connection.listen("key") { fail("callback after close") }.ok)
    }
}
