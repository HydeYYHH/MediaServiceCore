package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CommentAttachmentTest {
    private fun fixture() = javaClass.getResource("/fixtures/comment-surface-attachments.json")!!.readText()

    @Test fun joinsEachAttachmentByItsOwnSurfaceKeyAndPreservesEmptyText() {
        val page = commentPage(fixture())
        assertEquals(listOf("image-comment", "voice-comment", "unavailable-comment"), page.comments.map { it.id })
        val imageComment = page.comments[0]
        assertNull(imageComment.text)
        assertTrue(imageComment.runs.isEmpty())
        assertTrue(imageComment.hasDisplayableContent)
        val image = imageComment.imageAttachment!!
        assertEquals(320, image.width); assertEquals(180, image.height)
        assertEquals(1.7777777777777777, image.aspectRatio!!, 0.00001)
        val artwork = image.artwork!!
        assertEquals(2, artwork.variants.size)
        assertEquals("https://images.example.test/small", artwork.select(100, 80)!!.url)
        assertEquals("https://images.example.test/large", artwork.best!!.url)
        assertNull(image.unavailableText); assertNull(image.unsupportedText); assertNull(imageComment.voiceReply)
        assertEquals("CommentImageAttachment(variantCount=2)", image.toString())

        val voice = page.comments[1]
        assertNull(voice.text); assertNull(voice.imageAttachment)
        val voiceReply = voice.voiceReply!!
        assertEquals("A server supplied voice transcript", voiceReply.transcript)
        assertEquals("Audio is unavailable", voiceReply.unavailableText)
        assertEquals("CommentVoiceReply", voiceReply.toString())
        assertTrue(voice.hasDisplayableContent)

        val unavailable = page.comments[2]
        val unavailableImage = unavailable.imageAttachment!!
        assertNull(unavailable.text); assertNull(unavailableImage.artwork)
        assertEquals("Image unavailable", unavailableImage.unavailableText)
        assertEquals("Image cannot be displayed", unavailableImage.unsupportedText)
        assertNull(unavailableImage.width); assertNull(unavailableImage.height)
        assertNull(unavailableImage.aspectRatio); assertFalse(unavailable.hasDisplayableContent)

        val presentation = DiscussionComment(imageComment)
        assertSame(image, presentation.imageAttachment)
        assertNull(presentation.voiceReply)
        assertTrue(presentation.hasDisplayableContent)
        assertEquals("DiscussionComment", presentation.toString())
    }

    @Test fun missingOrMismatchedReferencesNeverBorrowAnotherCommentImage() {
        for (body in listOf(
            fixture().replace("\"commentSurfaceKey\": \"image-surface\"", "\"commentSurfaceKey\": \"missing-surface\""),
            fixture().replace("\"key\": \"image-surface\"", "\"key\": \"different-payload-key\""),
            fixture().replace("\"commentSurfaceKey\": \"image-surface\"", "\"unusedSurfaceKey\": \"image-surface\""),
            fixture().replace("\"commentImageAttachmentViewModel\"", "\"unknownImageAttachmentViewModel\"")
        )) {
            val image = commentPage(body).comments.first()
            assertNull(image.imageAttachment)
            assertFalse(image.hasDisplayableContent)
            assertNull(image.text)
        }
    }

    @Test fun malformedImageResourcesAndUnknownVoiceShapesStayUnavailable() {
        val body = fixture().replace("https://images.example.test/small", "javascript:untrusted")
            .replace("https://images.example.test/large", "http://images.example.test/insecure")
            .replace("\"width\": 320, \"height\": 180, \"aspectRatio\": 1.7777777777777777",
                "\"width\": -1, \"height\": 0, \"aspectRatio\": \"NaN\"")
            .replace("\"transcriptText\": {\"content\": \"A server supplied voice transcript\"}", "\"unrecognizedAudio\": {\"url\": \"https://audio.example.test/unknown\"}")
        val comments = commentPage(body).comments
        val image = comments.first().imageAttachment!!
        assertNull(image.artwork); assertNull(image.width); assertNull(image.height); assertNull(image.aspectRatio)
        assertFalse(comments.first().hasDisplayableContent)
        assertNull(comments[1].voiceReply!!.transcript)
        assertFalse(comments[1].hasDisplayableContent)
        assertFalse(DiscussionComment(comments[1]).hasDisplayableContent)
        assertNotNull(DiscussionComment(comments[1]).voiceReply)
    }

    @Test fun regularTextDoesNotRequireAnyAttachmentAndBlankTextDoesNotBecomeContent() {
        val regular = commentPage("""{"commentRenderer":{"commentId":"plain","contentText":{"simpleText":"Hello"}}}""").comments.single()
        assertTrue(regular.hasDisplayableContent)
        assertNull(regular.imageAttachment); assertNull(regular.voiceReply)
        val blank = regular.copy(text = "  ", runs = emptyList())
        assertFalse(blank.hasDisplayableContent)
        assertFalse(DiscussionComment(blank).hasDisplayableContent)
    }
}
