package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.junit.Test;
import static org.junit.Assert.*;

/** Java hosts must reach the actual public overload, with its own defaults and transport behavior. */
public class PublicPlaybackOverloadTest {
    private static final String MEDIA = "https://media.example/muxed";
    private static final String RESPONSE = "{\"playabilityStatus\":{\"status\":\"OK\"},"
        + "\"streamingData\":{\"formats\":[{\"itag\":18,\"mimeType\":\"video/mp4\",\"url\":\"" + MEDIA + "\"}]}}";

    private static HttpClient transport(List<HttpRequest> requests) {
        return request -> {
            assertEquals("POST", request.getMethod());
            assertTrue(request.getUrl().startsWith("https://www.youtube.com/youtubei/v1/player?"));
            assertFalse(request.getHeaders().containsKey("Cookie"));
            assertFalse(request.getHeaders().containsKey("Authorization"));
            requests.add(request);
            return new HttpReply(200, RESPONSE, Collections.emptyMap());
        };
    }

    private static PlaybackLoad playable(ReadResult<PlaybackLoad> result) {
        assertTrue(result.getError() == null ? "empty result" : result.getError().getCode(), result.getOk());
        PlaybackLoad load = result.getValue();
        assertNotNull(load);
        assertNull(load.getError());
        assertEquals(MEDIA, load.getPlayback().getVideoUrl());
        assertNotNull(load.getClient());
        assertNotNull(load.getAnswer());
        assertNotNull(load.getSource());
        assertTrue(load.getSource().getUrls().contains(MEDIA));
        return load;
    }

    @Test public void everyInjectableSessionConstructorLoadsThroughItsOwnTransport() {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = transport(requests);
        PlayerScript script = new PlayerScript("https://www.youtube.com/base.js", "signatureTimestamp:123");
        Function0<Long> clock = () -> 1_000L;
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        PlayerScriptProvider provider = new PlayerScriptProvider() {
            @Override public ReadResult<PlayerScript> script(String videoId, RequestContext context, boolean fresh) {
                providerCalls.incrementAndGet();
                return new ReadResult<>(script, null);
            }
            @Override public String cacheKey(String videoId, RequestContext context) { return null; }
        };
        Function1<PlayerLoadAttempt, Unit> onAttempt = attempt -> {
            assertFalse(attempt.getAuthenticated());
            assertNull(attempt.getError());
            attempts.incrementAndGet();
            return Unit.INSTANCE;
        };
        PlaybackSession[] sessions = {
            new PlaybackSession(http),
            new PlaybackSession(http, script),
            new PlaybackSession(http, script, null),
            new PlaybackSession(http, script, null, null),
            new PlaybackSession(http, script, null, null, clock),
            new PlaybackSession(http, script, null, null, clock, provider),
            new PlaybackSession(http, script, null, null, clock, provider, onAttempt),
            new PlaybackSession(http, script, null, null, clock, provider, onAttempt, new PlayerClientQueues())
        };
        for (int i = 0; i < sessions.length; i++) {
            PlaybackLoad loaded = playable(sessions[i].load("constructor" + i));
            assertEquals("constructor" + i, loaded.getSource().getVideoId());
            assertEquals(1L, loaded.getSource().getSequence());
            assertNotNull(loaded.getSource().getSessionId());
        }
        assertEquals(8, requests.size());
        assertEquals(3, providerCalls.get());
        assertEquals(2, attempts.get());

        // Even the convenience constructor validates a target before creating any network request.
        ReadResult<PlaybackLoad> rejected = new PlaybackSession().load("invalid/video");
        assertFalse(rejected.getOk());
        assertEquals(RequestFailure.INVALID, rejected.getError().getKind());
    }

    @Test public void everyLoadOverloadReturnsAUsableSourceAndPreservesItsSuppliedContext() {
        List<HttpRequest> requests = new ArrayList<>();
        PlaybackSession session = new PlaybackSession(transport(requests));
        RequestContext context = new RequestContext();
        PlaybackPreference preference = new PlaybackPreference(720);
        Set<PlayerClient> excluded = Collections.singleton(PlayerClient.VISIONOS);
        Set<PlayerClient> deferred = Collections.singleton(PlayerClient.TV);
        List<ReadResult<PlaybackLoad>> loads = Arrays.asList(
            session.load("overload0"),
            session.load("overload1", context),
            session.load("overload2", context, preference),
            session.load("overload3", context, preference, true),
            session.load("overload4", context, preference, false, excluded),
            session.load("overload5", context, preference, false, excluded, false),
            session.load("overload6", context, preference, false, excluded, false, deferred),
            session.load("overload7", context, preference, false, excluded, false, deferred, "download")
        );
        for (int i = 0; i < loads.size(); i++) {
            PlaybackLoad loaded = playable(loads.get(i));
            PlayerSource source = loaded.getSource();
            assertEquals("overload" + i, source.getVideoId());
            assertEquals(i + 1L, source.getSequence());
            assertEquals(context, source.getContext());
            assertEquals(i < 2 ? Integer.MAX_VALUE : 720, source.getPreference().getMaxHeight());
            assertTrue(requests.get(i).getBody().contains("\"videoId\":\"overload" + i + "\""));
        }
        assertEquals(8, requests.size());
        PlayerSource basic = new PlayerSource("overload0", loads.get(0).getValue().getClient(), context,
            Collections.singleton(MEDIA), 20L);
        assertEquals(Integer.MAX_VALUE, basic.getPreference().getMaxHeight());
        assertNull(basic.getSessionId());
        PlayerSource configured = new PlayerSource("overload0", loads.get(0).getValue().getClient(), context,
            Collections.singleton(MEDIA), 21L, preference);
        assertSame(preference, configured.getPreference());
        assertNull(configured.getSessionId());
    }

    @Test public void javaFormatConstructorsPreserveSuppliedDownloadMetadataAndDefaultOmittedMetadata() {
        PlayerAudio track = new PlayerAudio("en.1", "en", "English", true, false);
        PlayerRange init = new PlayerRange(0L, 99L);
        PlayerRange index = new PlayerRange(100L, 199L);
        List<PlayerFormat> formats = Arrays.asList(
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index, Collections.singletonList("avc1")),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index, Collections.singletonList("avc1"), VideoRange.SDR),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index, Collections.singletonList("avc1"), VideoRange.SDR, 90_000L),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index, Collections.singletonList("avc1"), VideoRange.SDR, 90_000L, 48_000),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index, Collections.singletonList("avc1"), VideoRange.SDR, 90_000L, 48_000, 2),
            new PlayerFormat(137, "video/mp4", "https://media.example/muxed", null, 1080, 2_000_000, 30, track, 1920, 1024L, init, index, Collections.singletonList("avc1"), VideoRange.SDR, 90_000L, 48_000, 2, "1080p")
        );
        for (int i = 0; i < formats.size(); i++) {
            PlayerFormat format = formats.get(i);
            assertEquals(Integer.valueOf(137), format.getItag());
            assertEquals("video/mp4", format.getMimeType());
            assertEquals(MEDIA, format.getUrl());
            assertNull(format.getCipher());
            assertEquals(i >= 1 ? Integer.valueOf(1080) : null, format.getHeight());
            assertEquals(i >= 2 ? Integer.valueOf(2_000_000) : null, format.getBitrate());
            assertEquals(i >= 3 ? Integer.valueOf(30) : null, format.getFps());
            assertEquals(i >= 4 ? track : null, format.getAudioTrack());
            assertEquals(i >= 5 ? Integer.valueOf(1920) : null, format.getWidth());
            assertEquals(i >= 6 ? Long.valueOf(1024L) : null, format.getContentLength());
            assertEquals(i >= 7 ? init : null, format.getInitRange());
            assertEquals(i >= 8 ? index : null, format.getIndexRange());
            assertEquals(i >= 9 ? Collections.singletonList("avc1") : Collections.emptyList(), format.getCodecs());
            assertEquals(i >= 10 ? VideoRange.SDR : VideoRange.UNKNOWN, format.getDynamicRange());
            assertEquals(i >= 11 ? Long.valueOf(90_000L) : null, format.getApproxDurationMs());
            assertEquals(i >= 12 ? Integer.valueOf(48_000) : null, format.getSampleRate());
            assertEquals(i >= 13 ? Integer.valueOf(2) : null, format.getAudioChannels());
            assertEquals(i >= 14 ? "1080p" : null, format.getQualityLabel());
        }
    }

    @Test public void javaAnswerConstructorsKeepOptionalPlaybackSourcesAndDeadlinesIndependent() {
        PlayerFormat format = new PlayerFormat(18, "video/mp4", MEDIA, null);
        PlayerDetails details = PlayerResponseKt.playerAnswer("{\"videoDetails\":{\"videoId\":\"v\",\"title\":\"Lesson\"}}").getDetails();
        List<PlayerAnswer> answers = Arrays.asList(
            new PlayerAnswer(false, "OK", null, true, true, true, false),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls"),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl())),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive")),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format)),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format)),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash"),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr"),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer"),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer", 60L),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer", 60L, details),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer", 60L, details, Collections.emptyList()),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer", 60L, details, Collections.emptyList(), 60_000L),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer", 60L, details, Collections.emptyList(), 60_000L, 750L),
            new PlayerAnswer(false, "OK", null, true, true, true, false, "https://media.example/hls", Collections.singletonList(format.getUrl()), Collections.singletonList("https://media.example/adaptive"), Collections.singletonList(format), Collections.singletonList(format), "https://media.example/dash", "https://media.example/sabr", "streamer", 60L, details, Collections.emptyList(), 60_000L, 750L, 1_750L)
        );
        for (int i = 0; i < answers.size(); i++) {
            PlayerAnswer answer = answers.get(i);
            assertFalse(answer.getTransportFailed());
            assertEquals("OK", answer.getStatus());
            assertNull(answer.getReason());
            assertTrue(answer.getHasDirectUrl());
            assertTrue(answer.getHasHls());
            assertTrue(answer.getHasMuxed());
            assertFalse(answer.getLive());
            assertEquals(i >= 1 ? "https://media.example/hls" : null, answer.getHlsUrl());
            assertEquals(i >= 2 ? Collections.singletonList(MEDIA) : Collections.emptyList(), answer.getMuxedUrls());
            assertEquals(i >= 3 ? Collections.singletonList("https://media.example/adaptive") : Collections.emptyList(), answer.getAdaptiveUrls());
            assertEquals(i >= 4 ? Collections.singletonList(format) : Collections.emptyList(), answer.getMuxedFormats());
            assertEquals(i >= 5 ? Collections.singletonList(format) : Collections.emptyList(), answer.getAdaptiveFormats());
            assertEquals(i >= 6 ? "https://media.example/dash" : null, answer.getDashUrl());
            assertEquals(i >= 7 ? "https://media.example/sabr" : null, answer.getSabrUrl());
            assertEquals(i >= 8 ? "streamer" : null, answer.getUstreamerConfig());
            assertEquals(i >= 9 ? Long.valueOf(60L) : null, answer.getExpiresInSeconds());
            assertEquals(i >= 10 ? details : null, answer.getDetails());
            assertTrue(answer.getCaptions().isEmpty());
            assertEquals(i >= 12 ? Long.valueOf(60_000L) : null, answer.getExpiresAtMs());
            assertEquals(i >= 13 ? 750L : 0L, answer.getMediaWaitMs());
            assertEquals(i >= 14 ? 1_750L : 0L, answer.getMediaAvailableAtMs());
            Playback selected = PlayerPlaybackKt.selectPlayback(answer, new PlaybackPreference());
            assertEquals(i >= 4 ? MEDIA : null, selected.getVideoUrl());
            assertEquals(i >= 1 && i < 4 ? "https://media.example/hls" : null, selected.getHlsUrl());
        }
    }

    @Test public void javaPreferenceConstructorsDefaultOnlyTheConstraintsThatWereOmitted() {
        List<PlaybackPreference> preferences = Arrays.asList(
            new PlaybackPreference(),
            new PlaybackPreference(720),
            new PlaybackPreference(720, Collections.singleton("video/mp4")),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1"),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en"),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en", true),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en", true, Collections.singleton("avc1")),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en", true, Collections.singleton("avc1"), Collections.singleton("mp4a")),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en", true, Collections.singleton("avc1"), Collections.singleton("mp4a"), 30),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en", true, Collections.singleton("avc1"), Collections.singleton("mp4a"), 30, VideoRange.SDR),
            new PlaybackPreference(720, Collections.singleton("video/mp4"), "en.1", "en", true, Collections.singleton("avc1"), Collections.singleton("mp4a"), 30, VideoRange.SDR, false)
        );
        for (int i = 0; i < preferences.size(); i++) {
            PlaybackPreference preference = preferences.get(i);
            assertEquals(i >= 1 ? 720 : Integer.MAX_VALUE, preference.getMaxHeight());
            assertEquals(i >= 2 ? Collections.singleton("video/mp4") : Collections.emptySet(), preference.getMimeTypes());
            assertEquals(i >= 3 ? "en.1" : null, preference.getAudioId());
            assertEquals(i >= 4 ? "en" : null, preference.getAudioLanguage());
            assertEquals(i >= 5, preference.getAudioOnly());
            assertEquals(i >= 6 ? Collections.singleton("avc1") : Collections.emptySet(), preference.getVideoCodecs());
            assertEquals(i >= 7 ? Collections.singleton("mp4a") : Collections.emptySet(), preference.getAudioCodecs());
            assertEquals(i >= 8 ? 30 : Integer.MAX_VALUE, preference.getMaxFps());
            assertEquals(i >= 9 ? VideoRange.SDR : null, preference.getDynamicRange());
            assertEquals(i < 10, preference.getAllowSabr());
        }
    }
}
