package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ModernMetadataReadTest {
    @Test fun commentCommandsCannotShadowTheActualPanelContinuation() {
        val body = """{"playerOverlays":{"showEngagementPanelEndpoint":{"panelIdentifier":"engagement-panel-comments-section"}},
          "engagementPanels":[{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
            "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"comments-page"}}}}
            ]}}]}}}}]}"""
        assertEquals("comments-page", commentKey(body))
    }

    @Test fun readsModernChannelHeaderWithoutInventingCounts() {
        val page = channelPage("""{"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{
          "title":{"dynamicTextViewModel":{"text":{"content":"Adele"}}},
          "metadata":{"contentMetadataViewModel":{"metadataRows":[
            {"metadataParts":[{"text":{"content":"@adele"}}]},
            {"metadataParts":[{"text":{"content":"33.5M subscribers"}},{"text":{"content":"67 videos"}}]}]}},
          "image":{"decoratedAvatarViewModel":{"avatar":{"avatarViewModel":{"image":{"sources":[{"url":"avatar"}]}}}}},
          "banner":{"imageBannerViewModel":{"image":{"sources":[{"url":"banner"}]}}}
        }}}}}""", "UCchannel")
        assertEquals("Adele", page.title)
        assertEquals("@adele", page.handle)
        assertEquals("33.5M subscribers", page.subscriberCount)
        assertEquals("67 videos", page.videoCount)
        assertEquals("avatar", page.thumbnail)
        assertEquals("banner", page.banner)
        assertNull(channelPage("{}", "UCchannel").videoCount)
    }

    @Test fun modernLockupPreservesAuthorViewsAgeAndIdentity() {
        val page = browsePage("""{"contents":[{"lockupViewModel":{
          "contentId":"aqz-KE-bpKQ","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"aqz-KE-bpKQ"}}}}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Movie"},
            "image":{"decoratedAvatarViewModel":{"avatar":{"avatarViewModel":{"image":{"sources":[{"url":"avatar"}]}}}}},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[
              {"metadataParts":[{"text":{"content":"Blender","commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCchannel"}}}}]}}]},
              {"metadataParts":[{"text":{"content":"9.5M"},"accessibilityLabel":"9.5 million views","leadingIcon":{"name":"PLAY_ARROW_OUTLINED"}},
                {"text":{"content":"10mo ago"}}]}]}}}}
        }}]}""")
        val item = page.items.single()
        assertEquals("Blender", item.author)
        assertEquals("UCchannel", item.channelId)
        assertEquals("9.5M", item.viewCount)
        assertEquals("10mo ago", item.published)
        assertEquals("avatar", item.authorThumbnail)
    }

    @Test fun watchMetadataCarriesOwnerAndCommentHeader() {
        val page = watchPage("""{
          "videoPrimaryInfoRenderer":{"title":{"runs":[{"text":"Movie"}]},"dateText":{"simpleText":"Oct 1, 2026"},
            "viewCount":{"videoViewCountRenderer":{"shortViewCount":{"simpleText":"23M views"}}}},
          "videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"title":{"runs":[{"text":"Blender"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"UCchannel"}},"thumbnail":{"thumbnails":[{"url":"avatar"}]}}}},
          "engagementPanels":[{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
            "header":{"engagementPanelTitleHeaderRenderer":{"contextualInfo":{"runs":[{"text":"5.2K"}]}}}}}],
          "frameworkUpdates":{"likeStatusEntity":{"likeStatus":"LIKE"}}
        }""", "aqz-KE-bpKQ")
        assertEquals("Blender", page.author)
        assertEquals("avatar", page.authorThumbnail)
        assertEquals("23M views", page.viewCount)
        assertEquals("Oct 1, 2026", page.published)
        assertEquals("5.2K", page.commentCount)
        assertEquals("LIKE", page.rating)
    }
}
