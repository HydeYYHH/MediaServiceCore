package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CommentWireTest {
    @Test fun indexesPayloadsByMutationEntityKeys() {
        val body = """{"contents":[{"commentViewModel":{"commentKey":"entity","toolbarStateKey":"state","pinnedText":"Pinned"}}],"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"entityKey":"entity","payload":{"commentEntityPayload":{"properties":{"commentId":"id","content":{"content":"Text"}},"author":{"channelId":"UCauthor","displayName":"Creator","isCreator":true,"isVerified":true},"toolbar":{"likeCountA11y":"123 likes"}}}},{"entityKey":"state","payload":{"engagementToolbarStateEntityPayload":{"likeState":"TOOLBAR_LIKE_STATE_LIKED","heartState":"TOOLBAR_HEART_STATE_HEARTED"}}}]}}}"""
        val comment = commentPage(body).comments.single()
        assertEquals("id", comment.id)
        assertEquals("UCauthor", comment.authorId)
        assertEquals("123 likes", comment.likeCount)
        assertTrue(comment.liked)
        assertTrue(comment.isCreator)
        assertTrue(comment.isVerified)
        assertTrue(comment.isPinned)
        assertEquals(true, comment.isHearted)
    }
}
