package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchResponseTest {
    @Test fun readsMetadataAndKeepsThePivotContinuationSeparate() {
        val page = watchPage("""{
          "contents":{"singleColumnWatchNextResults":{
            "results":{"results":{"contents":[{"itemSectionRenderer":{"contents":[{
              "videoMetadataRenderer":{"videoId":"current","title":{"runs":[{"text":"A film"}]},
                "description":{"simpleText":"About the film"},"shortViewCountText":{"simpleText":"12K views"},
                "publishedTimeText":{"simpleText":"2 days ago"},"likeStatus":"LIKE",
                "owner":{"videoOwnerRenderer":{"title":{"simpleText":"Ada"},
                  "navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}},
                  "thumbnail":{"thumbnails":[{"url":"https://example.org/avatar.jpg"}]},
                  "subscriptionButton":{"subscribed":true}}}}
            }]}}]}},
            "autoplay":{"compactVideoRenderer":{"videoId":"autoplay"}},
            "pivot":{"sectionListRenderer":{
              "contents":[{"shelfRenderer":{"content":{"horizontalListRenderer":{
                "items":[{"compactVideoRenderer":{"videoId":"related","title":{"simpleText":"Next"}}}],
                "continuations":[{"nextContinuationData":{"continuation":"SHELF"}}]
              }}}}],"continuations":[{"nextContinuationData":{"continuation":"PIVOT"}}]
            }}
          }},
          "liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"CHAT"}}]},
          "engagementPanels":[{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
            "header":{"engagementPanelTitleHeaderRenderer":{"contextualInfo":{"simpleText":"42"}}}}}]
        }""", "requested")
        assertEquals("current", page.videoId)
        assertEquals("A film", page.title)
        assertEquals("About the film", page.description)
        assertEquals("Ada", page.author)
        assertEquals("UCada", page.channelId)
        assertEquals("https://example.org/avatar.jpg", page.authorThumbnail)
        assertEquals("12K views", page.viewCount)
        assertEquals("2 days ago", page.published)
        assertEquals("LIKE", page.rating)
        assertEquals(true, page.subscribed)
        assertEquals("42", page.commentCount)
        assertEquals(listOf("related"), page.related.map { it.id })
        assertEquals("PIVOT", page.nextKey)
        assertEquals("CHAT", page.chatKey)
    }

    @Test fun readsMusicMetadataAndTransportOwnerWithoutIncludingAutoplay() {
        val page = watchPage("""{
          "contents":{"singleColumnWatchNextResults":{
            "results":{"results":{"contents":[{"itemSectionRenderer":{"contents":[{
              "musicWatchMetadataRenderer":{"title":{"simpleText":"A song"},"byline":{"simpleText":"Fallback"},
                "viewCount":{"videoViewCountRenderer":{"viewCount":{"simpleText":"123 views"}}}}
            }]}}]}},"autoplay":{"compactVideoRenderer":{"videoId":"not-related"}}
          }},
          "transportControls":{"transportControlsRenderer":{
            "subscribeButton":{"toggleButtonRenderer":{"isToggled":false}},
            "buttons":[
              {"type":"TRANSPORT_CONTROLS_BUTTON_TYPE_CHANNEL_BUTTON","button":{"videoOwnerRenderer":{
                "title":{"simpleText":"Composer"},"navigationEndpoint":{"openPopupAction":{"popup":{
                  "browseEndpoint":{"browseId":"UCcomposer"}}
                }}}}},
              {"type":"TRANSPORT_CONTROLS_BUTTON_TYPE_LIKE_BUTTON","button":{"toggleButtonRenderer":{"isToggled":false}}},
              {"type":"TRANSPORT_CONTROLS_BUTTON_TYPE_DISLIKE_BUTTON","button":{"toggleButtonRenderer":{"isToggled":true}}}
            ]
          }}
        }""", "song")
        assertEquals("A song", page.title)
        assertEquals("Composer", page.author)
        assertEquals("UCcomposer", page.channelId)
        assertEquals("123 views", page.viewCount)
        assertEquals("DISLIKE", page.rating)
        assertEquals(false, page.subscribed)
        assertTrue(page.related.isEmpty())
        assertNull(page.nextKey)
    }

    @Test fun readsContinuationShelvesWithoutRequiringMetadata() {
        val page = watchPage("""{"continuationContents":{"tvSurfaceContentContinuation":{"content":{
          "sectionListRenderer":{"contents":[{"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[
            {"tileRenderer":{"onSelectCommand":{"watchEndpoint":{"videoId":"next"}},
              "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Next film"}}}}}
          ]}}}}],"continuations":[{"nextContinuationData":{"continuation":"AFTER"}}]}
        }}}}""", "current")
        assertEquals(listOf("next"), page.related.map { it.id })
        assertEquals("AFTER", page.nextKey)
        assertNull(page.title)
    }

    @Test fun keepsMissingMetadataVisibleToTheCaller() {
        val page = watchPage("""{"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[]}}}}}""", "current")
        assertNull(page.title)
        assertTrue(page.related.isEmpty())
        assertFalse(page.subscribed == true)
    }

    @Test fun readsHorizontalContinuationItems() {
        val page = watchPage("""{"continuationContents":{"horizontalListContinuation":{"items":[{
          "pivotVideoRenderer":{"videoId":"continued","title":{"simpleText":"Another film"},
            "shortBylineText":{"simpleText":"Ada"},"navigationEndpoint":{"watchEndpoint":{"videoId":"continued"}}}
        }],"continuations":[{"nextContinuationData":{"continuation":"MORE"}}]}}}""", "current")
        assertEquals(listOf("continued"), page.related.map { it.id })
        assertEquals("Ada", page.related.single().author)
        assertEquals("MORE", page.nextKey)
    }

    @Test fun currentControlsOverrideMetadataButtonStates() {
        val page = watchPage("""{
          "contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[{
            "videoMetadataRenderer":{"title":{"simpleText":"Film"},"likeStatus":"LIKE",
              "owner":{"videoOwnerRenderer":{"subscriptionButton":{"subscribed":true}}}}
          }]}}}},
          "transportControls":{"transportControlsRenderer":{
            "subscribeButton":{"toggleButtonRenderer":{"isToggled":false}},
            "likeButton":{"toggleButtonRenderer":{"isToggled":false}},
            "dislikeButton":{"toggleButtonRenderer":{"isToggled":false}}
          }}
        }""", "current")
        assertEquals("INDIFFERENT", page.rating)
        assertEquals(false, page.subscribed)
    }
}
