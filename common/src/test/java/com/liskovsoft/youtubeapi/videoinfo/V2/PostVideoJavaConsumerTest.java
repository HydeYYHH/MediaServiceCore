package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Both constructor descriptors and the new explicit Java copy are directly compiled and executed. */
public class PostVideoJavaConsumerTest {
    @Test public void oldAndNewConstructorsAndCopyKeepVideoPresentationExplicit() {
        PostItem old = new PostItem(null, "Ugkx_fixture", null, null, null, null, Collections.emptyList(),
            null, null, null, null, null, null, null, null, false);
        assertNull(old.getVideo());
        MediaItem video = new MediaItem("video", "abcDEF123_-", "Title", "Author", "https://i.example/video.jpg", "1:23");
        PostItem current = new PostItem("UCaaaaaaaaaaaaaaaaaaaaaa", "Ugkx_fixture", "Author", null, null, "Body", Collections.emptyList(),
            "1", "2", null, null, null, null, null, null, false, video);
        assertSame(video, current.getVideo());
        PostItem copied = current.copy(current.getAuthorId(), current.getPostId(), current.getAuthor(), current.getAuthorAvatar(),
            current.getPublished(), "Changed", current.getImages(), current.getLikes(), current.getReplies(), current.getVoteStatus(),
            current.getLike(), current.getUnlike(), current.getDislike(), current.getUndislike(), current.getPoll(), current.getAuthorizationRequired(), current.getVideo(), current.getQuiz());
        assertSame(video, copied.getVideo()); assertEquals("Changed", copied.getText());
        PostItem cleared = copied.copy(null, copied.getPostId(), null, null, null, null, Collections.emptyList(),
            null, null, null, null, null, null, null, null, false, null, null);
        assertNull(cleared.getVideo()); assertEquals(current.getPostId(), cleared.getPostId());
        assertNull(video.getEndpoint()); assertTrue(video.getActions().isEmpty()); assertNull(video.getMenuEndpoint());
    }
}
