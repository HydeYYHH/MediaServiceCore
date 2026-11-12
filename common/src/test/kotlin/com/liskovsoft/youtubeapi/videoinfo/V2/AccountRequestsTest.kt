package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AccountRequestsTest {
    @Test fun cursorRetainsAccountAndRejectsSwitchLogoutAndForeignSession() {
        val session = AccountSession(RequestContext(webSession = WebSession("SAPISID=fixture"), generation = 10))
        val first = session.execute { ReadResult(listOf("first")) }.value!!
        assertEquals(10L, first.generation); assertEquals(listOf("first"), first.value)
        val cursor = first.cursor("opaque-secret-cursor")!!
        assertFalse(cursor.toString().contains("opaque-secret")); assertFalse(first.toString().contains("first"))
        assertNull(first.cursor(null)); assertNull(first.cursor(" "))
        assertEquals(listOf("second"), session.execute(cursor) { context, token ->
            assertEquals("opaque-secret-cursor", token); assertEquals(10L, context.generation)
            ReadResult(listOf("second"))
        }.value!!.value)
        assertEquals("foreign_session_cursor", AccountSession().execute(cursor) { _, _ -> fail("must not execute"); ReadResult(1) }.error?.code)
        session.clear()
        assertEquals("session_changed", session.execute(cursor) { _, _ -> fail("must not execute"); ReadResult(1) }.error?.code)
    }

    @Test fun inFlightResponseCannotBeDeliveredAfterLogoutAndErrorsArePreserved() {
        val session = AccountSession(RequestContext(generation = 2))
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        val result = AtomicReference<ReadResult<AccountResult<String>>>()
        val thread = Thread {
            result.set(session.execute {
                started.countDown(); check(finish.await(3, TimeUnit.SECONDS)); ReadResult("old-account-data")
            })
        }
        thread.start(); assertTrue(started.await(3, TimeUnit.SECONDS))
        session.clear(); finish.countDown(); thread.join(3000)
        assertFalse(thread.isAlive); assertNull(result.get().value)
        assertEquals(RequestFailure.CANCELLED, result.get().error?.kind)
        val failure = RequestError(RequestFailure.TRANSPORT, "timeout")
        assertSame(failure, session.execute<String> { ReadResult(error = failure) }.error)
        val page = session.execute { ReadResult("page") }.value!!
        assertSame(failure, session.execute(page.cursor("next")!!) { _, _ -> ReadResult<String>(error = failure) }.error)
    }
}
