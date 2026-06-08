# UHD/HDR Green Video Handoff

Date: 2026-06-06
Device under test: Google TV Streamer at `192.168.178.158:5555`
Workspace: `C:\race-control-tv-st14n`

## TL;DR

The app can now obtain F1TV's UHD/HDR Widevine stream (`HDR_UHD_DASHWV`) and Media3 can decrypt/decode it with the secure MediaTek HEVC decoder. The remaining failure is visual presentation: the video plane is green even though logs show frames render.

Official F1TV playback works on the same device. The main known architectural difference is that the official TV UHD path uses Tiledmedia's `TiledPlayerActivityTv` / `TiledmediaView` with a protected EGL render target, not our Leanback + Media3 `VideoSupportFragment` surface path.

Copying the official app "exactly" is not realistic or safe as-is because it would mean shipping proprietary Bitmovin/Tiledmedia SDK code/assets/native libraries and likely a licensed player key. A proper migration to Bitmovin SDK may be possible if a valid SDK/license is available, but the official UHD TV player appears to be Tiledmedia-backed. Bitmovin alone may still use the same Android MediaCodec/Surface path and may not fix the green HDR output.

Current direction after rejecting licensed SDKs: keep Media3, keep requesting the real UHD/HDR stream, and fix the protected HDR presentation path instead of avoiding HDR.

## Current Evidence

Latest useful log files:

- `C:\race-control-tv-st14n\logcat.txt`
- `C:\race-control-tv-st14n\logs_official_app_playback.txt`
- `C:\race-control-tv-st14n\docs\official-vs-media3-hdr-flow.md`
- Older intermediate logs in the repo root, for example `logs_after_dash_hdr_green.txt`.

What our app now proves:

- F1 PLAY API returns `HDR_UHD_DASHWV`.
- License URL is `widevine_l1`.
- Widevine L1 succeeds.
- Decoder is `c2.mtk.hevc.decoder.secure`.
- Selected format is `_HDR-UHD_HEVC_2`, `3840x2160`, `50fps`.
- Output format changes to HLG/BT.2020:
  - `color-standard = 6`
  - `color-transfer = 7`
  - `android._dataspace = 302383104`
- Media3 reports `onRenderedFirstFrame`.
- User sees green video.

Official app comparison:

- Official activity: `com.formulaone.production/com.avs.f1.ui.tiledmediaplayer.TiledPlayerActivityTv`
- Official layout uses `com.tiledmedia.clearvrview.TiledmediaView`.
- Official Tiledmedia code checks `EGL_PROTECTED_CONTENT_EXT`.
- Official Tiledmedia code logs/uses protected EGL support for Widevine L1.
- Official also uses `c2.mtk.hevc.decoder.secure` and HLG/BT.2020 output.
- Official works visually on the same device.

## Current Uncommitted Changes

As of this handoff, these files are modified:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/fr/groggy/racecontrol/tv/f1/F1Client.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/ChannelPlaybackActivity.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/ChannelPlaybackFragment.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/HdrToneMappingRenderersFactory.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/MediaSourceItemFactory.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/utils/DeviceInfo.kt`
- `app/src/main/res/values/styles.xml`
- `docs/uhd-hdr-green-video-handoff.md`

Important: some late experiments should be reviewed before committing:

- The SDR-UHD fallback/track-override experiment was removed. Current active work does not intentionally avoid HDR.
- Current active Media3 experiment delays player startup until the Leanback `SurfaceView` exists, marks that surface secure, explicitly binds ExoPlayer to it before `prepare()`, and changes MediaCodec queueing behavior on Google TV Streamer/MediaTek.
- The tunneling experiment was removed after logs proved tunneling activated but did not solve the issue.
- The custom radio gating was temporarily relaxed to allow custom radio regardless of live/replay status.

## What Was Tried

### Stream/API identity

Implemented Google TV Streamer identity consistently:

- User-Agent spoofs Google TV Streamer / Android TV.
- `x-f1-device-info` spoofs Google TV Streamer with official-ish app/player versions.
- PLAY, entitlement, manifest/segment, and license requests include F1-ish headers:
  - `x-f1-device-info`
  - `Origin: https://f1tv.formula1.com`
  - `Referer: https://f1tv.formula1.com/`
  - entitlement/license token headers where relevant

Result: This fixed stream discovery. It did not fix green video.

### Stream type probing

Added HDR/UHD probe chain informed by known F1TV stream types:

- `HDR_UHD_DASH`
- `HDR_UHD_DASH_SINGLE`
- `HDR_UHD_DASHWV`
- `HDR_UHD_DASHWV_SINGLE`
- `HDR_UHD_CMAFWV`
- `HDR_UHD_CMAFWV_SINGLE`

Preferred accepted internal-player stream became `HDR_UHD_DASHWV`.

Result: Correct UHD/HDR Widevine stream is acquired.

### Secure surface

Added:

- `FLAG_SECURE`
- `SurfaceView.setSecure(true)`
- `android:colorMode="hdr"`
- fullscreen-ish playback theme
- `android:hardwareAccelerated="true"`
- `android:screenOrientation="userLandscape"`
- `android:launchMode="singleTop"`

Result: No visual fix.

### Audio handling

UHD/HDR embedded audio caused problems in some earlier attempts. Current approach:

- Keep main UHD/HDR source video-only when companion/custom audio is present.
- Attach same-channel standard audio companion.
- Custom GP Radio can disable main player audio.
- A later fix preserved video selector constraints when GP Radio disables main audio.

Result: Custom radio can start, and video selection can remain `_HDR-UHD_HEVC_2`. Audio is not the green-screen root cause.

### Tunneling

Enabled Media3 `DefaultTrackSelector.Builder.setTunnelingEnabled(true)`.

Logs confirmed real tunneling:

- `android._tunneled = 1`
- `audioTrackInit ... tunneling`
- MediaTek `avsync` tunnel allocation

Result: User still reported wrong/green behavior. Tunneling also conflicts with custom radio because it depends on the main A/V audio tunnel. Removed again.

### SDR-UHD fallback

Added a late experiment to choose `_SDR-UHD_HEVC_1` from the UHD Widevine manifest on Google TV Streamer instead of `_HDR-UHD_HEVC_2`.

Expected: avoid HLG presentation path while preserving best safe resolution.

Result: User reported it is still wrong. This should be rechecked in logs before deciding whether to keep or remove.

Status: removed after user clarified the desired solution is to make HDR work, not to avoid HDR.

### Active Media3 protected HDR experiment

Implemented after online/official investigation:

- Android's HDR playback guidance says ExoPlayer supports HDR by default and that HDR output should use `SurfaceView`; `TextureView` has limited HDR behavior on Android 13+.
- Media3's `DefaultRenderersFactory` exposes queueing controls for forcing synchronous MediaCodec operation and toggling async crypto behavior on Android 14+.
- Media3 and ExoPlayer issue history shows HEVC + Widevine L1 failures are frequently device/platform DRM specific, so a Google TV Streamer/MediaTek targeted codec workaround is reasonable.

Current code changes:

- `ChannelPlaybackFragment` no longer starts playback in `onCreate`.
- Playback starts in `onViewCreated`, after the Leanback video view tree is inflated.
- The discovered `SurfaceView` is marked with `setSecure(true)` and kept screen-on.
- ExoPlayer is explicitly bound to that exact `SurfaceView` via `setVideoSurfaceView(surfaceView)` before `player.prepare()`.
- `HdrToneMappingRenderersFactory` enables decoder fallback.
- On Google TV Streamer / `kirkwood`, `HdrToneMappingRenderersFactory` forces synchronous MediaCodec queueing and disables the Android 14+ async crypto configure flag.

Result from 2026-06-06 test:

- The debug app (`com.st14n.f1.debug`) did run the updated Media3 code.
- The active stream was `HDR_UHD_CMAFWV` from `BIG_SCREEN_HLS`.
- The secure `SurfaceView` was configured and bound before `preparePlayer`.
- The MediaTek synchronous codec workaround logged as active.
- The decoder was `c2.mtk.hevc.decoder.secure`.
- Output switched to HLG/BT.2020 (`color-standard=6`, `color-transfer=7`, `android._dataspace=302383104`).
- Media3 reported `onRenderedFirstFrame`.
- User still saw green video.

New active experiment after that failure:

- Probe `HDR_UHD_DASHWV` / `HDR_UHD_DASHWV_SINGLE` before HLS CMAF-WV for Media3 HDR playback.
- Request the display's native 4K50 mode for UHD/HDR playback.
- Request fixed-source 50Hz on the secure `Surface` via `Surface.setFrameRate`.
- Explicitly launch/test `com.st14n.f1.debug`; stale `com.st14n.f1` is also installed on the TV and can confuse logs if launched.

Result from the DASH-WV / 4K50 test:

- `HDR_UHD_DASHWV` was accepted and selected.
- `_HDR-UHD_HEVC_2` 3840x2160/50fps was selected.
- `c2.mtk.hevc.decoder.secure` initialized.
- Output switched to HLG/BT.2020 and first frame rendered.
- User still saw green video.
- The 4K50 display-mode request logged, but `Surface.setFrameRate` did not apply because the surface was invalid at each request point.

Result from the video-plane isolation / SurfaceHolder callback test:

- The debug app (`com.st14n.f1.debug`) ran the diagnostic code.
- Companion SDR audio merge was skipped for HDR playback.
- Automatic custom-radio injection was skipped for HDR playback.
- `SurfaceHolder.Callback` fired for the secure playback `SurfaceView`.
- The app requested 4K50 again from `surfaceCreated` / `surfaceChanged`.
- The device actually switched HDMI/HWC to 4K50 during playback.
- `Surface.setFrameRate(50Hz, fixed_source, always)` applied after the surface became valid.
- Output still switched to HLG/BT.2020 and first frame rendered.
- User still saw the same black-then-green HDR failure.

Important new observation from the user:

- The screen is black before HDR engages.
- As soon as HDR turns on, the display flickers and then becomes fully green.
- This points at HDR display/presentation activation, not stream acquisition or initial decoder startup.

Important new log observation:

- HWC switched to 4K50 (`setActiveConfig[28]`).
- `MtkHdmiService` still logged `hdrMode = 0` while applying the 4K50 HDMI mode.
- The codec output then switched to HLG/BT.2020 (`android._dataspace = 302383104`).
- The secure `SurfaceHolder` initially reported `surfaceChanged ... size=1920x1080` even though the selected decoded video is 3840x2160.

Latest installed Media3 experiment:

- Force the secure `SurfaceHolder` backing buffer to the decoded video size using `SurfaceHolder.setFixedSize(videoWidth, videoHeight)` from `onVideoSizeChanged`.
- Re-apply that fixed size when the protected surface is rebound or recreated.
- Goal: get the protected video layer to allocate/present as a native 3840x2160 layer instead of leaving the app/Leanback surface at logical 1920x1080 while HWC/HDMI are in 4K HDR mode.
- Build succeeded and was installed to `com.st14n.f1.debug` on 2026-06-06 at 21:11:53.

Result from the fixed-size and no-forced-timing tests:

- `SurfaceHolder.setFixedSize(3840, 2160)` did apply.
- Media3 reported `surfaceSize 3840x2160`.
- The app then stopped forcing 4K50 / `Surface.setFrameRate` for HDR playback so Android/HWC could negotiate timing on its own.
- Main DASH playback was filtered to video-only to remove embedded audio from the diagnostic path.
- `_HDR-UHD_HEVC_2` was still selected.
- Output still switched to HLG/BT.2020.
- Media3 still reported first frame rendered.
- User still saw the same black-then-green HDR failure.
- Therefore the 1080p logical app surface, companion audio, embedded audio, app-forced 50Hz, and CDN host are not the root cause.

Proper official-vs-Media3 flow analysis:

- See `docs/official-vs-media3-hdr-flow.md`.
- Official works via `TiledPlayerActivityTv` with `com.tiledmedia.clearvrview.TiledmediaView`.
- Official APK ships ClearVR/Tiledmedia native renderer libraries and native DRM/display/video bridge strings such as `createVideoDecoder`, `createDRMSessionBridge`, `sendRendererFrame`, `FrameOutputMSEStyle`, and `DisplayOutput`.
- Official APK also contains protected EGL/HLG strings:
  - `EGL_EXT_protected_content`
  - `EGL_PROTECTED_CONTENT_EXT`
  - `EGL_EXT_gl_colorspace_bt2020_hlg`
  - `EGL_GL_COLORSPACE_BT2020_HLG_EXT`
  - `EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_HLG`
- That is a materially different protected HDR renderer path from Media3 handing secure MediaCodec output to a normal Leanback `SurfaceView`.

Browser curl attachment:

- Browser request used `WEB_HLS`, `player=player_tm`, `device=web`, Chrome/Windows device info, and web player version `8.212.0`.
- Replaying only response metadata showed `streamType=SDR_HD_CMAF`, `tmePresent=true`, no direct manifest URL, and no license URL.
- That is not directly playable by Media3; it points back to Tiledmedia/TME orchestration rather than a simple header/profile swap.

Previous installed diagnostic build:

- Still requests real HDR (`HDR_UHD_DASHWV` first).
- Skips the companion SDR audio merge for HDR playback to isolate the video plane.
- Skips automatic custom-radio injection during HDR diagnostic playback.
- Registers a `SurfaceHolder.Callback` and retries display/frame-rate setup on `surfaceCreated` and `surfaceChanged`.

Latest reviewed protected-HLG graph build on 2026-06-07:

- Build succeeded with `.\gradlew.bat :app:assembleDebug --console=plain`.
- Final reviewed build has not been launched for playback yet after this routing review.
- Adds `ProtectedEglSurfaceProbe` to prove whether the actual playback `SurfaceHolder.surface` can create a protected BT.2020 HLG EGL window surface.
- Adds `ProtectedHlgGlObjectsProvider` and a `ProtectedHlgGraphMediaCodecVideoRenderer`.
- For UHD/HDR Widevine streams, Media3 now attempts a `PlaybackVideoGraphWrapper` path with:
  - empty video effects, solely to force Media3's `VideoSink`/GL graph
  - a custom `GlObjectsProvider`
  - `EGL_PROTECTED_CONTENT_EXT`
  - `EGL_GL_COLORSPACE_BT2020_HLG_EXT`
- `openWithProtectedHdrRenderer` now routes to this protected-HLG graph instead of being a placeholder.
- HDR playback is no longer left in video-only diagnostic mode:
  - the app fetches a same-channel standard audio companion for UHD/HDR Widevine streams
  - the main HDR source is filtered to video-only only when a companion/external audio source is present
  - if companion fetch fails, embedded audio is preserved rather than silently forcing video-only playback
- If that graph reports a player error, the Activity retries the same HDR manifest once with direct Media3 `SurfaceView` output before falling back to standard HLS/SDR.
- No live logcat capture is currently running; start a fresh capture before the next test.

Build/install status:

- `.\gradlew.bat :app:assembleDebug --console=plain` succeeded on 2026-06-07 after the routing/audio review.
- Install/launch should happen after this review, immediately before the next visual test.

Expected log proof during next test:

- `preferHdrManifestForDevice=true`
- `HDR manifest preference requested; probing DASH Widevine HDR before HLS CMAF Widevine HDR`
- `actualStreamType=HDR_UHD_DASHWV`
- `Protected HDR renderer decision ... shouldUseProtectedRenderer=true`
- `Opening with protected HDR renderer`
- `Committing Media3 protected HLG graph renderer`
- `Media3 renderer factory protectedHlgGraph=true`
- `Installing Media3 protected HLG video graph renderer`
- `Created protected EGL context for Media3 protected HLG graph` or a clear protected-context fallback error
- `Created protected BT.2020 HLG EGL output surface for Media3 graph`
- `Configured protected SurfaceView for secure UHD/HDR playback`
- `Bound ExoPlayer to protected SurfaceView source=preparePlayer`
- `Forced synchronous MediaCodec queueing for MediaTek secure HDR playback`
- `Requested secure SurfaceHolder fixed buffer size`
- `Fetching standard same-channel audio companion for UHD/HDR playback`
- `Filtering main UHD/HDR source to video only; companion/external source supplies audio`
- `Secure SurfaceHolder surfaceCreated`
- `Secure SurfaceHolder surfaceChanged` with `size=3840x2160` after video size is known
- `c2.mtk.hevc.decoder.secure`
- `color-transfer = 7`
- `onRenderedFirstFrame`

## Likely Root Cause

The root cause is probably not:

- Missing API header
- Wrong stream type
- Wrong manifest
- Widevine license failure
- Wrong decoder
- Audio merge issue
- Bitrate adaptation

The likely root cause is:

- Media3 + normal Android secure `SurfaceView` presentation path on this MediaTek Google TV Streamer cannot present this protected HLG UHD stream correctly.
- Official avoids this by using Tiledmedia/ClearVR's protected renderer/display bridge, not by merely choosing better headers or a different Media3 flag.
- Within the current unlicensed Media3-only stack, this is now best treated as a renderer-path limitation rather than an unresolved stream-selection bug.

## Bitmovin / Tiledmedia Migration Assessment

### Can we migrate to Bitmovin?

Technically possible if:

- A valid Bitmovin Android SDK dependency can be added.
- A valid Bitmovin license key is available.
- The SDK supports the F1 DASH Widevine URL/license headers needed here.
- The app can rebuild controls/audio/custom radio around Bitmovin callbacks.

But:

- Bitmovin Android commonly still uses Android MediaCodec/Surface underneath.
- If it uses the same secure surface presentation path as Media3, green video may persist.
- The official decompiled app includes Bitmovin SDK, but official UHD TV playback in logs uses `TiledPlayerActivityTv`, not the normal Bitmovin player activity.

### Can we copy official exactly?

Not responsibly as a normal project change:

- It would involve proprietary SDK code/assets/native libs from the official APK.
- It may require licensed keys, entitlement config, analytics config, and signature/package assumptions.
- It may violate license/copyright boundaries.
- It would be brittle and hard to maintain.

### Better architectural options

Recommended order:

1. Integrate a legitimate player SDK with protected HDR support.
   - Prefer Tiledmedia if obtainable because official UHD TV playback uses it.
   - Bitmovin is worth testing only with a valid SDK/license and a minimal proof of concept.

2. Build a minimal Bitmovin proof-of-concept screen.
   - Feed it the already-working `HDR_UHD_DASHWV` manifest and Widevine L1 license URL.
   - Add the exact same headers.
   - Test whether Bitmovin fixes green output before migrating controls.

3. If no licensed SDK is available, keep Media3 and make fallback behavior honest.
   - Request UHD/HDR when available.
   - If device is known-bad, fallback to SDR/HD or SDR-UHD if verified working.
   - Do not claim true HDR on this device.

4. Investigate a custom OpenGL/protected EGL renderer.
   - This is difficult with Widevine L1 protected content.
   - Media3 does not expose a straightforward protected EGL rendering path equivalent to Tiledmedia's.

## Build And Install Commands

Use PowerShell from repo root:

```powershell
cd C:\race-control-tv-st14n
.\gradlew.bat :app:assembleDebug
adb connect 192.168.178.158:5555
adb -s 192.168.178.158:5555 install -r "C:\race-control-tv-st14n\app\build\outputs\apk\debug\com.st14n.f1-1.0.2-debug.apk"
```

Clear logs before a test:

```powershell
adb -s 192.168.178.158:5555 logcat -c
```

Pull logs after a test:

```powershell
adb -s 192.168.178.158:5555 logcat -d > C:\race-control-tv-st14n\logcat.txt
```

Useful filtered log commands:

```powershell
rg -n "HDR_UHD_DASHWV|Received viewing response|preparePlayer|Track:|downstreamFormat|videoInputFormat|videoSize|drmKeysLoaded|videoDecoderInitialized|Configured protected SurfaceView|Bound ExoPlayer|Media3 renderer factory|Installing Media3 protected HLG|Protected HLG EGL|protected BT.2020 HLG|ProtectedHlgGlObjectsProvider|Forced synchronous|color-standard|color-transfer|android\._dataspace|android\._tunneled|onRenderedFirstFrame|Player error|direct Media3 surface fallback|GP Radio|Main player audio disabled" C:\race-control-tv-st14n\logcat.txt -S
```

```powershell
rg -n "TiledPlayerActivityTv|TiledmediaView|SurfaceUtils|c2\.mtk\.hevc|color-standard|color-transfer|android\._dataspace|consumer usage|WVCdm|EGL Protected|protected content" C:\race-control-tv-st14n\logs_official_app_playback.txt -S
```

Compare current working tree:

```powershell
git status --short
git diff --stat
```

## ADB Device Notes

Device:

```powershell
adb connect 192.168.178.158:5555
adb devices
```

Install command used repeatedly:

```powershell
adb install "C:\race-control-tv-st14n\app\build\outputs\apk\debug\com.st14n.f1-1.0.2-debug.apk"
```

Prefer explicit serial after connecting:

```powershell
adb -s 192.168.178.158:5555 install -r "C:\race-control-tv-st14n\app\build\outputs\apk\debug\com.st14n.f1-1.0.2-debug.apk"
```

## Suggested Next Steps

1. Test the installed protected-HLG Media3 graph build visually on the Google TV Streamer.
2. If it still goes green, inspect `logcat_live_protected_graph.txt` for `Media3 renderer factory`, `Installing Media3 protected HLG`, `Protected HLG EGL`, `Created protected BT.2020 HLG EGL output surface`, and decoder/dataspace markers.
3. Keep the stream/header fixes because they solved acquisition of `HDR_UHD_DASHWV`.
4. Keep the custom-radio selector fix because it prevented GP Radio from dropping video quality.
5. Do not pursue Bitmovin unless a valid license/key becomes available; for exact official UHD/HDR behavior, Tiledmedia access is still the more relevant licensed route.

## Known Good/Bad Markers

Good acquisition markers:

```text
actualStreamType=HDR_UHD_DASHWV
drmType=widevine
laUrl=.../widevine_l1
Track:6 ... _HDR-UHD_HEVC_2 ... res=3840x2160 ... supported=YES
videoDecoderInitialized ... c2.mtk.hevc.decoder.secure
color-transfer = 7
android._dataspace = 302383104
onRenderedFirstFrame
```

Good protected-graph markers:

```text
Media3 renderer factory protectedHlgGraph=true
Installing Media3 protected HLG video graph renderer
Protected HLG EGL window-surface probe ... succeeded=true
Created protected BT.2020 HLG EGL output surface for Media3 graph
```

Bad/failed visual outcome:

```text
User sees green video despite the above markers.
```

Latest Media3/official delta build installed on 2026-06-07:

- Previous bare `SurfaceView` Media3 run proved the new route was active:
  - `useOfficialLikeBareHdrSurface=true`
  - `Created official-like bare Surface HDR fragment`
  - selected `_HDR-UHD_HEVC_2`
  - `c2.mtk.hevc.decoder.secure`
  - `color-transfer = 7`
  - `android._dataspace = 302383104`
  - `onRenderedFirstFrame`
- That run still produced green video.
- Important log delta versus official:
  - Official: `RealTime: priority 0, operating rate 0.000000, lowlatency:0`
  - Previous Media3 build: `RealTime: priority 0, operating rate 50.000000, lowlatency:0`
- The installed build now suppresses Media3's `operating-rate` hint for F1 UHD/HLG HEVC while preserving official-proven `priority=0` and `frame-rate=50`.
- Removed speculative `no-post-process` and `auto-frc` keys from the official-like MediaCodec override, because the official log does not show those keys and Media3 logged them as having no Codec2 equivalents.
- Playback Activity now forces an opaque secure black window before inflation.
- Bare HDR fragment now uses a normal-Z secure `SurfaceView`, sets the holder format opaque, and avoids rebinding the same raw `Surface` on `surfaceChanged`.
- Build succeeded and was installed to `com.st14n.f1.debug`; logcat was cleared after launching to HomeActivity.

Expected proof markers in the next test:

```text
Suppressing Media3 MediaCodec operating-rate hint for official-like secure HLG path
Applied official-like secure HLG MediaCodec format flags ... priority=0 operatingRate=unset
RealTime: priority 0, operating rate 0.000000, lowlatency:0
```

Tunneling markers from failed experiment:

```text
android._tunneled = 1
audioTrackInit ... tunneling
avsync "Alloc tunnel playback_0 resources"
```

These confirmed tunneling was active but did not solve the visual issue.

## Latest Tests & Current Native-Media3 Direction (2026-06-07)

### Test Run 1: Sabrina Spoofing with Forced HDR Manifest
- **Action**: Spoofed the Chromecast with Google TV (`sabrina`) identity in `DeviceInfo.kt` to trigger the backend whitelist, while requesting the forced HDR manifest.
- **Result**: Successfully bypassed SDR fallback and fetched the `HDR_UHD_DASHWV` HLG stream. However, the screen still flickered and turned green as soon as HDR mode engaged.

### Test Run 2: Reverting Surface Formats and colorMode
- **Action**: Set the playback `SurfaceView` format to `OPAQUE` (to prevent alpha blending compilation bugs with secure buffers), removed `android:colorMode="hdr"` from the Manifest (to prevent forcing 10-bit window-composition mode on the activity), and changed the window backgrounds to black in the styles.
- **Result**: Still produced a solid green screen as soon as HDR rendering kicked in.

### Superseded Fallback Conclusion
An earlier conclusion said to disable HDR manifests on Google TV Streamer / `kirkwood`. That is not the current goal and is not what the active code does. The user explicitly wants true UHD/HDR fixed, not avoided, and `DeviceInfo.shouldRequestHdrManifest()` currently still requests HDR when the display reports HLG support.

### Latest Verified Failure (Before Graph State Fixes)
Tested build before the latest graph-routing fix:

- Requested and received `HDR_UHD_DASHWV`.
- Selected `_HDR-UHD_HEVC_2` at 3840x2160/50.
- Used `c2.mtk.hevc.decoder.secure`.
- Suppressed Media3 `operating-rate` so MediaTek logged `RealTime: priority 0, operating rate 0.000000`.
- Applied `SurfaceControl.Transaction.setDataSpace(..., DATASPACE_BT2020_HLG)` to the `SurfaceView` layer before decode and again on video-size change.
- Explicitly set MediaCodec color keys to BT.2020 / HLG / limited range.
- MediaCodec output still reported `color-standard=6`, `color-transfer=7`, `android._dataspace=302383104`.
- Media3 reported `onRenderedFirstFrame`.
- User still saw solid green when HDR mode engaged.

Important correction: that run did **not** use the Media3 protected-HLG graph. The routing still opened `OfficialLikeHdrPlaybackFragment`, the bare direct `Surface` path. Logs showed:

```text
ProtectedHdrCapabilitiesProbe: protectedContent=false bt2020Hlg=false canCreateProtectedHlgEglSurface=false
Committing official-like bare Surface HDR fragment
Installing official-like direct secure HDR MediaCodec renderer
```

### Media3 Protected-HLG Graph Approach & EGL_BAD_MATCH
We then successfully engaged the Media3 protected-HLG VideoGraph by bypassing the probe check. However, this immediately led to crashes on the second frame:
1. `onRenderedFirstFrame` fired successfully.
2. The second frame threw `EGL_BAD_MATCH (0x3009)` inside `FinalShaderProgramWrapper.renderFrameToOutputSurface`.

**Root cause of `EGL_BAD_MATCH`:** 
The app was calling `player.setVideoSurfaceView(surfaceView)` repeatedly (on `surfaceCreated`, `startPlayer`, `preparePlayer`, `surfaceChanged`). Each call triggered the graph's `setOutputSurfaceInfo()` method, which hot-swapped the EGL surface while the GL pipeline was mid-stream. This caused the EGL context to become desynced from the active EGL surface, leading to the driver throwing `0x3009`. 

**Fix applied:**
- Added `boundHlgGraphSurfaceView` to track the surface bound to the graph.
- Skipped redundant `setVideoSurfaceView()` calls if the identity of the `SurfaceView` hadn't changed.
- Created a **plain EGL window surface** (without `EGL_GL_COLORSPACE_BT2020_HLG_EXT` attributes) for the graph output, relying entirely on the Android layer's `DATASPACE_BT2020_HLG` hint to inform SurfaceFlinger of the HDR content, which aligns with how the official app works.

### Latest Test Outcome & Graph Death (2026-06-07)
After applying the `setVideoSurfaceView` idempotency fix and plain EGL surface generation, the user tested the build on the TV:
- **Result:** The video never started playing and eventually closed the player.
- **Log analysis:** The playback encountered a `Player error: ERROR_CODE_FAILED_RUNTIME_CHECK (1004)` almost immediately upon start.
- **Root Cause:** Deep in the logs, `MediaCodec` threw `Failed to initialize c2.mtk.hevc.decoder.secure, error 0xfffffff4 (NO_MEMORY)`. 
  - This happens because the `PlaybackVideoGraphWrapper` creates an internal OpenGL `SurfaceTexture` for the decoder to write to.
  - Because `EGL_EXT_protected_content` is not exposed on this display's default EGL context, the graph's EGL context and input texture are **not secure**.
  - The MediaTek secure decoder (`c2.mtk.hevc.decoder.secure`) detects that its output target is an unsecure texture and aborts initialization with `NO_MEMORY` (a common obfuscation for DRM memory routing failures).
- **Conclusion:** The Media3 VideoGraph (`setVideoSurfaceView`) approach is **dead** for UHD/HDR Widevine L1 on this device. We cannot emulate Tiledmedia's protected GL rendering pipeline using standard Media3 tools if the platform driver refuses to provide a protected EGL context to our app.

### Latest Code Removal: Reverting Surface Format Overrides
Based on deeper investigation of the decompiled Tiledmedia SDK:
- The official app's `TiledmediaView` and its helper `TMSurfaceView` create a plain `SurfaceView` and call `setSecure(true)`.
- It does **not** call `setFormat(PixelFormat.OPAQUE)`.
- It does **not** explicitly call `setDataSpace(DATASPACE_BT2020_HLG)`.
- We realized that forcing `PixelFormat.OPAQUE` or an explicit dataspace might break the DRM and hardware composer's implicit negotiation for secure YUV buffers, leading to the green screen.
- **Action Taken:** Removed `playbackSurfaceView.holder.setFormat(android.graphics.PixelFormat.OPAQUE)` and `HdrSurfaceHints.applyBt2020HlgDataSpace` from `ChannelPlaybackFragment.kt`. Left `playbackSurfaceView.setSecure(true)` intact. The Direct Media3 route will now pass a completely vanilla secure `SurfaceView` to ExoPlayer.

### 2026-06-08 Test: MediaTek Surface Size Race Condition & Codec Overrides
In the latest iteration, we identified two highly likely culprits for the green screen:
1. **The MediaCodec Overrides**: Our `OfficialLikeDirectHdrMediaCodecVideoRenderer` was forcefully injecting `KEY_COLOR_TRANSFER_REQUEST`, `KEY_PRIORITY = 0`, and unsetting the `KEY_OPERATING_RATE`. The official app's player simply lets ExoPlayer extract the standard flags from the DASH manifest. Forcing `KEY_COLOR_TRANSFER_REQUEST` may have caused a double-mapping bug on the MediaTek decoder.
2. **The Surface Size Race Condition**: ExoPlayer's `MediaCodecVideoRenderer` processes the first frame asynchronously. Leanback's `VideoSupportFragment` binds the `SurfaceView` at its initial layout size (1920x1080). If the decoder delivers the very first 3840x2160 HDR 10-bit YUV frame to the composer while the surface bounds are still 1080p, the MediaTek MT8696 hardware composer panics, corrupts the pipeline, and permanently locks the video plane to green. The subsequent resize triggered by ExoPlayer's `onVideoSizeChanged` is too late.

**Fix Applied (Awaiting Final User Verification):**
- **Removed** `enableOfficialLikeDirectHdrCodecConfig` so ExoPlayer negotiates with `MediaCodec` natively without our overrides.
- **Proactively forced** the `SurfaceView` fixed size to `3840x2160` inside `configurePlaybackVideoSurface` (before the player is even initialized) if `looksLikeHdrUhdWidevine(viewing)` is true. This guarantees the composer sees a 4K surface for the very first frame.

### 2026-06-08 Comprehensive Surface Pipeline Cleanup (Second Pass)

**Context:** Toast diagnostic confirmed `EGL false / attempted true`. Device does NOT expose `EGL_EXT_protected_content` extension string. `shouldUseProtectedRenderer=false` so app routes to `ChannelPlaybackFragment` direct SurfaceView path.

**Critical bugs found and fixed in this session:**

1. **Tunneling still enabled** — `setTunnelingEnabled(true)` was in `ChannelPlaybackFragment.applyPlaybackVideoConstraints()` despite the handoff docs saying it was "removed after testing". Tunneling conflicts with custom radio (which needs audio path control). Now removed with a comment explaining why.

2. **Pixel format override** — `playbackSurfaceView.holder.setFormat(PixelFormat.TRANSPARENT)` was being called in `configurePlaybackVideoSurface`. This forces an explicit pixel format negotiation which may interfere with the MediaTek HWC's implicit secure-buffer pipeline. Removed.

3. **Dataspace/format overrides still active** — `applyBt2020HlgDataSpace()` in `bindPlaybackVideoSurface` was calling `setFormat(RGBA_1010102)` and `SurfaceControl.Transaction.setDataSpace(BT2020_HLG)` for the direct HDR path. The official Tiledmedia app only calls `setSecure(true)` and does NOT override pixel format or SurfaceControl dataspace. These explicit overrides break the MediaTek HWC's implicit secure YUV buffer negotiation. Now gutted to a no-op with explanation.

4. **OfficialLikeHdrPlaybackFragment** still had:
   - `enableOfficialLikeDirectHdrCodecConfig = true` → MediaCodec overrides still active on that path
   - `surfaceView.holder.setFormat(PixelFormat.OPAQUE)` → explicit pixel format
   - `HdrSurfaceHints.applyBt2020HlgDataSpace()` called 4 times (surfaceCreated, surfaceChanged, videoSizeChanged, onCreateView-post)
   - `holder.setSizeFromLayout()` resetting the proactive 4K fixed size back to 1080p
   All removed. Now only `setSecure(true)` + `holder.setFixedSize(3840, 2160)`.

5. **Proactive 4K surface fixed size** now conditioned to only apply on the direct surface path (not when protected HLG graph is active, which manages its own EGL surface size).

**Build installed:** `com.st14n.f1.debug`, logcat cleared.

**Expected toast in next test:**
```
EGL: false | Path: VANILLA-SV | Attempted: true
```

**Expected log markers in next test:**
```
Configured direct secure Media3 HDR SurfaceView (vanilla — no format/dataspace override) ...
Preemptively setting SurfaceView fixed size to 3840x2160 ...
Created official-like secure HDR SurfaceView (vanilla: only setSecure) ... fixedSize=3840x2160
```

**What the next test proves or disproves:**
- If green screen is resolved: the pixel format / dataspace / tunneling / MediaCodec overrides were the culprit.
- If still green: the issue is deeper in ExoPlayer's MediaCodec configuration or the MediaTek display pipeline itself, and we've exhausted the surface-manipulation angle.

### Remaining No-License Possibility Matrix

Still plausible enough to test:

- Testing the cleaned-up direct secure `SurfaceView` route (now completely devoid of pixel format, dataspace overrides, and MediaCodec overrides) with the **proactive 4K surface fixed-size**.
- HLS CMAF-WV versus DASH-WV. Both reached HLG/BT.2020 decode; DASH-WV is the preferred path.

Not viable without changing product constraints:

- Bitmovin Android SDK, unless a valid license is obtained.
- Tiledmedia/ClearVR SDK, unless legitimately obtained. This remains closest to the official app path.
- LibVLC/mpv/FFmpeg user-space decoding for the real F1 UHD stream, because Widevine L1 protected UHD/HDR output cannot be decoded in app-visible software buffers.
- Android `TextureView` for true HDR. Android documentation explicitly points HDR playback toward `SurfaceView`; `TextureView` has limited HDR support on Android 13+ and tends toward SDR/transcode behavior.
- A simple platform `MediaPlayer` swap. It does not solve the custom DASH/Widevine license/header/audio requirements that Media3 is already handling.
