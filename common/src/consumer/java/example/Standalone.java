package example;

import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerAnswer;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerResponseKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerPlaybackKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackPreference;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackSession;
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply;
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext;
import com.liskovsoft.youtubeapi.videoinfo.V2.CommentRun;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerContextKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClientQueues;
import java.util.Collections;

/** Compiled against the packaged JAR and its runtime dependencies, without main output directories. */
public final class Standalone {
    public static void main(String[] args) {
        PlayerAnswer answer = PlayerResponseKt.playerAnswer(
            "{\"streamingData\":{\"formats\":[{\"url\":\"https://media.example/video\"}]}}");
        String url = PlayerPlaybackKt.selectPlayback(answer, new PlaybackPreference()).getVideoUrl();
        if (!"https://media.example/video".equals(url)) throw new AssertionError("Packaged core failed");
        PlaybackPreference legacy = new PlaybackPreference(480, Collections.emptySet(), null, null, false);
        HttpClient http = request -> new HttpReply(200,
            "{\"streamingData\":{\"formats\":[{\"url\":\"https://media.example/legacy\"}]}}",
            Collections.emptyMap());
        PlaybackSession session = new PlaybackSession(http, null, null, null, () -> 0L);
        if (!session.load("video", new RequestContext(), legacy, false, Collections.emptySet()).getOk())
            throw new AssertionError("Legacy JVM constructor overload failed");
        if (!PlayerContextKt.loadPlayer("video", new RequestContext(), http, null, null, null,
            new PlayerClientQueues(), Collections.emptySet(), () -> 0L).getOk())
            throw new AssertionError("Legacy JVM load overload failed");
        new CommentRun("text", null, null, null, null, null);
        System.out.println("Standalone JAR consumer passed");
    }
}
