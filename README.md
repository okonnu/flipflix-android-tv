# Flipflix research prototype

Flipflix is a separate, native Android TV / Fire TV catalog prototype. It tests how much of a streaming website can be recreated with native remote navigation while leaving playback in a third-party embedded video frame.

## What works

- One TV-first Home screen with Trending, Movies, TV Shows, Anime, and Live Sports rows instead of a left navigation rail
- Horizontal catalog rows fetch more pages as the remote approaches the end; finite live sports wraps to the first event
- Home rows display four complete artwork cards per horizontal page, including the last partial batch. Titles and metadata sit over the artwork; section headings remain clickable without arrow characters
- Selectable row headings open full-page collection grids with a Load more control; Search remains a top action
- Up from a Home card selects its section heading. Up/Down on headings selects other sections; Right enters the cards, and OK opens the selected section
- Predictable D-pad navigation across poster rows and a high-contrast TV focus state
- Native title details, TV seasons, and episode selection
- Edge-to-edge title artwork behind the detail screen, with readable text and source buttons layered over a soft dark gradient
- Bolt, Rift, and Fast choices directly on the title screen, before playback
- Rift attempts native HLS/MP4 playback when it advertises a direct stream, with an embedded fallback if native playback fails. Bolt and Fast use embedded players
- Sports listings with a native wrapper around the observed embed provider
- TV launcher entry, no touchscreen requirement, landscape layout
- Playback opens fullscreen with a compact Netflix-inspired timeline and icon row. The controls hide after **5 seconds without remote input**, even while a button has focus. Arrow or OK reveals them without accidentally activating the selected action. Menus stay open while choosing an option
- Play/pause, back/forward 10 seconds, mute, subtitles, video quality, previous/next episode, source, retry, and exit fullscreen. Up focuses the timeline; left/right on the timeline seeks 10 seconds; Down returns to the buttons. Dedicated remote media keys also work in fullscreen
- Rift direct streams use Media3/ExoPlayer with native captions and supported quality tracks. Embedded sources use a small, event-driven HTML video bridge, with no continuous DOM scanning. Subtitle and quality choices come from the actual stream/provider, not a hard-coded list. When a provider does not expose an option, the menu explains that limitation
- Remote Back exits fullscreen; Back again returns to the title. Pop-up windows and top-level off-site navigation are suppressed

The app reads public catalog responses from `pantyflix.com` and poster images from TMDB. These are third-party services that may change or restrict access. The Anime row uses a broad animation discovery query, not the exact website anime catalog. Account features are not implemented. Native Rift playback depends on the provider continuing to expose a compatible stream and the TV supporting its codec. Embedded controls require a compatible Android System WebView and an accessible HTML video in an allowed provider frame. Nested players on other domains, DRM/custom players, or unavailable provider tracks may limit control. On older WebViews without document-start injection, the provider loads directly and the bridge attaches after the page finishes loading.

This is an educational prototype, not an official Pantyflix client. Metadata, artwork, branding, and streams must be used under their respective permissions and terms before any broader distribution.

## Visual styling

The existing native components use the community [Netflix Design System 2024 reference](https://www.figma.com/design/mknKOvsPMV2oLlYXGZwUO9/?node-id=202-19090): #141414 backgrounds, #181818 surfaces, #E50914 accents, white primary playback actions, gray secondary actions, 4dp corners, compact landscape catalog tiles, gradient title previews, and thumbnail episode rows. Android system sans-serif fonts substitute for the reference's Netflix Sans. TV focus uses a visible white outline, adapting the reference's mouse-hover states for remote navigation. Flipflix keeps its own name and existing feature set.

## Build instructions

The shared SDK is `/home/okonu/Android/Sdk`. No SDK copy is stored in this project.

```sh
ANDROID_HOME=/home/okonu/Android/Sdk ./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

This experimental release variant is non-debuggable but signed by the local Android debug key. Keep that key for installable updates; a production app should use a dedicated release key. GitHub releases use the asset name `app-release.apk`, allowing a stable `/releases/latest/download/app-release.apk` download URL.

## Playback regression tests

`PlaybackControlsTest` runs on the Android TV emulator. It covers focused-button timeout, wake without accidental activation, D-pad seeking, menu timeout suspension, native playback/seek/mute/captions/track selection, an actual HTML video controlled through the WebView message bridge, whole-card carousel pages/top-border clipping, and opening every Home heading with the remote. The fixture is test-only and is not shipped in the release APK.

```sh
ANDROID_HOME=/home/okonu/Android/Sdk ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```
