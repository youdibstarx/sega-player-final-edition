# SEGA PLAYER — 4K channels with silent audio

## Implementation

SEGA PLAYER uses AndroidX Media3 ExoPlayer **1.11.1**. A channel that works with sound
in VLC/BOB Player but plays silently on a particular TV box can have an AC-3, E-AC-3
(Dolby Digital Plus), DTS, TrueHD or unusual AAC audio track that the platform
MediaCodec does not decode properly. This hypothesis must be verified against
a failing stream on a real target device.

The app now depends on:

- `io.put:media3-ffmpeg-decoder:1.11.1+1` from Maven Central.
- Upstream implementation: https://github.com/putdotio/media3-ffmpeg-decoder
- FFmpeg-native supported decoders for this build: `vorbis opus flac alac
  pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd`.
- Bundled native ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

The Media3 `DefaultRenderersFactory` is set to
`EXTENSION_RENDERER_MODE_PREFER`, **not merely ON**. This matters because
some TV boxes advertise AC-3/E-AC-3 hardware decoding but produce silence
without reporting a decoder initialization failure. The FFmpeg *audio* renderer
is preferred, while the stock MediaCodec *video* renderer keeps 4K hardware
acceleration. Existing decoder-failure retry logic remains in place.

No BOB Player proprietary binary or VLC/IJKPlayer binary is copied or bundled.

## Device test before release

1. Choose one 4K station that had picture but no sound in SEGA PLAYER and
   does play with sound in the reference player on the **same** TV box.
2. Test it over the same HDMI / PCM settings, using the same authorized stream.
3. Test a regular AAC channel, AC-3/E-AC-3, DTS if available, and normal
   4K/HEVC playback. Check sound, video smoothness, channel switching and sync.
4. Repeat on a 32-bit ARM TV box and a 64-bit ARM TV box.
5. Test network / decoder failure retries and foreground-background resume.
6. If still silent, capture Media3 `onTracksChanged`, `onPlayerError`,
   audio renderer/decoder names and (if available) `ffprobe` codec data.
   Investigate pass-through and output channel configuration separately.

A passing Gradle CI build confirms packaging, **not audible playback**.
A fresh, correctly signed APK with a monotonically increasing versionCode
is needed for installation on previously deployed devices. Do not distribute
the unsigned CI artifact as an installable update. Existing backend/panel
and reseller credits are unaffected.

## Open-source licenses and release requirements

The Media3 FFmpeg extension wrapper is Apache-2.0; FFmpeg within the AAR is
**LGPL-2.1-or-later**. The matching release publishes notices and a source /
relink archive in Maven Central:
https://repo1.maven.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1+1/

The app MUST distribute/present the exact upstream notices and license,
offer the matching FFmpeg source and relinking material, preserve the metadata
under `META-INF/media3-ffmpeg-decoder/` and ensure users can exercise their
LGPL relinking/replacement rights. Review the application's installation
signature/attestation policy against those rights before a commercial release.
Do not treat an embedded notice alone as sufficient legal compliance.
