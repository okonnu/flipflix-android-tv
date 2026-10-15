# Flipflix research prototype

Flipflix is a separate, native Android TV / Fire TV catalog prototype. It tests how much of a streaming website can be recreated with native remote navigation while leaving playback in a third-party embedded video frame.

## What works

- One TV-first Home screen with Trending, Movies, TV Shows, Anime, and Live Sports rows instead of a left navigation rail
- Horizontal catalog rows fetch more pages as the remote approaches the end; finite live sports wraps to the first event
- Home rows display four complete artwork cards per horizontal page, including the last partial batch. Titles and metadata sit over the artwork; section headings remain clickable without arrow characters
- Horizontal page changes, vertical page/row navigation, and season choices glide into place with a 220-320ms decelerating animation at the normal system animation scale. Focus changes immediately; repeated presses retarget from the current position instead of queuing animations. Home headings stay visible with their row, final cards remain whole, and motions are cancelled when leaving a screen or starting a touch gesture
- Selectable row headings open full-page collection grids with a Load more control; Search remains a top action
- Movies and TV Shows have native remote-friendly Genre, Year, Minimum rating, Country, and Sort by menus plus Reset filters. Combine filters across the full catalog, with Most popular, Highest rated, Newest first, or Oldest first sorting. Movie year means original release year; TV year means first aired. Each section remembers its own choices across details, playback, and app restarts; Home rows stay unfiltered
- Left/Right moves between filter buttons, OK opens a selection list, Back cancels it, and Down enters results. Up from the first card row returns to the last-used filter. Loading, no matches, and retry states never trap remote focus. Changing filters resets pagination and ignores late responses from the previous query
- Up from a Home card selects its section heading. Up/Down on headings selects other sections; Right enters the cards, and OK opens the selected section
- Predictable D-pad navigation across poster rows and a high-contrast TV focus state
- Native title details, TV seasons, and episode selection
- Movie and series details show their genres beside the year, type, and rating. Known catalog genres appear immediately and are refreshed by the existing details request; missing genres are omitted, with no extra network requests or polling
- Edge-to-edge title artwork behind the detail screen, with readable text and source buttons layered over a soft dark gradient
- Movie and series details automatically look up official English YouTube trailers (or teasers) using the title's TMDB ID after a short idle delay. A small top-center "Preview is loading..." label appears in unused header space during startup, clear of movie controls and series episode/season rows. Actual YouTube PLAYING events trigger a 400ms eased crossfade from the artwork/controls to an unobstructed YouTube player with sound. The player starts unmuted at full player volume; the TV's system volume and mute are unchanged, and remote volume keys work normally. No guessed buffering percentage or scrolling-card video downloads
- Arrow, OK, Back, or a media key stops the preview and smoothly restores the artwork and controls without accidentally activating a source; the next press works normally. A trailer plays once per details visit. Missing/unavailable trailers, blocked autoplay, startup/rebuffer timeouts, and unsupported WebViews leave the normal page usable. Navigating, opening a dialog, or backgrounding the app releases the preview; system reduced-motion settings disable automatic previews
- Bolt, Rift, and Fast choices directly on the title screen, before playback
- Rift attempts native HLS/MP4 playback when it advertises a direct stream, with an embedded fallback if native playback fails. Bolt and Fast use embedded players
- Sports listings with a native wrapper around the observed embed provider. Sports load the provider directly, without an outer iframe. A narrow compatibility fix handles embed.st's failed inline-PDF probe, which otherwise misidentifies Android WebView as a sandboxed iframe. It does not remove sandbox attributes, disable actual iframe restrictions, intercept video requests, or scan the DOM. Remote Back returns to Live Sports
- Sports automatically attempt to start the provider's JW Player when ready. Startup uses a bounded set of checks over 20 seconds, stops after a start attempt, and never continually clicks or overrides later pauses. Flipflix's native Play button and the remote Play key can retry before video metadata exists; no pointer is needed for the initial Play screen
- TV launcher entry, no touchscreen requirement, landscape layout
- Playback is always fullscreen with a compact Netflix-inspired timeline and icon row. There is no separate windowed player, permanent header, or episode footer. The controls hide after **5 seconds without remote input**, even while a button has focus. Arrow or OK reveals them without accidentally activating the selected action. Menus stay open while choosing an option
- Play/pause, back/forward 10 seconds, mute, subtitles, video quality, previous/next episode, source, retry, and Close player. Up focuses the timeline; left/right on the timeline seeks 10 seconds; Down returns to the buttons. Dedicated remote media keys also work in fullscreen
- A Fill screen / Fit picture button beside Close player. Fill scales the video proportionally and crops the edges to cover the player; Fit restores the whole picture. Each new playback starts in Fit. Native playback uses Media3's crop mode; accessible embedded videos use reversible, video-only styling, without changing page zoom or scaling the Android view. Bars encoded into the video itself may remain
- Rift direct streams use Media3/ExoPlayer with native captions and supported quality tracks. Embedded sources use a small, event-driven HTML video bridge, with no continuous DOM scanning. Subtitle and quality choices come from the actual stream/provider, not a hard-coded list. When a provider does not expose an option, the menu explains that limitation
- Remote Back or Close player stops playback and returns directly to the title details (or Live Sports). Back inside a source/settings menu only dismisses that menu. Pop-up windows and top-level off-site navigation are suppressed

The app reads public catalog responses from `pantyflix.com` and poster images from TMDB. These are third-party services that may change or restrict access. The Anime row uses a broad animation discovery query, not the exact website anime catalog. Account features are not implemented. Native Rift playback depends on the provider continuing to expose a compatible stream and the TV supporting its codec. Embedded controls require a compatible Android System WebView and an accessible HTML video in an allowed provider frame. Nested players on other domains, DRM/custom players, or unavailable provider tracks may limit control. On older WebViews without document-start injection, the provider loads directly and the bridge attaches after the page finishes loading.

This is an educational prototype, not an official Pantyflix client. Metadata, artwork, branding, and streams must be used under their respective permissions and terms before any broader distribution.

### Trailer previews

`TrailerPreview` uses the existing public catalog proxy's `/api/tmdb/movie/{id}/videos` or `/api/tmdb/tv/{id}/videos` response. It selects at most three deduplicated, valid YouTube IDs marked official, preferring English trailers over teasers. It never searches by title or extracts direct YouTube streams. The standard YouTube IFrame Player API retains its controls, attribution, aspect ratio, and advertisements. Branding, titles, source buttons, loading label, and both gradients disappear after playback starts; remote interaction pauses the preview before bringing app chrome back. Loading the embedded player shares playback information with YouTube; no API credentials or account login are bundled.

Only the app-owned top-level HTML document at the Android application identity origin can report events through the origin-scoped message bridge; provider subframes have no native API access. Each preview has a short startup delay, bounded timeout/retries, and no polling, DOM scans, download cache, or idle work. Returning to the page after playback is a new details visit. Old WebViews without message-listener support keep artwork instead. Live availability still depends on YouTube, the catalog entries, region, and the device's WebView. Direct access to TMDB outside this prototype's existing proxy requires an appropriate API key and attribution.

### Sports compatibility notes

The observed `Remove sandbox attributes on the iframe tag` warning comes from a hidden `object#sandDetect` PDF failing to load, not an iframe added by Flipflix. `sports-compat.js` only handles that exact PDF error on a top-level HTTPS `embed.st/embed/` page; real iframes, other documents, and unrelated errors are left alone. Current WebViews install the handler at document start. Older WebViews insert it into this provider's initial HTML before its scripts, preserving response security headers and leaving media traffic untouched.

This fixes the false warning, not all provider/browser incompatibilities. The API 30 TV emulator's WebView 90 also fails on the provider's newer WebAssembly code, so removing the warning there does not prove live-video playback. Live playback still depends on a sufficiently current WebView, a working provider stream, and supported codecs. No WebView engine or device-wide settings are bundled or changed by this update.

`sports-start.js` is limited to the same top-level sports documents. It targets JW Player's documented `jwplayer('player').play()` API, with a fallback to the observed JW display Play button inside `#player`. It never taps arbitrary screen coordinates or advertisement links. A rejected autoplay can be retried from the native Play control; provider or network failures still require Retry or another stream. The early-injection path works on old WebViews as well as those supporting document-start scripts.

## Visual styling

The existing native components use the community [Netflix Design System 2024 reference](https://www.figma.com/design/mknKOvsPMV2oLlYXGZwUO9/?node-id=202-19090), with the requested pure #000000 page/window backgrounds and artwork gradients fading into black. Dialog/input surfaces stay #181818, with #E50914 accents, white primary playback actions, gray secondary actions, 4dp corners, compact landscape catalog tiles, gradient title previews, and thumbnail episode rows. Android system sans-serif fonts substitute for the reference's Netflix Sans. TV focus uses a visible white outline, adapting the reference's mouse-hover states for remote navigation. Flipflix keeps its own name and existing feature set.

## TV scrolling

The scrolling behavior follows the focus-aware alignment and bounded-pending-movement principles used by [Android Leanback](https://developer.android.com/reference/androidx/leanback/widget/BaseGridView) and [DpadRecyclerView](https://rubensousa.github.io/DpadRecyclerView/recipes/scrolling/). This app retains its existing views and four-card paging rather than adding or migrating to either library.

`TvScrollMotion` animates only scroll offsets using Android's frame-synced `ValueAnimator`, which honors the system animation scale. There are no timers running while idle and no per-frame layout or network operations. `TvHorizontalScrollView` and `TvScrollView` suppress the framework's immediate focus-reveal jump, retain focus bookkeeping, and animate a single current destination. The vertical scroller groups Home headings with their carousel. Initial layout and explicitly immediate visibility requests remain immediate; regular remote navigation animates. Appending catalog pages preserves the current focus and does not restart an unchanged destination.

## Build instructions

The shared SDK is `/home/okonu/Android/Sdk`. No SDK copy is stored in this project.

```sh
ANDROID_HOME=/home/okonu/Android/Sdk ./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

This experimental release variant is non-debuggable but signed by the local Android debug key. Keep that key for installable updates; a production app should use a dedicated release key. GitHub releases use the asset name `app-release.apk`, allowing a stable `/releases/latest/download/app-release.apk` download URL.

## Playback regression tests

`PlaybackControlsTest` runs on the Android TV emulator. It covers focused-button timeout, wake without accidental activation, D-pad seeking, menu timeout suspension, native playback/seek/mute/captions/track selection, remote Fit/Fill toggling and native crop geometry, an actual HTML video controlled and resized through the WebView message bridge, whole-card carousel pages/top-border clipping, and opening every Home heading with the remote. The fixture is test-only and is not shipped in the release APK.

`SportsCompatibilityTest` checks strict URL scoping, early script ordering, suppression of only the known PDF error, and preservation of unrelated errors and iframe behavior. Startup fixtures also cover delayed JW readiness, one-shot activation, exact Play targeting without ad clicks, bounded retries, cancellation, autoplay rejection, and native Play before video metadata or bridge messages exist. These run in the emulator's real WebView with a deterministic clock; they do not claim that a third-party live stream is available.

`PlayerNavigationTest` exercises the real Activity: fullscreen-only movie/TV/sports playback, single-Back return, source/menu/episode/retry navigation, native-video geometry and cleanup, provider custom fullscreen transitions, and rejection of late callbacks after closing playback.

`CatalogFiltersTest` covers movie/series query parameters, saved choices, remote filter menus, focus with empty/loading/error states, filtered pagination, reset, stale response rejection, and detail-screen genre metadata using a deterministic catalog fixture.

`TvScrollingTest` checks actual intermediate scroll frames, monotonic movement, quick direction changes, whole cards at rest, simultaneous vertical/horizontal movement, heading visibility, pagination during animation, season choices, and cancellation when a screen is removed. It runs on the TV emulator with deterministic native-view fixtures.

`TrailerPreviewTest` exercises the real WebView wrapper/message bridge, sound-enabled startup, system volume-key passthrough, official trailer selection, loading label, PLAYING-gated fade, animated return, key-up/held-key consumption, focus restoration, failures/timeouts, lifecycle cleanup, and subframe-message rejection. Its optional `liveYouTubeProbe` diagnostic runs only when passed `-Pandroid.testInstrumentationRunnerArguments.liveTrailer=<YouTube ID>` and reports observed live playback separately from deterministic regression assertions. Adding `-Pandroid.testInstrumentationRunnerArguments.requireLiveAudio=true` asserts that the actual YouTube player reaches PLAYING with mute off and nonzero player volume; it does not measure sound from a physical TV's speakers.

```sh
ANDROID_HOME=/home/okonu/Android/Sdk ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```
