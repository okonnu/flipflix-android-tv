# Startup media

The startup animation uses the approved geometric Darkflix logo assembly and wordmark
from the separate `darkflix-logo-preview` project. As of v0.1.26 the displayed app
name is also Darkflix. The package, signing key, and stored viewing history are unchanged.

## Soundtrack

- Track: Epic Cinematic Logo Reveal
- Creator: BreakzStudios
- Pixabay item: 333643
- Source: https://pixabay.com/sound-effects/film-special-effects-epic-cinematic-logo-reveal-333643/
- License: https://pixabay.com/service/license-summary/
- Full terms: https://pixabay.com/service/terms/
- User approved the track and accepted its Content License for the preview before
  asking to apply that combined animation to the app.

The track is incorporated into an eight-second audiovisual composition, delayed
by 1.920 seconds to align its impact with the logo assembly at 4.200 seconds,
attenuated by 2 dB, and faded to silence during the final 800 ms. The unmodified
stock audio is not distributed separately. This does not assert exclusive rights
to the soundtrack or register it as a sonic trademark.

## App transition

`StartupLogo` draws all four red pieces and the lettering from Android vector
paths, at the actual app rendering resolution. There are no startup bitmap assets,
video scaling, or video decoding. A 4K panel can still receive a lower-resolution
app surface from its TV box; this implementation does not change device-wide
display settings.

The eight-second AAC stereo soundtrack in `app/src/main/res/raw/darkflix_startup.m4a`
is the master clock for every vector layer. Opacity and travel use shared cosine
ease-in/out curves; the pieces settle at the 4.2-second impact. The wordmark then
appears. `StartupIntro` fades the entire layer to transparent from media time
7.0 to 8.0 seconds. Home is mounted and loading underneath; the layer and audio
decoder are removed at the end. The soundtrack retains its approved fade and
synchronization. No per-frame allocations, network calls, or background loops
are added after startup.

The intro has no skip action. Navigation presses are consumed until completion;
system volume and leaving the app still work. Backgrounding releases the media
immediately and returning does not replay it. Decoder failures and a bounded
startup watchdog reveal Home rather than leaving an unusable black screen.
Disabling system animations also disables this decorative sequence.

## Rebuild the bundled soundtrack

Use FFmpeg with the approved eight-second `darkflix-startup-with-audio.mp4` as
`INPUT`. No source soundtrack download is necessary:

```sh
ffmpeg -i "$INPUT" -vn -c:a copy app/src/main/res/raw/darkflix_startup.m4a
python3 tools/check_startup_media.py
```
