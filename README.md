# Flipflix research prototype

Flipflix is a separate, native Android TV / Fire TV catalog prototype. It tests how much of a streaming website can be recreated with native remote navigation while leaving playback in a third-party embedded video frame.

## What works

- Native Home, Search, Movies, TV Shows, Anime, Sports, and My Space screens
- D-pad focus, title details, TV seasons and episode selection
- Native playback source selector with three observed movie/TV hosts: Bolt, Fast, and Rift
- Sports listings with a native wrapper around the observed embed provider
- TV launcher entry, no touchscreen requirement, landscape layout
- Embedded playback stays in the video area; pop-up windows and top-level off-site navigation are suppressed

The app reads public catalog responses from `pantyflix.com` and poster images from TMDB. These are third-party services that may change or restrict access. The Anime screen uses a broad animation discovery query, not the exact website anime catalog. My Space account features are not implemented. Playback availability and D-pad support inside the embedded provider are outside this app's control.

This is an educational prototype, not an official Pantyflix client. Metadata, artwork, branding, and streams must be used under their respective permissions and terms before any broader distribution.

## Build

The shared SDK is `/home/okonu/Android/Sdk`. No SDK copy is stored in this project.

```sh
ANDROID_HOME=/home/okonu/Android/Sdk ./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

This experimental release variant is non-debuggable but signed by the local Android debug key. Keep that key for installable updates; a production app should use a dedicated release key. GitHub releases use the asset name `Flipflix.apk`, allowing a stable `/releases/latest/download/Flipflix.apk` download URL.
