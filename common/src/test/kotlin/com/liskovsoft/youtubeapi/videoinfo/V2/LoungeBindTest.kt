package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoungeBindTest {
    @Test
    fun readsTheBindQuery() {
        val query = bindQuery("Living Room", "device-1", "tok")
        assertTrue(query.url.contains("/api/lounge/bc/bind"))
        assertTrue(query.url.contains("RID=1337"))
        assertTrue(query.url.contains("app=lb-v4"))
        assertTrue(query.url.contains("AID=42"))
        assertTrue(query.url.contains("zx=xxxxxxxxxxxx"))
        assertTrue(query.url.contains("name=Living%20Room"))
        assertEquals("application/x-www-form-urlencoded", query.headers["Content-Type"])
        assertFalse(query.headers.containsKey("X-Youtube-Client-Name"))
        assertEquals("count=0", query.body)
    }

    @Test
    fun readsSessionIds() {
        val body = "42\n" +
            "[[0,[\"c\",\"sid-1\"]],[1,[\"S\",\"gs-1\"]],[2,[\"loungeStatus\",{\"devices\":\"[]\"}]]]"
        val session = loungeSession(body, "Living Room", "device-1", "tok")
        assertEquals("sid-1", session?.sessionId)
        assertEquals("gs-1", session?.gSessionId)
        val listen = session?.listenUrl ?: ""
        assertTrue(listen.contains("RID=rpc"))
        assertTrue(listen.contains("CI=0"))
        assertTrue(listen.contains("SID=sid-1"))
        assertTrue(listen.contains("gsessionid=gs-1"))
        assertTrue(listen.contains("name=Living%20Room"))
        assertFalse(listen.contains("RID=1337"))
    }

    @Test
    fun blankTokenSkipsPost() {
        var called = false
        val session = loadSession("Living Room", "device-1", " \n") {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertNull(session)
    }
}
