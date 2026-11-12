package example;

import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult;
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure;
import com.liskovsoft.youtubeapi.videoinfo.V2.WebPlayerPage;
import com.liskovsoft.youtubeapi.videoinfo.V2.WebPlayerReadKt;

import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerAnswer;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerResponseKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerPlaybackKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackPreference;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackSession;
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply;
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext;
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession;
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountSession;
import com.liskovsoft.youtubeapi.videoinfo.V2.CommentRun;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerContextKt;
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClientQueues;
import com.liskovsoft.youtubeapi.videoinfo.V2.VisitorSession;
import com.liskovsoft.youtubeapi.videoinfo.V2.CatalogSession;
import com.liskovsoft.youtubeapi.videoinfo.V2.CatalogPage;
import com.liskovsoft.youtubeapi.videoinfo.V2.ChannelTabKind;
import com.liskovsoft.youtubeapi.videoinfo.V2.PreparedPost;
import com.liskovsoft.youtubeapi.videoinfo.V2.PostQuiz;
import com.liskovsoft.youtubeapi.videoinfo.V2.PreparedMediaItem;
import com.liskovsoft.youtubeapi.videoinfo.V2.DisplayByline;
import com.liskovsoft.youtubeapi.videoinfo.V2.DisplayBylineRole;
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
        HttpClient accountHttp = request -> {
            String authorization = request.getHeaders().get("Authorization");
            if (authorization == null || !authorization.startsWith("SAPISIDHASH ") ||
                !"SAPISID=fixture".equals(request.getHeaders().get("Cookie")))
                throw new AssertionError("Cookie authorization missing from packaged core");
            return new HttpReply(200,
                "{\"accountItemRenderer\":{\"accountId\":\"owner\",\"isSelected\":true}}", Collections.emptyMap());
        };
        AccountSession account = new AccountSession();
        if (!account.install(new WebSession("SAPISID=fixture", 0), accountHttp).getOk())
            throw new AssertionError("Packaged cookie session installation failed");
        long generation = account.snapshot().getGeneration();
        if (!account.update(new WebSession("SAPISID=fixture", 0), accountHttp).getOk() ||
            account.snapshot().getGeneration() != generation)
            throw new AssertionError("Cookie renewal changed the stable account generation");
        account.clear();
        if (account.snapshot().getSignedIn() || account.snapshot(generation).getOk())
            throw new AssertionError("Cleared account delivered an obsolete snapshot");
        account.installVisitor(new VisitorSession("VISITOR_INFO1_LIVE=guest-fixture", "visitor-fixture"));
        final int[] calls = {0};
        HttpClient catalogHttp = request -> {
            calls[0]++;
            if (!request.getBody().contains("FEwhat_to_watch") || !request.getBody().contains("WEB") ||
                request.getHeaders().containsKey("Authorization") ||
                !"VISITOR_INFO1_LIVE=guest-fixture".equals(request.getHeaders().get("Cookie")))
                throw new AssertionError("Native visitor home identity was not preserved");
            return new HttpReply(200,
                "{\"contents\":{\"richGridRenderer\":{\"contents\":[{\"videoRenderer\":{" +
                "\"videoId\":\"jNQXAC9IVRw\",\"title\":{\"simpleText\":\"Lesson\"}," +
                "\"channelThumbnail\":{\"thumbnails\":[{\"url\":\"https://yt3.ggpht.com/fixture\",\"width\":88,\"height\":88}]}}}]}}}",
                Collections.emptyMap());
        };
        CatalogPage home = new CatalogSession(account, catalogHttp).home().getValue();
        if (home == null || home.getEmpty() || home.getPreparationRequestCount() != 0 ||
            home.getItems().get(0).getAuthor().getAvatar() == null ||
            home.getItems().get(0).getAuthor().getAvatar().getBest().getWidth() != 88 || calls[0] != 1)
            throw new AssertionError("Packaged prepared catalog contract failed");
        HttpClient screensHttp = request -> {
            if (request.getMethod().equals("GET")) return new HttpReply(200,
                "<script>var ytInitialData={\"contents\":[{\"backstagePostRenderer\":{" +
                "\"postId\":\"Ugkx_packaged_post\",\"contentText\":\"Announcement\",\"authorText\":\"Teacher\"," +
                "\"authorThumbnail\":{\"thumbnails\":[{\"url\":\"https://example.test/author\"}]},\"backstageAttachment\":{\"quizRenderer\":{" +
                "\"choices\":[{\"text\":{\"simpleText\":\"One\"},\"isCorrect\":true},{\"text\":{\"simpleText\":\"Two\"},\"isCorrect\":false}]," +
                "\"totalVotes\":{\"simpleText\":\"2 votes\"}}}}}]};</script>", Collections.emptyMap());
            return new HttpReply(200,
                "{\"metadata\":{\"channelMetadataRenderer\":{\"externalId\":\"UC_x5XG1OV2P6uZZ5FSM9Ttw\",\"title\":\"Lessons\",\"avatar\":{\"thumbnails\":[{\"url\":\"https://example.test/channel-avatar\"}]}}}," +
                "\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"selected\":true,\"tabIdentifier\":\"videos\"," +
                "\"endpoint\":{\"browseEndpoint\":{\"browseId\":\"UC_x5XG1OV2P6uZZ5FSM9Ttw\"}}," +
                "\"content\":{\"richGridRenderer\":{\"contents\":[{\"videoRenderer\":{\"videoId\":\"jNQXAC9IVRw\",\"title\":\"Lesson\"}}]}}}}]}}}", Collections.emptyMap());
        };
        CatalogSession screens = new CatalogSession(account, screensHttp);
        CatalogPage channel = screens.channel("UC_x5XG1OV2P6uZZ5FSM9Ttw", ChannelTabKind.VIDEOS).getValue();
        PreparedPost post = screens.post("Ugkx_packaged_post").getValue();
        if (channel == null || channel.getItems().get(0).getPost() != null || post == null ||
            !"Ugkx_packaged_post".equals(post.getPost().getPostId()) || post.getAuthor().getAvatar() == null ||
            !"PreparedPost(authorArtworkAvailable=true)".equals(post.toString()))
            throw new AssertionError("Packaged prepared channel/post contract failed");
        PostQuiz quiz = post.getPost().getQuiz();
        if (quiz == null || quiz.getChoices().size() != 2 ||
            !"One".equals(quiz.getChoices().get(0).getText()) || !quiz.getChoices().get(0).isCorrect() ||
            quiz.getChoices().get(1).isCorrect() || !"2 votes".equals(quiz.getTotalVotes()))
            throw new AssertionError("Packaged read-only quiz contract failed");
        PreparedMediaItem videoCard = channel.getItems().get(0);
        DisplayByline display = videoCard.getDisplayByline();
        if (display.getRole() != DisplayBylineRole.COLLECTION_OWNER || display.getAuthor().getAvatar() == null ||
            videoCard.getItem().getChannelId() != null || videoCard.getAuthor().getChannelId() != null ||
            videoCard.copy(videoCard.component1(), videoCard.component2()).getDisplayByline() != display ||
            new PreparedMediaItem(videoCard.getItem(), videoCard.getAuthor()).getDisplayByline().getRole() == DisplayBylineRole.COLLECTION_OWNER ||
            !display.equals(new DisplayByline(display.getAuthor(), DisplayBylineRole.valueOf("COLLECTION_OWNER"))))
            throw new AssertionError("Packaged single display byline contract failed");
        ReadResult<WebPlayerPage> invalidWebPlayer = WebPlayerReadKt.loadWebPlayer("invalid video", new RequestContext());
        if (invalidWebPlayer.getError() == null || invalidWebPlayer.getError().getKind() != RequestFailure.INVALID)
            throw new AssertionError("Packaged WEB document overload did not reject before HTTP");
        System.out.println("Standalone JAR consumer passed");
    }
}
