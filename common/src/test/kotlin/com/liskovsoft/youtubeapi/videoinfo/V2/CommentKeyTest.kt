package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CommentKeyTest {
    @Test fun keepsTheInitialCommentKeySeparateFromSortOptions() {
        val body = """{"engagementPanels":[{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","header":{"serviceEndpoint":{"continuationCommand":{"token":"sort"}}},"content":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}}}]}"""
        assertEquals("comments", commentKey(body))
    }
}
