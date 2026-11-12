package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class CommentAttachmentJavaApiTest {
    @Test public void oldAndExtendedCommentConstructorsProduceUsefulPresentation() {
        Comment oldMinimum = new Comment("plain", "Author", "Text", null, null, false,
            null, null, null, null, null, null, Collections.emptyList(), null, null, false);
        Comment oldFull = new Comment("old", "Author", null, null, null, false,
            null, null, null, null, null, null, Collections.emptyList(), null, null, false,
            null, null, Collections.emptyList(), "UCfixture", false, false, false, null, null, "INDIFFERENT");
        Artwork artwork = new Artwork(Collections.singletonList(new ArtworkVariant("https://images.example.test/comment", 320, 180)));
        CommentImageAttachment image = new CommentImageAttachment(artwork, 320, 180, 16.0 / 9.0, null, null);
        CommentVoiceReply voice = new CommentVoiceReply("Server transcript", "Audio unavailable");
        Comment imageOnly = new Comment("image", "Author", null, null, null, false,
            null, null, null, null, null, null, Collections.emptyList(), null, null, false,
            null, null, Collections.emptyList(), "UCfixture", false, false, false, null, null, null, image);
        Comment withVoice = new Comment("voice", "Author", null, null, null, false,
            null, null, null, null, null, null, Collections.emptyList(), null, null, false,
            null, null, Collections.emptyList(), "UCfixture", false, false, false, null, null, null, null, voice);
        assertTrue(oldMinimum.getHasDisplayableContent());
        assertFalse(oldFull.getHasDisplayableContent());
        assertNull(oldFull.getImageAttachment()); assertNull(oldFull.getVoiceReply());
        assertSame(image, imageOnly.getImageAttachment()); assertNull(imageOnly.getVoiceReply());
        assertEquals(320, imageOnly.getImageAttachment().getArtwork().getBest().getWidth().intValue());
        assertEquals(180, image.getHeight().intValue()); assertEquals(16.0 / 9.0, image.getAspectRatio(), 0.0001);
        assertNull(image.getUnavailableText()); assertNull(image.getUnsupportedText());
        assertTrue(imageOnly.getHasDisplayableContent());
        assertEquals("Server transcript", withVoice.getVoiceReply().getTranscript());
        assertEquals("Audio unavailable", voice.getUnavailableText());
        assertTrue(withVoice.getHasDisplayableContent());
    }
}
