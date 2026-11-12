package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentReplayTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")).readText()

    @Test fun modernPlaylistRetainsItsHeaderAndAllEntries() {
        val page = playlistPage(fixture("playlist-detail.json"))
        assertEquals("30", page.title)
        assertEquals("Adele", page.owner)
        assertEquals(13, page.videos.size)
        assertEquals("EFIfRhk8NRc", page.videos.first().videoId)
        assertEquals("Adele - Strangers By Nature (Official Lyric Video)", page.videos.first().title)
        assertEquals("Adele", page.videos.first().author)
        assertEquals("3:03", page.videos.first().length)
        assertTrue(page.videos.all { !it.thumbnail.isNullOrBlank() })
    }

    @Test fun playlistEntriesKeepRepeatedVideosButIgnoreSecondaryRecommendations() {
        val card = """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"video_one","metadata":{"lockupMetadataViewModel":{"title":{"content":"Entry"}}},"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"video_one","playlistSetVideoId":"entry_one","index":0}}}}}}}"""
        val body = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[$card,$card]}}}}],"secondaryContents":$card}}}"""
        val page = playlistPage(body)
        assertEquals(2, page.videos.size)
        assertEquals("entry_one", page.videos.first().setVideoId)
        assertEquals("1", page.videos.first().index)
    }

    @Test fun carouselMetadataStaysBoundToItsOwnTile() {
        val page = browsePage(fixture("tv-music.json"))
        val video = page.items.single { it.id == "Rt9tW3cMLhI" }
        assertEquals("Olivia Rodrigo - stupid song (Official Music Video)", video.title)
        assertEquals("Olivia Rodrigo", video.author)
        assertEquals("41M views", video.viewCount)
        assertFalse(video.authorThumbnail.isNullOrBlank())
        assertEquals("3:37", video.duration)
        assertTrue(page.items.filter { it.kind == "video" }.all { !it.title.isNullOrBlank() && !it.author.isNullOrBlank() })
    }
}
