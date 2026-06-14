# UHD/HDR Green Video Handoff - 2026-06-13

Workspace: `C:\race-control-tv-st14n`
Device under test: Google TV Streamer / MediaTek Android TV device
Latest failed test log: `C:\race-control-tv-st14n\logcat_latest_green_after_extractor.txt`
User-visible result after latest build: still green video, with loading animation.

## TL;DR

The latest run still fails visually, but it no longer looks like a DASH candidate, manifest color, `hev1`/`hvc1`, empty `hvcC`, audio, tunneling, DRM, network, or basic cbcs sample parsing problem.

The current app requests and selects the real UHD/HDR Widevine stream, selects `_HDR-UHD_HEVC_2` at `3840x2160`, keeps the representation as `hev1.2.4.L153.B0`, carries `cbcs` DRM metadata, repairs the empty init-segment `hvcC`, injects BT.2020/HLG color from the DASH descriptors, creates `c2.mtk.hevc.decoder.secure`, loads DRM keys, receives HLG dataspace from the codec, and renders a first frame.

The strongest remaining signal is after first-frame render: the codec output changes to HLG (`android._dataspace = 302383104`), but `MtkHdmiService` configures HDMI with `hdrMode = 0`. That points at protected HDR presentation / MediaTek display handoff, not at another manifest selection fix.

The next useful comparison is an official F1TV playback log on the same current-week stream, filtered for `MtkHdmiService`, SurfaceFlinger/HWC, codec output dataspace, and the official playback surface. If official playback flips HDMI/HWC into an HDR mode while this app stays at `hdrMode = 0`, the remaining work is to discover which official SDK/path triggers that display pipeline.

## Latest Build Tested

The latest APK was built and installed from this workspace before the user retested:

- Gradle task: `.\gradlew.bat :app:assembleDebug --console=plain`
- Installed APK: `C:\race-control-tv-st14n\app\build\outputs\apk\debug\com.st14n.f1-1.0.3-debug.apk`
- Log captured after clearing logcat: `C:\race-control-tv-st14n\logcat_latest_green_after_extractor.txt`

User result: still green.

## What The Latest Log Proves

### Correct stream acquisition

`logcat_latest_green_after_extractor.txt:761` shows the app receives:

- `actualStreamType=HDR_UHD_DASHWV`
- `overrideStreamType=HDR_UHD_DASHWV`
- `drmType=widevine`
- license URL using `widevine_l1`
- manifest path ending in `HDR-UHD-DASH-WV/index.mpd`
- content ID `1000010272`
- channel ID `1025`

This means the app is not accidentally playing the SDR fallback or wrong stream type.

### Protected HDR EGL preflight fails

The device/app EGL preflight says protected HLG graph creation is not available:

- `logcat_latest_green_after_extractor.txt:841`: `protectedContent=false bt2020Hlg=false canCreateProtectedHlgEglSurface=false`
- `logcat_latest_green_after_extractor.txt:843`: app falls back to the Media3 internal player

This matters because the old official-app analysis found the official TV UHD path uses Tiledmedia/ClearVR-style playback rather than the ordinary Media3 Leanback surface path.

### Audio and tunneling are not the current failure

The latest run disables main-player audio for HDR/UHD and leaves tunneling off:

- `logcat_latest_green_after_extractor.txt:899`: `Main player audio disabled=true tunneling=false ... reason=hdr_video_only_no_companion`
- `logcat_latest_green_after_extractor.txt:1236`: `C2TunneledModeSetter 303 mode 0 videoonly 0`

The failure persists, so the green video is not explained by the previous AAC/tunneling noise.

### The selected video track is the 2160p HEVC HDR track

Track selection chooses the requested UHD candidate:

- `logcat_latest_green_after_extractor.txt:1052`: EventLogger selects track 6, `_HDR-UHD_HEVC_2`, `3840x2160`, `50fps`, `codecs=hev1.2.4.L153.B0`, `color=BT2020/Limited range/HLG`
- `logcat_latest_green_after_extractor.txt:1118`: app log confirms `selected=true supported=true HDRInfo=colorSpace=6 colorTransfer=7 colorRange=2 DRM=schemeType=cbcs schemeDataCount=2`

This rules out rejecting/dropping the `hev1`/`cbcs` candidate as a solution path. The app is selecting it and must make it work.

### DASH color metadata is injected correctly

The custom DASH parser injects BT.2020/HLG ColorInfo from the manifest descriptors:

- `logcat_latest_green_after_extractor.txt:994-1000`: HDR representations get `colorSpace=6 colorTransfer=7 colorRange=2`
- `logcat_latest_green_after_extractor.txt:1000`: `_HDR-UHD_HEVC_2` specifically gets BT.2020/HLG

So the failure is not simply that Media3 lost DASH CICP color metadata before track selection.

### Init segment repair is active

The current-week UHD init segment arrives as only 848 bytes, and the app repairs it:

- `logcat_latest_green_after_extractor.txt:1041`: custom HEVC chunk extractor is used for `_HDR-UHD_HEVC_2`
- `logcat_latest_green_after_extractor.txt:1163`: init segment network body is 848 bytes
- `logcat_latest_green_after_extractor.txt:1165`: `Expanded empty hvcC ... hvcC=31->145 addedColr=true`
- `logcat_latest_green_after_extractor.txt:1166`: repaired init segment becomes 981 bytes
- `logcat_latest_green_after_extractor.txt:1167`: manifest format is merged back over parsed track; `parsedCodecs=hvc1... mergedCodecs=hev1... initData=1`

This rules out the original empty `hvcC` by itself. The repair is applied, CSD reaches the extractor/decoder path, and the screen is still green.

### The codec path initializes and decodes

Media3/Codec2 creates the secure MediaTek HEVC decoder and outputs the expected cropped 2160 frame:

- `logcat_latest_green_after_extractor.txt:1204`: official-like secure HLG MediaCodec flags applied, `size=3840x2160 frameRate=50.0`
- `logcat_latest_green_after_extractor.txt:1206`: `allocate(c2.mtk.hevc.decoder.secure)`
- `logcat_latest_green_after_extractor.txt:1275`: `Created component [c2.mtk.hevc.decoder.secure]`
- `logcat_latest_green_after_extractor.txt:1495`: MediaTek format change `width 3840, height 2176`, cropped to `3840x2160`
- `logcat_latest_green_after_extractor.txt:1500`: `videoDecoderInitialized ... c2.mtk.hevc.decoder.secure`
- `logcat_latest_green_after_extractor.txt:1533-1534`: `drmKeysLoaded` and app log says DRM keys loaded successfully

This is not a simple decoder creation or license failure.

### Codec output reaches HLG dataspace

The codec initially reports SDR-ish dataspace, then switches after output format change:

- `logcat_latest_green_after_extractor.txt:1374`: initial `android._dataspace = 259`
- `logcat_latest_green_after_extractor.txt:1333`: MediaTek color aspects become `r 2 p 6 t 7 m 5`
- `logcat_latest_green_after_extractor.txt:1546`: output `android._dataspace = 302383104`
- `logcat_latest_green_after_extractor.txt:1502`: `videoInputFormat` remains `_HDR-UHD_HEVC_2`, `3840x2160`, BT.2020/HLG

The decoder is not unaware that this is BT.2020/HLG.

### First frame renders, but HDMI stays non-HDR

Media3 reports first-frame render:

- `logcat_latest_green_after_extractor.txt:1569`: `renderedFirstFrame`
- `logcat_latest_green_after_extractor.txt:1570`: display snapshot shows the sink advertises `hdrTypes=[HLG]`, `hdrConversion=PASSTHROUGH`, `isHdr=true`
- `logcat_latest_green_after_extractor.txt:1571-1572`: app `onRenderedFirstFrame` logs fire

Immediately after, MediaTek HDMI config remains non-HDR:

- `logcat_latest_green_after_extractor.txt:1677`: `setVideoResolution: hdrMode = 0`
- `logcat_latest_green_after_extractor.txt:1679-1680`: user request includes `hdrMode = 0`
- `logcat_latest_green_after_extractor.txt:1699-1700`: final/actual HDMI config uses `hdrMode = 0`
- `logcat_latest_green_after_extractor.txt:1751`: `setVideoConfig hdrMode 0`

This is the best current lead. A secure HEVC decoder is producing HLG dataspace, first frame is rendered, the display advertises HLG, but HDMI remains configured as non-HDR. Presenting protected HLG YUV through an SDR/non-HDR HDMI mode is consistent with a green output.

### Segments continue to load

After the first frame, the app continues loading UHD video segments:

- `logcat_latest_green_after_extractor.txt:1784+`: subsequent `_HDR-UHD_HEVC_2` segments continue to load

So the latest failure is not a clean network stop, manifest expiration, DRM-key loss, or player crash.

## Current Stream Artifacts

Captured current-week HDR files:

- `C:\race-control-tv-st14n\diagnostics\current_week_hdr\index.mpd`
- `C:\race-control-tv-st14n\diagnostics\current_week_hdr\hdr_uhd_init.mp4`
- `C:\race-control-tv-st14n\diagnostics\current_week_hdr\hdr_uhd_media.mp4`
- `C:\race-control-tv-st14n\diagnostics\current_week_hdr\FragmentedMp4Extractor.javap.txt`
- `C:\race-control-tv-st14n\diagnostics\current_week_hdr\TrackEncryptionBox.javap.txt`

Important parsed facts from the artifact analysis:

- Current-week MPD HDR adaptation set declares `cbcs` content protection.
- The selected representation is `_HDR-UHD_HEVC_2`, `3840x2160`, `50fps`, `15.36Mbps`, `hev1.2.4.L153.B0`.
- The original init segment has an empty/minimal `hvcC` and uses `frma=hev1`.
- Encryption is `schm=cbcs`; `tenc` version 1 with pattern encryption (`1:9`) and constant IV.
- Media sample crypto tables look internally consistent: `saiz`/`senc` entries exist, the first sample has subsamples, and clear/encrypted boundaries align with HEVC NAL boundaries.
- Media3 rewrites 4-byte NAL lengths to 4-byte Annex B start codes, so sample size and crypto offsets are not obviously shifted by the rewrite.

This makes a simple cbcs subsample-alignment bug less likely than the display handoff issue, though it is not mathematically impossible without decrypting protected payloads.

## Code State Relevant To This Investigation

New/changed files in the current playback path:

- `C:\race-control-tv-st14n\app\src\main\java\fr\groggy\racecontrol\tv\ui\channel\playback\F1DashManifestParser.kt`
  - Injects `ColorInfo` from DASH CICP descriptors.
- `C:\race-control-tv-st14n\app\src\main\java\fr\groggy\racecontrol\tv\ui\channel\playback\F1DashInitSegmentFixingDataSource.kt`
  - Repairs current-week F1 UHD init segments with empty HEVC CSD and adds HLG color box metadata.
- `C:\race-control-tv-st14n\app\src\main\java\fr\groggy\racecontrol\tv\ui\channel\playback\F1DashChunkExtractorFactory.kt`
  - Uses a custom DASH chunk extractor for HEVC and preserves manifest format identity/color/DRM over the parsed init format.
- `C:\race-control-tv-st14n\app\src\main\java\fr\groggy\racecontrol\tv\ui\channel\playback\ChannelPlaybackFragment.kt`
  - Wires `DashMediaSource` to the custom chunk extractor and manifest parser.
  - Disables tunneling.
  - Disables main-player audio for UHD/HDR unless external audio is used.
- `C:\race-control-tv-st14n\app\src\main\java\fr\groggy\racecontrol\tv\ui\channel\playback\OfficialLikeHdrPlaybackFragment.kt`
  - Wires the same DASH extractor/parser path.
- `C:\race-control-tv-st14n\app\src\main\java\fr\groggy\racecontrol\tv\ui\channel\playback\HdrToneMappingRenderersFactory.kt`
  - Still contains direct secure MediaCodec / official-like experiments and logs.

The working tree is dirty and includes older unrelated/experimental changes. Before committing, review `git status --short` and diff carefully. Do not assume every modified file belongs to the final fix.

## What Is Ruled Out Or Less Likely

- Wrong stream: latest log shows `HDR_UHD_DASHWV` from the F1 Play API.
- Wrong track: `_HDR-UHD_HEVC_2` is selected at 2160p.
- Rejecting `hev1` or `cbcs`: not acceptable; the selected official candidate is `hev1`/`cbcs`, and the current app reaches decode with it.
- Missing DASH color descriptors: parser injects BT.2020/HLG before selection.
- Empty init `hvcC` alone: repair applies and `initData=1` reaches the decoder path, but green remains.
- `hvc1` replacing `hev1`: custom extractor merges manifest identity back to `hev1`, but green remains.
- Basic DRM failure: Widevine keys load and the secure decoder starts.
- Decoder-not-created failure: `c2.mtk.hevc.decoder.secure` initializes and outputs frames.
- Audio/tunneling stall: main audio disabled, tunneling off, failure remains.
- Network stop: UHD video segments continue loading after first frame.

## Remaining Working Hypotheses

1. The standard Media3 `SurfaceView` secure-decoder route on this device does not trigger the same protected HDR presentation path as the official app. The decoder outputs HLG, but HDMI/HWC stays in non-HDR mode (`hdrMode = 0`), causing green video.

2. The official app's Tiledmedia/ClearVR path may be doing something outside normal Media3/ExoPlayer surface configuration: a protected renderer bridge, private SDK calls, native surface handling, or a display/HDR mode trigger that the current app does not perform.

3. Current-week stream changes exposed the issue because the stream now depends more strictly on representation-level metadata/init repair/display handoff than last week's stream. The app can now compensate for manifest/init metadata issues, but display handoff is still different from official.

4. A remaining cbcs/sample handling issue is lower probability after the latest evidence, but still worth keeping in mind only if official/current-week comparison proves HDMI/HDR mode is identical and official still renders correctly.

## Recommended Next Steps

1. Capture official F1TV playback logs for the same current-week content on the same device.

   Use a focused filter that includes:

   - `MtkHdmiService`
   - `SurfaceFlinger`
   - `hwcomposer`
   - `CCodec`
   - `C2MtkVdec`
   - `Tiledmedia`
   - `ClearVR`
   - `SurfaceView`
   - `android._dataspace`
   - `setVideoResolution`
   - `setVideoConfig`
   - `hdrMode`

   The key question is whether official playback changes HDMI/HWC to an HDR mode where this app remains at `hdrMode = 0`.

2. Compare official vs this app around first frame:

   - Surface name/layer type and secure/protected flags.
   - Codec output dataspace transition.
   - HDMI `hdrMode`.
   - SurfaceFlinger layer dataspace/color mode if visible.
   - Any vendor/private calls around display mode or HDR policy.

3. If official has `hdrMode != 0`, find the trigger.

   Search the official decompiled app and native libraries for display/HDR keywords, Tiledmedia/ClearVR setup, protected surface creation, and any MediaTek/HDMI/vendor reflection. Earlier docs already established official TV playback activity as `com.avs.f1.ui.tiledmediaplayer.TiledPlayerActivityTv` using `com.tiledmedia.clearvrview.TiledmediaView`.

4. If no Java/Kotlin trigger exists, assume the fix requires a legitimate protected HDR playback SDK/path.

   The likely candidates are a licensed Tiledmedia/ClearVR integration or another SDK that can create the same protected HDR renderer path. More Media3 flags may not be enough if platform EGL preflight remains `protectedContent=false bt2020Hlg=false`.

5. Keep the stream acquisition and parser/init fixes while testing presentation.

   These changes are still useful: they prove the app can select and feed the exact UHD/HDR `hev1`/`cbcs` candidate. But they should be cleaned/gated before committing because the current tree contains lots of diagnostics.

## What Not To Do Next

- Do not solve this by falling back to SDR or rejecting the 2160p candidate. The user explicitly wants the 2160p official track to work.
- Do not keep cycling on `hev1` vs `hvc1` as the primary theory unless a new log contradicts the latest extractor evidence.
- Do not assume another `hvcC` injection tweak will fix it; the latest test had repaired CSD and still went green.
- Do not re-enable tunneling as the main fix; it was disabled in the latest test and the failure signature did not change in the useful direction.
- Do not treat "first frame rendered" as success. The failure is visual presentation after decode.

## Useful Commands

Filtered app log anchors:

```powershell
rg -n "HDR_UHD_DASHWV|F1DashManifestParser|F1DashChunkExtractor|F1DashInitFixingDataSource|Track #6|Track:6|drmKeysLoaded|videoDecoderInitialized|android\._dataspace|renderedFirstFrame|setVideoResolution: hdrMode|setVideoConfig hdrMode|MtkHdmiService|Player error" C:\race-control-tv-st14n\logcat_latest_green_after_extractor.txt
```

Code locations:

```powershell
rg -n "DashMediaSource|F1DashChunkExtractorFactory|F1DashManifestParser|F1DashInitSegmentFixingDataSource|setTunnelingEnabled|hdr_video_only_no_companion" C:\race-control-tv-st14n\app\src\main\java
```

Official comparison starting points:

```powershell
rg -n "TiledPlayerActivityTv|TiledmediaView|EGL_PROTECTED_CONTENT|c2\.mtk\.hevc\.decoder\.secure|android\._dataspace|setVideoResolution: hdrMode|setVideoConfig hdrMode" C:\race-control-tv-st14n\logs_official_app_playback.txt C:\race-control-tv-st14n\docs\official-vs-media3-hdr-flow.md
```

## Bottom Line

The current app is now getting past the parts that were most suspicious in the response manifest and init segment. It selects the right 2160p `hev1`/`cbcs` HDR track, repairs the bad HEVC CSD, decrypts, decodes, receives HLG dataspace, and renders a first frame. The green output remains because the protected HDR frame is apparently not being handed to the display/HDMI stack as HDR; `MtkHdmiService` stays at `hdrMode = 0`.

The next engineer should stop treating this as primarily a manifest parser problem and compare the official app's current-week HDR display handoff against this app's Media3 surface handoff.
