package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerScriptTest {
    @Test
    fun prefixesASitePath() {
        val page = """{"jsUrl":"/s/player/e12fbea4/player_ias.vflset/en_US/base.js","jsUrl":"/s/player/other/base.js"}"""
        assertEquals(
            "https://www.youtube.com/s/player/e12fbea4/player_ias.vflset/en_US/base.js",
            playerScriptUrl(page)
        )
    }

    @Test
    fun unescapesSlashesBeforePrefixing() {
        val page = """"jsUrl":"\/s\/player\/abc\/base.js""""
        assertEquals("https://www.youtube.com/s/player/abc/base.js", playerScriptUrl(page))
    }

    @Test
    fun keepsAnAbsoluteUrl() {
        val page = """"jsUrl":"https://www.youtube.com/s/player/abc/base.js""""
        assertEquals("https://www.youtube.com/s/player/abc/base.js", playerScriptUrl(page))
    }

    @Test
    fun missingOrUnusableIsEmpty() {
        assertNull(playerScriptUrl("no script here"))
        assertNull(playerScriptUrl("""{"jsUrl":""}"""))
        assertNull(playerScriptUrl(""""jsUrl":"player/base.js"""""))
    }
}
