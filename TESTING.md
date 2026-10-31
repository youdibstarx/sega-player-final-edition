# SEGA PLAYER V3 preview

This repository is V3 only. No V2 repository or production control-panel changes are required.

## Install safely

The Actions artifact contains a debug APK with application ID `com.segaiptv.player.v3.preview`.
It installs beside V2 and owns separate app data, MAC alias and Device Key. Activate this test
identity through the existing panel if you want to use assigned playlists. Do not uninstall V2,
clear its data or attempt to copy its identity into the preview.

The release application ID is still `com.segaiptv.player`. Replacing an existing installation
requires the original signing certificate and a sufficient versionCode. This workflow does not
publish a production-signed upgrade. Debug signing keys on ephemeral runners may differ between
builds: use a dedicated test device and expect to reinstall the preview if signatures differ.

## Implemented

- Six home destinations; top TV navigation; dark charcoal and gold focus/selection.
- Live bouquets, channel logos, search, favorites and a fitted 16:9 preview.
- Shared Media3 playback with release on STOP, source change and disposal; retry and buffering UI.
- Lazy movie/series poster grids, provider categories and details.
- M3U series names with S01E02 or 1x02 are grouped into seasons and episodes. Untagged names remain
  individually playable; the app does not invent season information.
- Existing M3U Plus and Xtream-generated get.php M3U URLs remain supported. Native player_api.php
  catalogue enrichment is not implemented; assign the provider's M3U Plus URL in the existing panel.
- Playlist management shows assigned sources without displaying their credential-bearing URLs.

## Validation

Actions executes `:app:testDebugUnitTest :app:assembleDebug` and uploads the debug APK.
Tests cover series/movie classification, season parsing and relative M3U streams.
Real device checks still required: activate a test device, load an authorized playlist, move through
all pages using only D-pad/OK/Back, switch channels repeatedly, open full screen, background/resume,
verify favorites persist, and play movie/series entries. Check on 720p and 1080p TV layouts.
No customer credentials or live streams are embedded in the tests.
