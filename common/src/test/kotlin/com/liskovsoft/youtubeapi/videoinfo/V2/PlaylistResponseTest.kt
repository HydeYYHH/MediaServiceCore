package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistResponseTest {
    @Test fun readsThePrimaryColumnAndPreservesRepeatedEntries() {
        val page = playlistPage("""{"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{
          "twoColumnRenderer":{
            "leftColumn":{"entityMetadataRenderer":{"title":{"simpleText":"Watch later"},"bylines":[{
              "lineRenderer":{"items":[{"lineItemRenderer":{"badge":{}}},{"lineItemRenderer":{"text":{"simpleText":"Ada"}}}]}
            }]},"compactVideoRenderer":{"videoId":"left-video"}},
            "rightColumn":{"playlistVideoListRenderer":{"contents":[
              {"tileRenderer":{"metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Film"},"lines":[{
                "lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"Author"}}}]}
              }]}},"header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.org/film.jpg"}]}}},
                "onSelectCommand":{"watchEndpoint":{"videoId":"same","index":0,"playlistSetVideoId":"first"}}}},
              {"tileRenderer":{"metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Film again"}}},
                "onSelectCommand":{"watchEndpoint":{"videoId":"same","index":1,"playlistSetVideoId":"second"}}}},
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","onSelectCommand":{"browseEndpoint":{"browseId":"VLother"}}}}
            ],"continuations":[{"nextContinuationData":{"continuation":"ITEMS"}}]},
              "secondaryContents":{"compactVideoRenderer":{"videoId":"recommended"}}}
          }
        }}}}}}""")
        assertEquals("Watch later", page.title)
        assertEquals("Ada", page.owner)
        assertEquals(listOf("same", "same"), page.videos.map { it.videoId })
        assertEquals(listOf("first", "second"), page.videos.map { it.setVideoId })
        assertEquals(listOf("1", "2"), page.videos.map { it.index })
        assertEquals("Author", page.videos.first().author)
        assertEquals("https://example.org/film.jpg", page.videos.first().thumbnail)
        assertEquals("ITEMS", page.nextKey)
    }

    @Test fun recognizesAnEmptyPlaylistWithoutInventingItems() {
        val page = playlistPage("""{"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{
          "twoColumnRenderer":{"leftColumn":{"entityMetadataRenderer":{"title":{"simpleText":"Liked videos"}}},
            "rightColumn":{"playlistVideoListRenderer":{"contents":[]}}}
        }}}}}}""")
        assertEquals("Liked videos", page.title)
        assertTrue(page.videos.isEmpty())
        assertNull(page.nextKey)
        assertNull(playlistPage("""{"unexpected":{}}""").title)
    }

    @Test fun readsAContinuationAndItsOwnCursor() {
        val page = playlistPage("""{"continuationContents":{"playlistVideoListContinuation":{"contents":[{
          "tileRenderer":{"metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Continued"}}},
            "onSelectCommand":{"watchEndpoint":{"videoId":"next","playlistSetVideoId":"entry"}}}
        }],"continuations":[{"nextContinuationData":{"continuation":"MORE"}}]}}}""")
        assertEquals(listOf("next"), page.videos.map { it.videoId })
        assertEquals("entry", page.videos.single().setVideoId)
        assertEquals("MORE", page.nextKey)
        assertNull(page.title)
    }

    @Test fun normalizesBrowseIdsOnceAndKeepsSystemPlaylistsValid() {
        for (id in listOf("WL", "LL", "PLlist", "VLPLlist")) {
            val query = playlistBrowseQuery(id, null, null)
            val expected = if (id.startsWith("VL")) id else "VL$id"
            assertTrue(query.body.contains("\"browseId\":\"$expected\""))
            assertFalse(query.body.contains("VLVL"))
        }
        assertTrue(playlistBrowseQuery("bad id", null, null).url.isEmpty())
        assertFalse(playlistBrowseQuery("WL", "CURSOR", null).body.contains("browseId"))
    }
}
