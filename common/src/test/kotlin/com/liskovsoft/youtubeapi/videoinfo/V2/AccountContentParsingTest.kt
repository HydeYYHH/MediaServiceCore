package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class AccountContentParsingTest {
    @Test fun modernLikeCountDoesNotReadTheDislikeButtonLabel() {
        val page = watchPage("""{"segmentedLikeDislikeButtonViewModel":{
            "likeButtonViewModel":{"likeButtonViewModel":{"toggleButtonViewModel":{
                "toggleButtonViewModel":{"defaultButtonViewModel":{"buttonViewModel":{"title":"2.9K"}}}}}},
            "dislikeButtonViewModel":{"defaultButtonViewModel":{"buttonViewModel":{"title":"17"}}}
        }}""")
        assertEquals("2.9K", page.likeCount)
        assertNull(watchPage("""{"segmentedLikeDislikeButtonViewModel":{"likeButtonViewModel":{
            "defaultButtonViewModel":{"buttonViewModel":{"title":"Like"}}}}}""").likeCount)
    }
    @Test fun channelHeaderPreservesIdentityAndDescription() {
        val page = channelPage("""{
            "header":{"channelHeaderRenderer":{
                "title":{"simpleText":"Ada"},
                "avatar":{"thumbnails":[{"url":"https://example.com/avatar"}]},
                "backgroundImage":{"thumbnails":[{"url":"https://example.com/banner"}]},
                "subtitle":{"lineRenderer":{"items":[
                    {"lineItemRenderer":{"text":{"simpleText":"@ada"}}},
                    {"lineItemRenderer":{"text":{"runs":[{"text":"10 subscribers"}]}}},
                    {"lineItemRenderer":{"text":{"simpleText":"20 videos"}}}
                ]}},
                "buttons":[{"subscribeButtonRenderer":{"subscribed":true}}],
                "selectableDescription":{"selectableTextRenderer":{
                    "compactDescription":{"runs":[{"text":"Preview"}]},
                    "onSelectCommand":{"aboutChannelViewModel":{"description":"Full description"}}
                }}
            }}}
        """, "UCada")
        assertEquals("Ada", page.title)
        assertEquals("@ada", page.handle)
        assertEquals("Full description", page.description)
        assertEquals("https://example.com/avatar", page.thumbnail)
        assertEquals("https://example.com/banner", page.banner)
        assertEquals("10 subscribers", page.subscriberCount)
        assertEquals("20 videos", page.videoCount)
        assertEquals(true, page.subscribed)
    }

    @Test fun commentsUseTheirContentContinuationInsteadOfSortOrRelatedTokens() {
        val body = """{"engagementPanels":[
            {"engagementPanelSectionListRenderer":{"panelIdentifier":"related-videos",
                "content":{"continuations":[{"nextContinuationData":{"continuation":"related"}}]}}},
            {"engagementPanelSectionListRenderer":{"panelIdentifier":"comment-item-section",
                "header":{"sortFilterSubMenuRenderer":{"subMenuItems":[{"continuation":{
                    "reloadContinuationData":{"continuation":"sort"}}}]}},
                "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{
                    "continuations":[{"reloadContinuationData":{"continuation":"comments"}}],
                    "targetId":"comment-item-section-target"}}]}}}}
        ]}"""
        assertEquals("comments", commentKey(body))
        assertEquals("comments", watchPage(body).commentsKey)
        assertEquals("sort", commentSortOptions(requireNotNull(jsonObject(body))).single().key)
        assertNull(commentKey(body.replace("comment-item-section\"", "unrelated-panel\"")))
    }

    @Test fun watchCountsDoNotConfuseTheRatingWithTheLikeCount() {
        val page = watchPage("""{"contents":{"singleColumnWatchNextResults":{"results":{"results":{
            "videoMetadataRenderer":{"likeStatus":"LIKE","likeButton":{"likeButtonRenderer":{
                "likeCount":12000,"likeCountText":{"simpleText":"12K"}}}}
        }}}},"commentsEntryPointRenderer":{"commentCount":{"simpleText":"120"}}}""")
        assertEquals("LIKE", page.rating)
        assertEquals("12K", page.likeCount)
        assertEquals("120", page.commentCount)
        assertEquals("2.9K", watchPage("""{"transportControls":{"transportControlsRenderer":{"buttons":[
            {"type":"TRANSPORT_CONTROLS_BUTTON_TYPE_LIKE_DISLIKE_BUTTON","button":{"likeButtonRenderer":{
                "likeCount":2940,"likeCountText":{"simpleText":"2.9K"}}}}
        ]}}}""").likeCount)
        assertNull(watchPage("""{"transportControls":{"transportControlsRenderer":{
            "likeButton":{"toggleButtonRenderer":{"defaultText":{"simpleText":"Like"}}}
        }}}""").likeCount)
    }
}
