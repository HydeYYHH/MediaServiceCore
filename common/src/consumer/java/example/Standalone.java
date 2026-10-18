package example;

import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerAnswer;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerResponseKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerPlaybackKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackPreference;

/** Compiled against the packaged JAR and its runtime dependencies, without main output directories. */
public final class Standalone {
    public static void main(String[] args) {
        PlayerAnswer answer = PlayerResponseKt.playerAnswer(
            "{\"streamingData\":{\"formats\":[{\"url\":\"https://media.example/video\"}]}}");
        String url = PlayerPlaybackKt.selectPlayback(answer, new PlaybackPreference()).getVideoUrl();
        if (!"https://media.example/video".equals(url)) throw new AssertionError("Packaged core failed");
        System.out.println("Standalone JAR consumer passed");
    }
}
