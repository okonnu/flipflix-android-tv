# Flipflix research prototype

Flipflix is a separate, native Android TV / Fire TV catalog prototype. It tests how much of a streaming website can be recreated with native remote navigation while leaving playback in a third-party embedded video frame.

## What works

- One TV-first Home screen with Trending, Movies, TV Shows, Anime, and Live Sports rows instead of a left navigation rail
- Horizontal catalog rows fetch more pages as the remote approaches the end; finite live sports wraps to the first event
- Selectable row headings open full-page collection grids with a Load more control; Search remains a top action
- Predictable D-pad navigation across poster rows and a high-contrast TV focus state
- Native title details, TV seasons, and episode selection
- Bolt, Rift, and Fast choices directly on the title screen, before playback
- Rift attempts native HLS/MP4 playback when it advertises a direct stream, with an embedded fallback if native playback fails. Bolt and Fast use embedded players
- Sports listings with a native wrapper around the observed embed provider
- TV launcher entry, no touchscreen requirement, landscape layout
- Playback opens fullscreen with a remote-operated overlay for previous/next episode, source, retry, and exit fullscreen. Press an arrow to reveal it after it hides. Remote Back exits fullscreen; Back again returns to the title. Pop-up windows and top-level off-site navigation are suppressed

The app reads public catalog responses from `pantyflix.com` and poster images from TMDB. These are third-party services that may change or restrict access. The Anime row uses a broad animation discovery query, not the exact website anime catalog. Account features are not implemented. Playback availability and D-pad support inside the embedded providers are outside this app's control. Native Rift playback depends on the provider continuing to expose a compatible stream and the TV supporting its codec.

This is an educational prototype, not an official Pantyflix client. Metadata, artwork, branding, and streams must be used under their respective permissions and terms before any broader distribution.

## Build

The shared SDK is `/home/okonu/Android/Sdk`. No SDK copy is stored in this project.

```sh
ANDROID_HOME=/home/okonu/Android/Sdk ./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

This experimental release variant is non-debuggable but signed by the local Android debug key. Keep that key for installable updates; a production app should use a dedicated release key. GitHub releases use the asset name `app-release.apk`, allowing a stable `/releases/latest/download/app-release.apk` download URL.
