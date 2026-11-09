# Independent MediaServiceCore

Pure JVM Kotlin core. Kotlin 2.2.20, JVM 17. The default build includes only `common`.

## Build and verify

Use JDK 21 with the checked-in Gradle 8.11.1 wrapper:

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk21'
.\gradlew.bat :common:check :common:jar
```

The JAR is in `common/build/libs`. Kotlin stdlib 2.2.20 is its runtime dependency. `verifyStandalone` compiles and runs `src/consumer/java/example/Standalone.java` against the JAR plus runtime dependencies, without the main class directories, Android, SharedModules or Litube.

## Maven releases

JitPack runs `:common:check` and `:common:publishToMavenLocal` for the requested Git tag. The publication includes the core JAR, source JAR and a POM with runtime dependencies.

```kotlin
repositories { maven { url = uri("https://jitpack.io") } }
dependencies { implementation("com.github.HydeYYHH:MediaServiceCore:<tag>") }
```

Pin a release tag instead of a moving branch or local source directory. The artifact requires Java 17 or an Android toolchain that supports Java 17 bytecode.

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

`PlaybackSession.load(videoId, context, preference)` shares concurrent work and bounds results by account, credential digest, visitor, locale, generation, script and preferences. Results retain `PlayerAnswer`, selected URLs and `PlayerSource`. `invalidate()` fences stale cache writes. Playback results expire when their response or bound Token expires.

- `PlaybackPreference` selects height, MIME families, codec prefixes, SDR/HDR, frame rate, audio ID/language or audio-only operation. A client with no matching combination does not stop fallback. Explicit format limits reject opaque manifests whose capabilities cannot be checked; unconstrained playback retains manifest fallback.
- Optional `PlayerScriptProvider` supports script rotation. `JvmPlayerScripts(http)` reads the embed page and script on the JVM; `fresh = true` reloads them and clears cached Tokens. A fixed `PlayerScript` remains supported.
- `PlayerSolver` receives the exact script URL/source plus batched signature and throttle inputs. `CachedPlayerSolver` separates script contents.
- `PlayerTokenProvider` receives client, video, context and PLAYER/MEDIA/SUBTITLE purpose. Token caches honor binding and expiry.
- `PlaybackSession.registerRequest(load, parentUrl, childUrl)` attributes HLS/DASH child requests to a known source in the same session. `PlaybackRecovery.recover` accepts only registered URLs and retains the original preferences; one source refresh and one fallback walk are available per video/context.
- HLS/DASH URLs and SABR configuration are exposed. Decoding, segment transport, the JavaScript engine and Token minting are supplied by the caller.

A candidate URL is not evidence of media byte reads or successful rendering.

## Other capabilities

- Browse: `loadHome`, `loadChannel`, `loadLibrary`, `loadSubscribedChannels`, `loadWatch`, `loadPlaylist`. Modern lockups and Shorts sequences preserve `ActionEndpoint` payloads. Typed browse/search loaders report unrecognized nonempty response shapes instead of silently returning an empty page.
- Watch: `WatchPage.queue` preserves duplicate playlist entries, indexes and previous/next endpoints; `resumeSeconds` retains the reported resume position.
- Search: `loadSearch`, `loadSearchSuggestions`, `searchFilters`, `SearchHistory`.
- Actions: `voteVideo`, `subscribeChannel`, `WatchHistory`, comment and playlist overloads accepting `RequestContext`. `ReadResult.ok` means no request/protocol error; inspect `ActionReply.confirmed` for explicit server confirmation. Unknown acknowledgements remain `UNCONFIRMED`.
- Comments: first page, opaque sort keys, continuation/replies, entity/view/toolbar association, rich runs, author flags and write results.
- Notifications: all sections, continuation, hiding, preferences and processed feedback.
- Chat: `ChatConnection` preserves ordered `ChatAction` events and the server's polling delay. `ChatReplay.read(positionMs)` returns `ChatEvent` offsets; `seek(positionMs)` resets pagination/deduplication. The caller schedules events against playback.
- Lounge: `LoungeConnection` uses injected `LoungeTransport`; `LoungeReporter` sends actual navigation, playback identity and request sequence state. Listen methods block on the caller's thread; no background worker is started. Close streams and honor cancellation in injected I/O.
- Extensions: `loadSponsorSegments`, `loadDeArrow`, `deArrowThumbnailUrl`, `loadDislikes`, `loadRssFeed`, `ChannelGroups`.

Third-party helpers read data and return candidates. They do not skip playback, replace UI content or submit votes automatically. Channel-group and search-history persistence are injectable. `readChannelGroups` and `ChannelGroups.import` accept MSC, PocketTube, NewPipe and GrayJay exports, including BOM and encoded GrayJay objects. Non-YouTube entries are skipped in mixed NewPipe/GrayJay exports; @handle resolution requires the optional `resolve` callback.

Connection close wakes default polling/reconnect waits and stops further delivery. Injected waiting and network I/O must provide their own cancellation/timeouts. Existing Kotlin entrypoints remain available, and JVM overloads retain older constructor/loading argument counts; the Java consumer verifies these against the packaged JAR.

## Online reads and validation limits

```powershell
.\gradlew.bat :common:onlineReadTest
```

This separately enabled task performs only public YouTube search/home/oEmbed/player-script, SponsorBlock and RYD reads. The default check runs regression tests and the packaged Java consumer; it performs no external account mutations. Historical fixtures are sanitized projections of checked-in upstream responses and cover their recorded shapes, not every current YouTube variant.

See [DEVELOPMENT.md](DEVELOPMENT.md) for mechanisms, names, per-commit references, tests and the online result. OAuth with real credentials, authenticated writes, real JS/Token providers, continuous playback and actual Lounge devices require separate integration acceptance.
