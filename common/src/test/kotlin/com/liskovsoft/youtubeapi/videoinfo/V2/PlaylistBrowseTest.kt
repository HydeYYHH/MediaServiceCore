package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBrowseTest {
    private val page = """
        {"header":{"playlistHeaderRenderer":{
          "title":{"simpleText":"Later"},
          "ownerText":{"runs":[{"text":"Ada"}]}
        }},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[{"playlistVideoListRenderer":{"contents":[
            {"playlistVideoRenderer":{
              "videoId":"dQw4w9WgXcQ",
              "title":{"simpleText":"Song"},
              "shortBylineText":{"simpleText":"Ada"},
              "lengthText":{"simpleText":"3:33"},
              "index":{"simpleText":"1"},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/a/hqdefault.jpg"}]}
            }},
            {"playlistVideoRenderer":{"title":{"simpleText":"skip me"}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}
          ]}}]}}
        ]}}}}]}}}
    """
    private val more = """
        {"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
          {"playlistVideoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Next"},"index":0}}
        ]}}]}
    """

    @Test
    fun loadsTheFirstPage() {
        val parsed = loadPlaylist("PLone", "visitor-1") { query ->
            assertTrue(query.url.contains("/youtubei/v1/browse"))
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            assertTrue(query.body.contains("\"browseId\":\"VLPLone\""))
            assertFalse(query.body.contains("continuation"))
            Pair(200, page)
        }
        assertEquals("Later", parsed.title)
        assertEquals("Ada", parsed.owner)
        assertEquals("NEXT", parsed.nextKey)
        assertEquals(1, parsed.videos.size)
        val video = parsed.videos[0]
        assertEquals("dQw4w9WgXcQ", video.videoId)
        assertEquals("Song", video.title)
        assertEquals("Ada", video.author)
        assertEquals("3:33", video.length)
        assertEquals("1", video.index)
        assertEquals("https://i.ytimg.com/vi/a/hqdefault.jpg", video.thumbnail)
    }

    @Test
    fun continuationOmitsTheBrowseId() {
        val query = playlistBrowseQuery("PLone", "NEXT", null)
        assertTrue(query.body.contains("\"continuation\":\"NEXT\""))
        assertFalse(query.body.contains("browseId"))
        val parsed = playlistPage(more)
        assertNull(parsed.title)
        assertNull(parsed.owner)
        assertEquals(1, parsed.videos.size)
        assertEquals("abcdefghijk", parsed.videos[0].videoId)
        assertEquals("Next", parsed.videos[0].title)
        assertEquals("0", parsed.videos[0].index)
    }

    @Test
    fun badPlaylistIdSkipsPost() {
        var called = false
        val parsed = loadPlaylist("bad id", null) {
            called = true
            Pair(200, page)
        }
        assertFalse(called)
        assertTrue(parsed.videos.isEmpty())
        assertNull(parsed.nextKey)
    }
}
