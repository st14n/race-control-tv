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

## 2026-06-14 Live vs Replay Findings

Current user-observed behavior:

- Current-week replay UHD/HDR can play when the DASH init repair path is active.
- Current live UHD/HDR still produces green video and was not falling back reliably.
- With the SDR tone-mapping setting enabled, replay previously black-screened because the global setting leaked into the SDR fallback renderer path.
- Custom radio policy must be based on the custom-radio settings and whether the session is live/replay; tone mapping must not decide radio availability.

Manifest/init comparison:

- `diagnostics/live_hdr_latest.mpd` is `type="dynamic"` with `minimumUpdatePeriod`, `timeShiftBufferDepth`, `availabilityStartTime`, live `publishTime`, and current `SegmentTimeline`/`startNumber`.
- `diagnostics/current_week_hdr/index.mpd` is `type="static"` with `mediaPresentationDuration` and a fixed segment timeline.
- The live and current-week replay HDR ladders are otherwise materially similar: both expose `HDR_UHD_DASHWV` style DASH-WV video with BT.2020/HLG CICP descriptors (`ColourPrimaries=9`, `TransferCharacteristics=18`, `MatrixCoefficients=9`) and `_HDR-UHD_HEVC_2` at `3840x2160/50`.
- `working_init.mp4` from the previously working stream has a complete `hvcC` box (`145` bytes) and declares `frma=hvc1`.
- Both current-week replay `diagnostics/current_week_hdr/hdr_uhd_init.mp4` and current live `diagnostics/live_hdr_uhd_init.mp4` have the same minimal/broken `hvcC` (`31` bytes), declare `frma=hev1`, and carry `schm=cbcs`.

Code conclusion:

- The current-week repair is not replay-only. Live uses the same broken/minimal init shape and also needs `F1DashInitSegmentFixingDataSource`.
- The previous live guard in `shouldRewriteF1DashUhdHdrInit(...)` was wrong because it disabled the one repair live now needs.
- The SDR fallback renderer must only enable HDR-to-SDR tone mapping for actual UHD/HDR viewings; SDR/HD fallback must use the normal graph.
- Custom radio auto-selection should be enabled only when `autoSelectCustomRadio` is true and a radio plan exists; it is allowed for live sessions and allowed for replay/non-live sessions only when `restrictCustomRadioToLiveSessions` is false.
- UHD/HDR playback that needs automatic custom radio must use the full `ChannelPlaybackFragment` path because `OfficialLikeHdrPlaybackFragment` currently does not host the custom radio engine or controls.

## 2026-06-14 Final Green Screen and 403 Fixes

### Fixed 403 Forbidden for Akamai UHD Segments
- **The Issue**: ExoPlayer failed to load the live UHD segments (giving a 403 Forbidden) and fell back to the 1080p SDR manifest. F1TV's f1prodlive.akamaized.net CDN redirects the index.mpd request to embed a mandatory Akamai hdntl token in the base URL. Our custom F1DashInitSegmentFixingDataSource was returning the pre-redirected URL (dataSpec.uri) instead of the final redirected URL. Thus, ExoPlayer failed to append the hdntl token to segment requests, which caused Akamai to reject them.
- **The Fix**: Modified F1DashInitSegmentFixingDataSource.getUri() to accurately report the resolvedUri (the redirected URL). ExoPlayer's DashManifestParser now inherits the hdntl token correctly, resolving the 403 Forbidden entirely.

## 2026-08-21 Live Session Investigation and Regression

Extensive same-day investigation during an actual live race weekend, with real device access. Summary of what was tried, what was ruled out with hard evidence, and a regression that was introduced and reverted.

### Dynamic hvcC extraction: fixed and confirmed genuinely working
- Rewrote the cache-key mechanism to use the resolved init-segment URI (verified against decompiled `DefaultDashChunkSource`/`DashUtil`) instead of a regex-guessed representation ID -- the old regex silently never matched on either CDN shape.
- Added a resolution-validation guard using Media3's own `NalUnitUtil.parseH265SpsNalUnit` to reject false-positive NAL matches. Initially compared against the wrong SPS field (`decodedWidth`/`decodedHeight`, the pre-crop coded picture size, e.g. 2176) instead of `width`/`height` (the conformance-window-cropped display size, e.g. 2160) -- this caused a real live test to incorrectly reject genuinely valid extracted parameter sets and fall back to stale hardcoded bytes. Fixed to compare the correct field.
- Fixed a missing-headers bug: `DynamicHvcCExtractor`'s side-channel segment fetch used a bare `HttpURLConnection` with no `User-Agent`/`Origin`/`Referer`/`x-f1-device-info` headers, causing Akamai's hdntl-tokenized CDN (still used for live and current-week replay) to silently serve unusable content. Fixed by sending the same headers as the rest of the app.
- **Confirmed working on a real live/current-week-replay session**: logs showed `Successfully extracted dynamic hvcC box for _HDR-UHD_HEVC_2 (size=145 bytes)` and `source=dynamic` in the repair log, with correct decoder init and HLG/BT.2020 output.
- **This did not fix the green screen.** Real, validated, freshly-extracted parameter sets, correct decode, correct color metadata -- still green. This is a clean negative result: missing/broken hvcC is not the root cause of the visual failure (though it was a real, separate bug worth fixing on its own merits).

### Protected EGL content: definitively ruled out, at both Java and native (NDK) level
- Added `app/src/main/cpp/` (CMake + NDK) with `protected_egl_probe.cpp`, probing `EGL_EXT_protected_content` and attempting actual protected context/surface creation through `libEGL.so` directly, matching how Tiledmedia's own `libClearVRNativeRendererPlugin.so` (`EGLRenderTarget` class, confirmed via string extraction from the decompiled official APK) does it.
- Result: `hasExtensionString=false`, `eglCreateContext` with `EGL_PROTECTED_CONTENT_EXT` fails with `error=0x3004` (`EGL_BAD_ATTRIBUTE`). Identical to the earlier Java-level (`EGL14`) probe result.
- This is not a Java-vs-native artifact -- the driver genuinely does not support the extension, at all, for any app. Since Tiledmedia's own native code checks for the same standard extension string, **whatever the official app actually does to work, it cannot be through a protected EGL context either** on this hardware. This closes off the entire "replicate Tiledmedia's protected renderer" theory that the project has circled since June.

### Window HDR color mode: tested, no effect, caused a regression when misapplied
- Added `Window.colorMode = ActivityInfo.COLOR_MODE_HDR` (the programmatic equivalent of the `android:colorMode="hdr"` manifest attribute removed in an earlier session) in both `OfficialLikeHdrPlaybackFragment` and `ChannelPlaybackFragment`, scoped to `looksLikeHdrUhdWidevine(viewing)`.
- In `OfficialLikeHdrPlaybackFragment` (direct/native HDR path): applied correctly, no crash, still green. App diagnostics (`HdrPresentationDiagnostics`) already reported `isHdr=true hdrConversion=PASSTHROUGH` even before this call ran, so the window was arguably already negotiating HDR regardless.
- **Regression**: the gating only checked "is this content inherently HDR/UHD Widevine", not "is *this specific playback attempt* the tone-mapped-to-SDR fallback". In `ChannelPlaybackFragment`'s `ToneMappedHdr`/`Standard` fallback attempts (`forceHdrToSdrToneMapping=true`), this told the window to expect genuine HDR output while the tone-mapping renderer was producing SDR pixels for it -- a real mismatch. Symptom: `videoSize=0x0` despite healthy buffering (26s buffered, `isPlaying=true`), first-frame watchdog firing, black screen -- worse than the pre-existing green screen, and it affected non-UHD/SDR playback too since the same fragment handles the generic fallback path.
- **Reverted entirely** (both fragments) rather than trying to re-scope it, since it had zero proven benefit and a demonstrated capacity for harm.

### SurfaceView Z-order: tested as a diagnostic, ruled out
- Hypothesis: Leanback's `VideoSupportFragment` keeps its `SurfaceView` at normal Z-order (`setZOrderOnTop(false)`, `setZOrderMediaOverlay(false)`) so its own overlay transport controls render above it in the ordinary view hierarchy -- but this could force SurfaceFlinger into GPU composition for that layer, which cannot read protected buffer content, producing green. The official app's `TiledPlayerActivityTv` uses a fully custom UI with no such constraint (confirmed via `TimeStats: [...][SurfaceView[com.formulaone.production/...TiledPlayerActivityTv](BLAST)...]` -- it is a standard BLAST-backed `SurfaceView`, not something exotic).
- Diagnostic test: forced `setZOrderOnTop(true)`/`setZOrderMediaOverlay(true)` in `OfficialLikeHdrPlaybackFragment`. Applied correctly (confirmed via logs), decoder/color markers all correct, no crash. **Still green.** Ruled out. Reverted to `false`/`false`.

### Systematic CCodecConfig/DRM diff against the official app: everything matches
Captured real logcat from the official F1TV app (`com.formulaone.production`, `TiledPlayerActivityTv`) playing the same live content on the same device, and diffed against our app's logs line-by-line. Every one of the following matched exactly between both apps:
- Decoder: `c2.mtk.hevc.decoder.secure`
- `algo.secure-mode.value = 1`
- `color-standard = 6`, `color-transfer = 7`, `android._dataspace = 302383104`
- `raw.pixel-format.value = 2130706439` (`0x7F000007`, MediaTek's opaque protected YUV format)
- WVCdm: `requested_security_level = Default`, `IsSecurityLevelSupported level = L1`

No device-level tracing tool (Frida/strace) is available without root (`ro.secure=1`, no `su`, `adbd cannot run as root in production builds` -- confirmed). Rooting this device was explicitly not pursued given the risk (unknown feasibility, could brick a device in active use, no small undertaking).

### Root cause: still unknown, six hypotheses eliminated
Missing parameter sets, protected EGL (Java and native), window HDR color mode, SurfaceView Z-order, pixel format, and DRM security level are all ruled out or confirmed identical to the official app. The difference must be somewhere not visible in `CCodecConfig`/`WVCdm`/decoder logs -- possibly Activity/Task/Window structural differences, or something that genuinely requires syscall/HAL-level tracing to observe.

### New finding: live UHD/HDR playback stuck in an infinite buffer-allocation retry loop
During this session's testing (after ~9 app reinstalls/force-stops), a live UHD/HDR playback attempt got stuck "loading forever" (never reaching green, black, or a player error) rather than the previously-typical outcomes. Captured in `diagnostics/live_session_2026-08-21_uhd_hdr_stuck_loading.txt`:

```
BufferQueueProducer: [SurfaceView[.../ChannelPlaybackActivity]#27(BLAST Consumer)27] dequeueBuffer: createGraphicBuffer failed
BufferQueueProducer: [...] requestBuffer: slot 11 is not owned by the producer (state = FREE)
BufferQueueProducer: [...] cancelBuffer: slot 11 is not owned by the producer (state = FREE)
```

1,770 consecutive occurrences in ~10 seconds (16:17:01.682-16:17:11.499), an unthrottled busy-loop with no backoff -- almost certainly still looping when the capture ended. `createGraphicBuffer failed` is a genuine graphics-memory allocation failure at the Gralloc/BufferQueue layer for the secure video buffer, not application logic. Given the unusually high number of forced app kills/reinstalls in this session (mid-decode, repeatedly), this is most likely accumulated/leaked secure-buffer memory pool state on the device itself rather than a new code bug -- **a device reboot should be tried first** before investigating this further, to rule out that explanation cheaply.

### Current state as of end of session (before reboot test)
- UHD/HDR disabled (plain SDR/HD): works.
- UHD/HDR native (`OfficialLikeHdrPlaybackFragment`): green (parameter sets, color, Z-order, window color mode all confirmed correct/ruled out as the cause).
- UHD/HDR with tone-mapping (`ChannelPlaybackFragment` `ToneMappedHdr`): black, even after reverting the window-colorMode regression -- this needs its own fresh investigation, separate from the green-screen native-path issue; not yet clear whether this is pre-existing or newly surfaced.
- Live UHD/HDR: stuck in the buffer-allocation retry loop described above -- try a reboot before re-testing.

### Fixed True Green Screen (10-bit HLG Decoder Corruption)
- **The Issue**: Even after fixing the parameter sets and the 403 forbidden error, the secure MediaCodec (c2.mtk.hevc.decoder.secure) was heavily corrupting the stream and outputting a green screen. Two severe bugs caused this:
  1. The custom F1DashManifestParser forcibly stripped bit-depth information from ExoPlayer's ColorInfo, overwriting the valid 10-bit color metadata with NA.
  2. The raw, hardcoded FULL_HVCC_BOX_LIVE and FULL_HVCC_BOX_REPLAY buffers that were previously extracted and injected both contained 0xF8 for bitDepthLumaMinus8 and bitDepthChromaMinus8. 0xF8 masks down to 0, which meant the decoder configuration was maliciously tricking the hardware into thinking the UHD stream was 8-bit instead of 10-bit.
- **The Fix**: 
  1. Eradicated F1DashManifestParser and removed it from the ExoPlayer builder.
  2. Hex-edited the hardcoded HEVC configuration arrays (FULL_HVCC_BOX_LIVE and FULL_HVCC_BOX_REPLAY) to contain 0xFA for the bit depth parameters, which correctly calculates to 10-bit (0xFA & 0x07 = 2, 2 + 8 = 10). ExoPlayer now accurately reports ColorInfo(BT2020, Limited range, HLG, false, 10bit Luma, 10bit Chroma) and the hardware decoder securely renders the HDR frames.

## 2026-08-05 Fresh Replay Test After ~7 Week Gap

Context: no live race weekend was running, so only replay could be tested. Device reconnected via `adb pair`/`adb connect` on a new IP after an ISP change (`192.168.178.151`); old fixed-port `adb tcpip 5555` session had to be re-established since Wireless debugging on this device only binds over Wi-Fi, not Ethernet.

### CDN moved from Akamai to CloudFront/MediaPackage
- Manifest/segment host is now `ott-video-fer-cf.formula1.com` with opaque, base64-token paths (`/v2/pa_<base64>/...../index.mpd`), served via CloudFront in front of AWS MediaPackage.
- Segment/init URLs no longer contain human-readable identifiers like `HDR-UHD-DASH-WV`, `_HDR-UHD_HEVC_2`, or `INDEX_VIDEO` anywhere in the path — everything is opaque tokens. This is very likely what was meant by "F1 no longer provides some content data it previously provided": the old URL-substring heuristics (`shouldRepair`, the old live/replay detector) have nothing readable left to match against.
- `shouldUseF1DashUhdHdrFixes` / `shouldRewriteF1DashUhdHdrInit` in `OfficialLikeHdrPlaybackFragment` still work correctly today only because they also match against `viewing.streamType` (e.g. `HDR_UHD_DASHWV`), not just the URL. Confirmed via log: main video source logged `f1UhdHdrFixes=true rewriteInit=true`, the SDR audio companion source (different streamType) correctly logged `f1UhdHdrFixes=false rewriteInit=false`.

### Init segment now ships a real hvcC, not the old broken 31-byte one
- Fresh replay session logged: `Expanded empty hvcC for F1 DASH UHD HEVC init segment hvcC=145->145 addedColr=true`.
- That means the init segment's `hvcC` box is now already 145 bytes (a real VPS/SPS/PPS-bearing box), not the historically-documented minimal/broken 31-byte one. `F1DashInitSegmentFixingDataSource.repairInitSegment` correctly detected `hvcc.size >= fullHvccBox.size` and kept the real extracted `hvcC` bytes as-is rather than substituting the hardcoded/dynamic fallback — it only appended the still-missing `colr` (BT2020/HLG NCLX) box, since `encv`/`hvc1`/`hev1` sample entries still carry no `colr` child.
- This is a genuine upstream change on F1's side (they now embed a complete hvcC), not something introduced by our code.

### Result
- Decoder: `c2.mtk.hevc.decoder.secure` initialized normally.
- Output format switched to `color-standard=6`, `color-transfer=7`, `android._dataspace=302383104` (BT.2020 HLG), matching all previously-documented good markers.
- `onRenderedFirstFrame` fired.
- No `Player error`, no crash, no fallback to SDR anywhere in the ~35s capture; playback ended cleanly on `videoDecoderReleased` when the user stopped it.
- User confirmed: **replay worked** (first clean replay HDR result recorded in this doc).
- Full log: `logcat_replay_2026-08-05.txt` (repo root).

### Still open
- Live could not be tested (no live session running at capture time). The live-vs-replay hvcC divergence documented on 2026-06-14 has not been re-verified against the new CloudFront CDN; live's init segment may or may not have picked up the same "real hvcC" upstream fix as replay.
- Correction (2026-08-05): the claim below that live was "never exercised against real data in any saved log" was wrong. It was based on a `grep -l "isLiveSession=true"` search across the repo's `.txt` logs, which came back empty — but several of those raw logcat captures were UTF-16 encoded (evidenced by the `_utf8` sibling copies that existed alongside them, e.g. `logcat4_utf8.txt`), and plain `grep` silently finds nothing in UTF-16 text. Those raw logs (and their `_utf8` copies) were then deleted in a later cleanup pass before this was caught, so the actual log lines are gone. However, the substantive finding survived in prose: see "2026-06-14 Live vs Replay Findings" below, written the same day as the Spanish GP weekend (practice/qualifying/race) — live UHD/HDR was confirmed producing green video that weekend. Lesson for next time: convert/verify encoding (`iconv`/`file`) before trusting a text search returned zero results, especially on older raw adb captures.
- User's working assumption (2026-08-05): live traffic likely still goes through Akamai (not migrated to the CloudFront/MediaPackage CDN replay now uses), so live should be assumed to still ship the broken/minimal `hvcC` until proven otherwise. The existing repair logic already handles this correctly regardless of CDN — `F1DashInitSegmentFixingDataSource.repairInitSegment` only trusts the real extracted `hvcC` when it's already `>= fullHvccBox.size`, and falls back to the hardcoded/dynamic parameter sets otherwise — so no code change should be needed, only a live re-test once a session is available to confirm.
- `F1DashManifestParser.kt` (CICP-descriptor-based `ColorInfo` injection) exists in the tree but is **not wired into any `DashMediaSource.Factory`** — it's dead code from an in-progress experiment, distinct from the eradicated original mentioned above.
- Scratch debugging artifacts at repo root (`TestExtractor.java`/`.class`, `fix.py`, various `logcat_*`/`dumpsys_*`/`*.mp4` captures) are untracked and should be cleaned up or gitignored before committing the real fix.

## 2026-08-22 Systematic elimination — root cause narrowed to encrypted HEVC on this decoder

A full day of controlled, single-variable tests. Each item below was tested and is
**ruled out** as the cause of the green screen:

| Hypothesis | Test | Result |
|---|---|---|
| Our rendering path / surface / Leanback | Played a synthetic 4K 10-bit HLG BT.2020 clip through the *same* fragment/surface/decoder | **Plays perfectly** |
| `hev1` in-band parameter sets | Same clip remuxed `hev1` vs `hvc1` (verified by byte count in logs) | **Both play perfectly** |
| Our custom DASH code | `VANILLA_MEDIA3_DIAGNOSTIC` — no custom extractor/manifest parser/init rewriter | Still green |
| Our `hvcC` injection | Init-segment rewriting disabled entirely | Still green |
| DRM / secure buffers / TEE / HDCP | Forced Widevine **L3** → non-secure decoder `c2.mtk.hevc.decoder`, clear content | Still green |
| Compositing / HDMI / display | `screencap` with FLAG_SECURE+setSecure off | Buffer is **uniformly RGB(0,26,0)** — zero-filled, pre-display. UI composites fine in the same window |
| Sample decryption parameters | Logged `CryptoInfo`, verified against the real `tenc` box | Pattern (1:9), constant IV == KID: **all correct, matches F1's actual packaging** |
| Two concurrent DRM sessions | `SKIP_COMPANION_AUDIO_DIAGNOSTIC` — exactly one `CONTENT/PLAY` per session | Still green |
| Decoder configured directly at 4K | Adaptive start (decoder configured at 480x270) | Still green at every resolution |
| Hardware decoder tone mapping | `KEY_COLOR_TRANSFER_REQUEST=SDR` | Silently ignored (`color-transfer-request = 0`, output stays HLG) |
| Window HDR colour mode / SurfaceView Z-order / HDCP settle delay | Each tested individually | No effect (colorMode caused a black-screen regression, reverted) |

### Automated stream-variant probe (30 combinations)

`STREAM_VARIANT_PROBE` cycles every override x platform. **Platform is irrelevant** —
`BIG_SCREEN_HLS`, `BIG_SCREEN_DASH` and `WEB_DASH` give identical results. Only three
outcomes exist:

1. **HDR + Widevine** (`HDR_UHD_DASHWV`, `HDR_UHD_CMAFWV`) → `c2.mtk.hevc.decoder.secure`,
   `color-transfer=7`, first frame renders → **green**.
2. **SDR fallback** (everything else) → `c2.mtk.**avc**.decoder.secure` (H.264, *not* HEVC),
   `color-transfer=3` → **works**.
3. **Unencrypted HDR** (`HDR_UHD_DASH`, `HDR_UHD_CMAF`) → `drmType=null`, no licence URL, but the
   manifest itself returns **HTTP 403** `x-amzn-ErrorType: AccessDeniedException` /
   `{"Message":"Access denied."}` from AWS MediaPackage. F1 deliberately does not serve
   unencrypted 4K HDR to consumer clients. Not fixable client-side.

### Where this leaves the root cause

The evidence converges on: **encrypted (cbcs) HEVC decodes to blank frames on this
MediaTek decoder**, while
- *clear* HEVC (incl. 4K 10-bit HLG) decodes correctly, and
- *encrypted* AVC/H.264 decodes correctly.

Because F1's only HEVC content is 10-bit HDR and its only SDR content is AVC, we cannot
locally separate "cbcs + HEVC" from "cbcs + 10-bit" — there is no counter-example in F1's
own ladder. This matches a cluster of *currently unresolved* MediaTek decoder issues in the
Media3/ExoPlayer tracker (androidx/media #2711, #2765, #2452; ExoPlayer #10890 — Xiaomi,
Vivo, OnePlus, Redmi), all "decoder initialises, no error, black/frozen/garbage output".

**Unresolved contradiction:** the official F1 app plays the *same* representation on the
*same* device with an identical `CCodecConfig` and a byte-identical SurfaceFlinger layer.
It uses Tiledmedia/ClearVR's own native demux/render pipeline, so it very likely does not
feed MediaCodec's crypto path the way Media3 does. That difference is not observable from
outside the app without TEE/syscall tracing, which needs root (device is `ro.secure=1`,
no `su`).

### Remaining untested idea

Package the synthetic HLG clip with **cbcs + ClearKey** and play it. That is the one test
that would separate "cbcs + HEVC" from "cbcs + 10-bit" definitively. It needs
shaka-packager and a ClearKey setup. Note that even a positive result yields no app-side
fix — it would only confirm a platform defect worth reporting upstream.
