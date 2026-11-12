package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoungePairTest {
    @Test
    fun readsAScreenId() {
        assertTrue(screenIdUrl().contains("/api/lounge/pairing/generate_screen_id"))
        assertEquals("abc", screenId("  abc \n"))
        assertNull(screenId(" \n"))
    }

    @Test
    fun readsTheLoungeToken() {
        val query = loungeTokenQuery("abc")
        assertTrue(query.url.contains("/api/lounge/pairing/get_lounge_token_batch"))
        assertEquals("application/x-www-form-urlencoded", query.headers["Content-Type"])
        assertFalse(query.headers.containsKey("X-Youtube-Client-Name"))
        assertTrue(query.body.contains("screen_ids=abc"))
        val screen = loungeScreen("""{"screens":[{"screenId":"abc","loungeToken":"tok"}]}""")
        assertEquals("abc", screen?.screenId)
        assertEquals("tok", screen?.loungeToken)
    }

    @Test
    fun formatsThePairingCode() {
        val query = pairingQuery("tok", "abc", "Litube", "device-1")
        assertTrue(query.url.contains("/api/lounge/pairing/get_pairing_code?ctx=pair"))
        assertTrue(query.body.contains("lounge_token=tok"))
        assertTrue(query.body.contains("screen_id=abc"))
        assertTrue(query.body.contains("access_type=permanent"))
        assertTrue(query.body.contains("app=lb-v4"))
        assertTrue(query.body.contains("qr=1"))
        assertEquals("ABC DEF GHI JKL", pairingCode("""{"code":"ABCDEFGHIJKL"}"""))
    }

    @Test
    fun pairsThroughTheThreeSteps() {
        val posts = ArrayList<String>()
        val code = loadPairing("Litube", "device-1", { url ->
            assertTrue(url.contains("generate_screen_id"))
            "  abc \n"
        }) { query ->
            posts.add(query.url)
            if (posts.size == 1) Pair(200, """{"screens":[{"screenId":"abc","loungeToken":"tok"}]}""")
            else Pair(200, """{"code":"ABCDEFGHIJKL"}""")
        }
        assertEquals(2, posts.size)
        assertTrue(posts[0].contains("get_lounge_token_batch"))
        assertTrue(posts[1].contains("get_pairing_code"))
        assertEquals("ABC DEF GHI JKL", code)
    }

    @Test fun liveBareDecimalResponsePreservesLeadingZeroesAndCompletesBootstrap() {
        assertEquals("001 234 567 890", pairingCode(" 001234567890\n"))
        for (invalid in listOf("12345", "12345678901234567", "-123456", "1.23456e8", "123456 trailing", "<html>123456</html>", "１２３４５６"))
            assertNull(pairingCode(invalid))
        var calls = 0
        val code = loadPairing("MSC test", "test-device", { "screen" }) {
            calls++
            200 to if (calls == 1) """{"screens":[{"screenId":"screen","loungeToken":"test-token"}]}""" else "001234567890"
        }
        assertEquals(2, calls)
        assertEquals("001 234 567 890", code)
    }

    @Test
    fun blankNameSkipsPost() {
        var called = false
        val code = loadPairing(" \n", "device-1", { "abc" }) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertNull(code)
    }
}
