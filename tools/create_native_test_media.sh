#!/usr/bin/env bash
set -euo pipefail
# Synthetic test patterns and tones only. All output is androidTest-only.
cd "$(dirname "$0")/.."
fixture_dir=app/src/androidTest/assets/native-tracks
for spec in 'low 160x90 60k' 'high 320x180 150k'; do
    read -r name size bitrate <<< "$spec"
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=$size:rate=12" \
        -t 90 -an -c:v libx264 -preset ultrafast -profile:v baseline -pix_fmt yuv420p \
        -g 24 -sc_threshold 0 -b:v "$bitrate" -hls_time 6 -hls_playlist_type vod \
        -hls_segment_filename "$fixture_dir/$name-%02d.ts" "$fixture_dir/$name.m3u8"
done
for spec in 'en 440' 'es 660'; do
    read -r name tone <<< "$spec"
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=$tone:sample_rate=44100" \
        -t 90 -vn -c:a aac -b:a 32k -hls_time 6 -hls_playlist_type vod \
        -hls_segment_filename "$fixture_dir/$name-%02d.ts" "$fixture_dir/$name.m3u8"
done
