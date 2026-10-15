package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistEditTest {
    private val choices = """
        {"contents":[{"addToPlaylistRenderer":{"playlists":[
          {"playlistAddToOptionRenderer":{"playlistId":"PLone","title":{"simpleText":"Later"},"containsSelectedVideos":"ALL"}},
          {"playlistAddToOptionRenderer":{"playlistId":"PLtwo","title":{"runs":[{"text":"My "},{"text":"list"}]},"containsSelectedVideos":"NONE"}}
        ]}}]}
    """

    @Test
    fun readsPlaylistChoices() {
        val query = playlistChoicesQuery("dQw4w9WgXcQ", "visitor-1")
        assertTrue(query.url.contains("/playlist/get_add_to_playlist"))
        assertTrue(query.body.contains("\"videoIds\":[\"dQw4w9WgXcQ\"]"))
        assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
        val parsed = playlistChoices(choices)
        assertEquals(2, parsed.size)
        assertEquals("PLone", parsed[0].id)
        assertEquals("Later", parsed[0].title)
        assertTrue(parsed[0].contains)
        assertEquals("PLtwo", parsed[1].id)
        assertEquals("My list", parsed[1].title)
        assertFalse(parsed[1].contains)
    }

    @Test
    fun addsAVideo() {
        val wrote = addVideo("PLone", "dQw4w9WgXcQ", null) { query ->
            assertTrue(query.url.contains("/browse/edit_playlist"))
            assertTrue(query.body.contains("\"playlistId\":\"PLone\""))
            assertTrue(query.body.contains("\"addedVideoId\":\"dQw4w9WgXcQ\""))
            assertTrue(query.body.contains("\"action\":\"ACTION_ADD_VIDEO\""))
            Pair(200, "")
        }
        assertTrue(wrote.ok)
    }

    @Test
    fun removesAVideo() {
        val wrote = removeVideo("PLone", "dQw4w9WgXcQ", null) { query ->
            assertTrue(query.body.contains("\"removedVideoId\":\"dQw4w9WgXcQ\""))
            assertTrue(query.body.contains("\"action\":\"ACTION_REMOVE_VIDEO_BY_VIDEO_ID\""))
            Pair(200, "")
        }
        assertTrue(wrote.ok)
    }

    @Test
    fun renamesAndEscapesQuotes() {
        val wrote = renamePlaylist("PLone", "say \"hi\"", null) { query ->
            assertTrue(query.body.contains("\"playlistName\":\"say \\\"hi\\\"\""))
            assertTrue(query.body.contains("\"action\":\"ACTION_SET_PLAYLIST_NAME\""))
            Pair(200, "")
        }
        assertTrue(wrote.ok)
    }

    @Test
    fun ordersAPlaylist() {
        val wrote = orderPlaylist("PLone", 3, null) { query ->
            assertTrue(query.body.contains("\"playlistVideoOrder\":\"3\""))
            assertTrue(query.body.contains("\"action\":\"ACTION_SET_PLAYLIST_VIDEO_ORDER\""))
            Pair(200, "")
        }
        assertTrue(wrote.ok)
    }

    @Test
    fun createsAPlaylistAndReadsItsId() {
        val wrote = createPlaylist("Later", "dQw4w9WgXcQ", null) { query ->
            assertTrue(query.url.contains("/playlist/create?"))
            assertTrue(query.body.contains("\"title\":\"Later\""))
            assertTrue(query.body.contains("\"videoIds\":[\"dQw4w9WgXcQ\"]"))
            Pair(200, """{"playlistId":"PLnew"}""")
        }
        assertTrue(wrote.ok)
        assertEquals("PLnew", wrote.playlistId)
    }

    @Test
    fun deletesAPlaylist() {
        val wrote = deletePlaylist("PLone", null) { query ->
            assertTrue(query.url.contains("/playlist/delete"))
            assertTrue(query.body.contains("\"playlistId\":\"PLone\""))
            Pair(200, "")
        }
        assertTrue(wrote.ok)
        assertNull(wrote.playlistId)
    }

    @Test
    fun savesAndForgetsAForeignPlaylist() {
        val saved = savePlaylist("PLforeign", null) { query ->
            assertTrue(query.url.contains("/like/like"))
            assertFalse(query.url.contains("removelike"))
            assertTrue(query.body.contains("\"target\":{\"playlistId\":\"PLforeign\"}"))
            Pair(200, "")
        }
        assertTrue(saved.ok)
        val forgotten = forgetPlaylist("PLforeign", null) { query ->
            assertTrue(query.url.contains("/like/removelike"))
            Pair(200, "")
        }
        assertTrue(forgotten.ok)
    }

    @Test
    fun badIdOrBlankTitleSkipsPost() {
        var called = false
        val post = { _: PlayerQuery ->
            called = true
            Pair(200, "")
        }
        assertFalse(addVideo("bad id", "dQw4w9WgXcQ", null, post).ok)
        assertFalse(called)
        assertFalse(renamePlaylist("PLone", " \n", null, post).ok)
        assertFalse(called)
        assertFalse(createPlaylist("  ", null, null, post).ok)
        assertFalse(called)
        val query = playlistChoicesQuery("bad id", null)
        assertTrue(query.url.isEmpty())
    }
}
