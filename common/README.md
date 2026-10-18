# Independent MediaServiceCore

Pure JVM Kotlin core. Kotlin 1.3.72, JVM 8. The default build includes only `common`.

## Build and verify

Use JDK 8 with the checked-in Gradle 5.4.1 wrapper:

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk8'
.\gradlew.bat :common:check :common:jar
```

The JAR is `common/build/libs/common.jar`. Kotlin stdlib 1.3.72 is its runtime dependency. `verifyStandalone` compiles and runs `src/consumer/java/example/Standalone.java` against the JAR plus runtime dependencies, without the main class directories, Android, SharedModules or Litube.

Legacy Android modules are opt-in with `-PincludeLegacyModules=true` and require their original SharedModules dependencies. Their build is outside this independent-core validation.

## Request and account state

BrowsePage.messages retains server-supplied empty-state text, including a fresh anonymous home feed asking the user to search.

All public APIs are in `com.liskovsoft.youtubeapi.videoinfo.V2`. New typed loaders return `ReadResult<T>`: an empty collection is a successful empty result; a failure carries `RequestError` with its kind, status and optional retry delay. Old `PlayerQuery` callbacks remain available.

```kotlin
val http = JvmHttpClient()
val search = loadSearch("Kotlin", context = RequestContext(language = "zh", region = "CN"), http = http)
if (search.ok) {
    val next = search.value!!.nextKey
    // Supply next as continuation to loadSearch for the next page.
}
```

OAuth follows the pinned upstream `auth/V2` wire contract. Supply your client configuration through `SignInClient`, call `loadSignInCode` and `pollSignIn`, and manage expiry through an `AccountToken` instance. `AccountSession` reads/selects account and brand identities. Persist credentials outside the library; construct a new immutable request context after token refresh. The library supplies no login UI and performs no account writes while merely reading pages.

## Playback and challenges

`PlaybackSession.load(videoId, context, preference)` shares concurrent work and bounds results by account/visitor/generation, script and preferences. Results retain `PlayerAnswer`, selected URLs and `PlayerSource`. `invalidate()` fences stale cache writes.

- `PlaybackPreference` selects height, MIME families, audio ID/language or audio-only operation. Adaptive audio/video is preferred when a complete matching pair exists.
- `PlayerSolver` receives the exact script URL/source plus batched signature and throttle inputs. `CachedPlayerSolver` separates script contents.
- `PlayerTokenProvider` receives client, video, context and PLAYER/MEDIA/SUBTITLE purpose. Token caches honor binding and expiry.
- `PlaybackRecovery.recover` accepts only an explicitly attributed URL from `PlayerSource.urls`; one source refresh and one fallback walk are available per video/context. HLS/DASH child requests must be explicitly attributed by the caller; unknown URLs are rejected.
- HLS/DASH URLs and SABR configuration are exposed. Decoding, segment transport, the JavaScript engine and Token minting are supplied by the caller.

A candidate URL is not evidence of media byte reads or successful rendering.

## Other capabilities

- Browse: `loadHome`, `loadChannel`, `loadLibrary`, `loadSubscribedChannels`, `loadWatch`, `loadPlaylist`.
- Search: `loadSearch`, `loadSearchSuggestions`, `searchFilters`, `SearchHistory`.
- Actions: `voteVideo`, `subscribeChannel`, `WatchHistory`, comment and playlist overloads accepting `RequestContext`.
- Comments: first page, opaque sort keys, continuation/replies, rich runs and write results.
- Notifications: all sections, continuation, hiding, preferences and processed feedback.
- Chat: `ChatConnection` preserves ordered `ChatAction` events and the server's polling delay.
- Lounge: `LoungeConnection` uses injected `LoungeTransport`; `LoungeReporter` sends actual navigation, playback identity and request sequence state. Listen methods block on the caller's thread; no background worker is started. Close streams and honor cancellation in injected I/O.
- Extensions: `loadSponsorSegments`, `loadDeArrow`, `deArrowThumbnailUrl`, `loadDislikes`, `loadRssFeed`, `ChannelGroups`.

Third-party helpers read data and return candidates. They do not skip playback, replace UI content or submit votes automatically. Channel-group and search-history persistence are injectable.

## Online reads and validation limits

```powershell
.\gradlew.bat :common:onlineReadTest
```

This separately enabled task performs only public YouTube search/home/oEmbed, SponsorBlock and RYD reads. Default tests perform no external account mutations. Historical fixtures are sanitized projections of checked-in upstream responses and cover their recorded shapes, not every current YouTube variant.

See [DEVELOPMENT.md](DEVELOPMENT.md) for mechanisms, names, per-commit references, tests and the online result. OAuth with real credentials, authenticated writes, real JS/Token providers, continuous playback and actual Lounge devices require separate integration acceptance.
