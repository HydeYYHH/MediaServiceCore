package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

/** Exercise the emitted JVM overloads with real responses, rather than only inspecting signatures. */
public class PublicContractJavaApiTest {
    private static final String DIRECT = "{\"streamingData\":{\"formats\":[{\"url\":\"https://media.example/low\",\"height\":360},{\"url\":\"https://media.example/high\",\"height\":1080}]}}";
    private static final String BOUND = "{\"streamingData\":{\"formats\":[{\"signatureCipher\":\"s=raw&url=https%3A%2F%2Fmedia.example%2Fbound\"}]}}";

    private static PlayerLoad loaded(ReadResult<PlayerLoad> result) {
        assertTrue(String.valueOf(result.getError()), result.getOk());
        assertNull(result.getError());
        assertFalse(result.getValue().getAnswer().getMuxedFormats().isEmpty());
        return result.getValue();
    }

    @Test public void playerOverloadsPreserveDefaultsAndUseEveryExplicitDependency() {
        RequestContext context = new RequestContext();
        assertEquals("invalid_video_id", PlayerContextKt.loadPlayer("invalid video", context).getError().getCode());
        HttpClient direct = request -> new HttpReply(200, DIRECT, Collections.emptyMap());
        PlayerScript script = new PlayerScript("https://www.youtube.com/base.js", "signatureTimestamp:42");
        AtomicInteger solves = new AtomicInteger();
        PlayerSolver solver = request -> {
            solves.incrementAndGet();
            assertSame(script, request.getScript());
            assertEquals(Collections.singleton("raw"), request.getSignatures());
            return new PlayerChallenge(Collections.singletonMap("raw", "solved"), Collections.emptyMap());
        };
        assertEquals("https://media.example/high", loaded(PlayerContextKt.loadPlayer("video", context, direct)).getAnswer().getMuxedFormats().get(1).getUrl());
        assertTrue(loaded(PlayerContextKt.loadPlayer("video", context, direct, script)).getAnswer().getHasDirectUrl());
        loaded(PlayerContextKt.loadPlayer("video", context, direct, script, solver));
        assertEquals(0, solves.get()); // Direct formats do not force a challenge solver call.

        List<TokenPurpose> minted = new ArrayList<>();
        PlayerTokenProvider tokens = request -> {
            minted.add(request.getPurpose());
            assertEquals("video", request.getVideoId());
            return new PlayerToken(request.getPurpose().name(), Long.MAX_VALUE);
        };
        HttpClient bound = request -> {
            assertTrue(request.getBody().contains("\"poToken\":\"PLAYER\""));
            return new HttpReply(200, BOUND, Collections.emptyMap());
        };
        PlayerLoad solved = loaded(PlayerContextKt.loadPlayer("video", context, bound, script, solver, tokens));
        assertTrue(solved.getAnswer().getMuxedFormats().get(0).getUrl().contains("sig=solved"));
        assertTrue(solved.getAnswer().getMuxedFormats().get(0).getUrl().contains("pot=MEDIA"));
        assertEquals(1, solves.get());
        assertEquals(Arrays.asList(TokenPurpose.PLAYER, TokenPurpose.MEDIA), minted);

        PlayerClientQueues queues = new PlayerClientQueues(PlayerClient.WEB);
        assertEquals(PlayerClient.WEB, loaded(PlayerContextKt.loadPlayer("video", context, direct, script, null, null, queues)).getClient());
        Set<PlayerClient> excludeWeb = Collections.singleton(PlayerClient.WEB);
        assertEquals(PlayerClient.VISIONOS, loaded(PlayerContextKt.loadPlayer("video", context, direct, script, null, null, queues, excludeWeb)).getClient());
        Function0<Long> clock = () -> 1000L;
        HttpClient expiring = request -> new HttpReply(200,
            "{\"streamingData\":{\"expiresInSeconds\":\"2\",\"formats\":[{\"url\":\"https://media.example/stream\"}]}}", Collections.emptyMap());
        assertEquals(Long.valueOf(3000L), loaded(PlayerContextKt.loadPlayer("video", context, expiring, script, null, null, queues, Collections.emptySet(), clock)).getAnswer().getExpiresAtMs());
        PlaybackPreference low = new PlaybackPreference(480);
        PlayerLoad preferred = loaded(PlayerContextKt.loadPlayer("video", context, direct, script, null, null, queues, Collections.emptySet(), clock, low));
        assertEquals("https://media.example/low", PlayerPlaybackKt.selectPlayback(preferred.getAnswer(), low).getVideoUrl());

        List<PlayerLoadAttempt> attempts = new ArrayList<>();
        Function1<PlayerLoadAttempt, Unit> onAttempt = attempt -> { attempts.add(attempt); return Unit.INSTANCE; };
        loaded(PlayerContextKt.loadPlayer("video", context, direct, script, null, null, queues, Collections.emptySet(), clock, low, onAttempt));
        assertEquals(1, attempts.size());
        assertEquals(PlayerClient.WEB, attempts.get(0).getClient());
        assertEquals(0L, attempts.get(0).getElapsedMs());
        PlayerLoad deferred = loaded(PlayerContextKt.loadPlayer("video", context, direct, script, null, null, queues, Collections.emptySet(), clock, low, onAttempt, Collections.singleton(PlayerClient.WEB)));
        assertEquals(PlayerClient.VISIONOS, deferred.getClient());

        AtomicInteger scriptReads = new AtomicInteger();
        Function0<PlayerScript> scriptLoader = () -> { scriptReads.incrementAndGet(); return script; };
        HttpClient lazyBound = request -> new HttpReply(200, BOUND, Collections.emptyMap());
        loaded(PlayerContextKt.loadPlayer("video", context, lazyBound, null, solver, null, queues, Collections.emptySet(), clock, new PlaybackPreference(), onAttempt, Collections.emptySet(), scriptLoader));
        assertEquals(1, scriptReads.get());
        assertEquals(2, solves.get());
    }

    @Test public void sessionOverloadsProduceUsablePlaybackAndHonorFreshAndExclusionOptions() {
        AtomicInteger requests = new AtomicInteger();
        HttpClient http = request -> {
            requests.incrementAndGet();
            return new HttpReply(200, DIRECT, Collections.emptyMap());
        };
        RequestContext context = new RequestContext();
        PlayerScript script = new PlayerScript("https://www.youtube.com/base.js", "source");
        PlayerSolver solver = request -> { fail("Direct URLs need no solver"); return null; };
        PlayerTokenProvider tokens = request -> null;
        Function0<Long> clock = () -> 1000L;
        AtomicInteger scriptReads = new AtomicInteger();
        PlayerScriptProvider scripts = new PlayerScriptProvider() {
            @Override public ReadResult<PlayerScript> script(String videoId, RequestContext identity, boolean fresh) {
                scriptReads.incrementAndGet();
                return new ReadResult<>(script, null);
            }
            @Override public String cacheKey(String videoId, RequestContext identity) { return "known-revision"; }
        };
        List<PlayerLoadAttempt> attempts = new ArrayList<>();
        Function1<PlayerLoadAttempt, Unit> onAttempt = attempt -> { attempts.add(attempt); return Unit.INSTANCE; };

        assertEquals("invalid_video_id", new PlaybackSession().load("invalid video").getError().getCode());
        assertTrue(new PlaybackSession(http).load("video").getOk());
        assertTrue(new PlaybackSession(http, script).load("video").getOk());
        assertTrue(new PlaybackSession(http, script, solver).load("video").getOk());
        assertTrue(new PlaybackSession(http, script, solver, tokens).load("video").getOk());
        assertTrue(new PlaybackSession(http, script, solver, tokens, clock).load("video").getOk());
        assertTrue(new PlaybackSession(http, script, solver, tokens, clock, scripts).load("video").getOk());
        PlaybackSession session = new PlaybackSession(http, script, solver, tokens, clock, scripts, onAttempt);
        assertTrue(session.load("video").getOk());
        assertTrue(session.load("video", context).getOk());
        PlaybackPreference low = new PlaybackPreference(480);
        assertEquals("https://media.example/low", session.load("video", context, low).getValue().getPlayback().getVideoUrl());
        int beforeFresh = requests.get();
        assertTrue(session.load("video", context, low, true).getOk());
        assertEquals(beforeFresh + 1, requests.get());
        assertEquals(0, scriptReads.get()); // The direct-URL leader does not resolve its known script revision.
        Set<PlayerClient> excluded = Collections.singleton(PlayerClient.VISIONOS);
        assertNotEquals(PlayerClient.VISIONOS, session.load("video", context, low, false, excluded).getValue().getClient());
        assertTrue(session.load("video", context, low, false, excluded, false).getOk());
        Set<PlayerClient> deferred = Collections.singleton(PlayerClient.WEB);
        assertNotEquals(PlayerClient.WEB, session.load("video", context, low, false, excluded, false, deferred).getValue().getClient());
        assertFalse(attempts.isEmpty());
        assertEquals(2, scriptReads.get()); // WEB/WEB_EMBED resolve the script before constructing their player request.
    }

    @Test public void defaultCacheQueueAndScriptProviderAreUsableWithoutAnyNetwork() {
        MemoryPlaybackCache cache = new MemoryPlaybackCache();
        cache.saveScript("script", "source");
        cache.saveSignature("script", "raw", "signature");
        cache.saveThrottle("script", "raw", "throttle");
        assertEquals("source", cache.script("script"));
        cache.clear();
        assertNull(cache.script("script"));
        assertNull(cache.signature("script", "raw"));
        assertNull(cache.throttle("script", "raw"));
        cache.saveScript("script", "new-source");
        assertEquals("new-source", cache.script("script"));

        PlayerClientQueues queues = new PlayerClientQueues();
        ClientQueueKey key = new ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD);
        List<PlayerClient> snapshot = queues.order(key);
        queues.demote(key, snapshot.get(0));
        assertEquals(snapshot.get(0), queues.order(key).get(snapshot.size() - 1));
        assertEquals(PlayerClient.VISIONOS, snapshot.get(0));
        assertEquals("invalid_video_id", new JvmPlayerScripts().script("invalid video", new RequestContext(), false).getError().getCode());
    }

    @Test public void legacyPollConstructorKeepsUnknownSelectionIneligibleForAReversibleWrite() {
        PostCommand select = new PostCommand("feedback", "\"feedbackTokens\":[\"SELECT\"]", null);
        PostCommand deselect = new PostCommand("feedback", "\"feedbackTokens\":[\"DESELECT\"]", null);
        PostPollChoice choice = new PostPollChoice("Choice", null, false, "40%", "60%", select, deselect);
        PostPoll poll = new PostPoll(Collections.singletonList(choice), "100 votes", false);
        // A host can render this older model, but cannot infer a known neutral selection from omitted flags.
        assertEquals("100 votes", poll.getTotal());
        boolean canRestoreAfterProbe = poll.getSelectionKnown() && !poll.getVoted() &&
            poll.getChoices().stream().allMatch(item -> item.getSelect() != null && item.getDeselect() != null);
        assertFalse(canRestoreAfterProbe);
        assertFalse(poll.getVoted());
    }

    @SuppressWarnings("unchecked")
    @Test public void importOverloadsMergeReplaceAndConvertExternalGroupsAtomically() throws Throwable {
        ChannelGroups groups = new ChannelGroups();
        String first = "{\"version\":1,\"groups\":[{\"id\":\"one\",\"title\":\"First\",\"channels\":[{\"id\":\"UCfirst\"}]}]}";
        String second = "{\"version\":1,\"groups\":[{\"id\":\"two\",\"title\":\"Second\",\"channels\":[]}]}";
        // `import` is a Java keyword, so method handles execute these otherwise unspellable JVM overloads.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        ReadResult<List<ChannelGroup>> initial = (ReadResult<List<ChannelGroup>>) lookup.findVirtual(ChannelGroups.class, "import",
            MethodType.methodType(ReadResult.class, String.class)).invoke(groups, first);
        assertTrue(initial.getOk());
        assertEquals("UCfirst", groups.find("one").getChannels().get(0).getId());
        ReadResult<List<ChannelGroup>> merged = (ReadResult<List<ChannelGroup>>) lookup.findVirtual(ChannelGroups.class, "import",
            MethodType.methodType(ReadResult.class, String.class, boolean.class)).invoke(groups, second, false);
        assertTrue(merged.getOk());
        assertEquals(2, groups.read().size());
        ReadResult<List<ChannelGroup>> replaced = (ReadResult<List<ChannelGroup>>) lookup.findVirtual(ChannelGroups.class, "import",
            MethodType.methodType(ReadResult.class, String.class, boolean.class, GroupFormat.class)).invoke(groups, second, true, GroupFormat.MSC);
        assertTrue(replaced.getOk());
        assertNull(groups.find("one"));
        assertEquals("two", groups.read().get(0).getId());
        String subscriptions = "{\"subscriptions\":[{\"service_id\":0,\"url\":\"https://www.youtube.com/channel/UCexternal\",\"name\":\"External\"}]}";
        ReadResult<List<ChannelGroup>> external = (ReadResult<List<ChannelGroup>>) lookup.findVirtual(ChannelGroups.class, "import",
            MethodType.methodType(ReadResult.class, String.class, boolean.class, GroupFormat.class)).invoke(groups, subscriptions, true, GroupFormat.NEWPIPE);
        assertTrue(external.getOk());
        assertEquals("UCexternal", groups.read().get(0).getChannels().get(0).getId());
    }
}
