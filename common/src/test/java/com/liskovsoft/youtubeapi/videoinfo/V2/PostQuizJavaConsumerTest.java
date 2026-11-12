package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PostQuizJavaConsumerTest {
    @Test public void oldSixteenAndSeventeenAndNewEighteenConstructorsAndCopyRemainExplicit() {
        PostItem sixteen = new PostItem(null, "Ugkx_fixture", null, null, null, null, Collections.emptyList(),
            null, null, null, null, null, null, null, null, false);
        MediaItem video = new MediaItem("video", "abcdefghijk", "Title", null, null, null);
        PostItem seventeen = new PostItem(null, "Ugkx_fixture", null, null, null, null, Collections.emptyList(),
            null, null, null, null, null, null, null, null, false, video);
        assertNull(sixteen.getQuiz()); assertNull(seventeen.getQuiz()); assertSame(video, seventeen.getVideo());
        PostQuizChoice choice = new PostQuizChoice("Choice", true);
        assertEquals("Choice", choice.getText()); assertTrue(choice.isCorrect());
        PostQuiz quiz = new PostQuiz(Collections.singletonList(choice), "7 votes");
        assertEquals(choice, quiz.getChoices().get(0)); assertEquals("7 votes", quiz.getTotalVotes());
        PostItem eighteen = new PostItem(null, "Ugkx_fixture", null, null, null, "Body", Collections.emptyList(),
            null, null, null, null, null, null, null, null, false, null, quiz);
        assertSame(quiz, eighteen.getQuiz()); assertSame(quiz, eighteen.component18()); assertNull(eighteen.component17());
        PostItem copied = eighteen.copy(eighteen.getAuthorId(), eighteen.getPostId(), eighteen.getAuthor(), eighteen.getAuthorAvatar(),
            eighteen.getPublished(), "Changed", eighteen.getImages(), eighteen.getLikes(), eighteen.getReplies(), eighteen.getVoteStatus(),
            eighteen.getLike(), eighteen.getUnlike(), eighteen.getDislike(), eighteen.getUndislike(), eighteen.getPoll(),
            eighteen.getAuthorizationRequired(), eighteen.getVideo(), eighteen.getQuiz());
        assertSame(quiz, copied.getQuiz()); assertEquals("Changed", copied.getText());
        PostItem cleared = copied.copy(null, copied.getPostId(), null, null, null, null, Collections.emptyList(), null, null,
            null, null, null, null, null, null, false, null, null);
        assertNull(cleared.getQuiz());
    }
}
