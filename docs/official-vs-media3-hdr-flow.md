# Official F1TV vs Media3 UHD/HDR Flow

Date: 2026-06-06
Device: Google TV Streamer, Philips HDR display

## Executive Summary

The current app reaches the correct F1TV UHD/HDR Widevine stream and decodes it with the same secure MediaTek HEVC decoder used by the official app. The failure is after decode, at protected HDR frame presentation.

The official app does not appear to solve this through a normal Android/Media3 `SurfaceView` handoff. It ships and uses the Tiledmedia/ClearVR stack, including native renderer and DRM/display bridge code. That is a materially different playback pipeline from our Media3 player.

## Our Latest Failing Media3 Flow

Package:

- `com.st14n.f1.debug`

Stream:

- PLAY API accepted `HDR_UHD_DASHWV`.
- Manifest contains both HDR and SDR HEVC tracks.
- Selected video track is `_HDR-UHD_HEVC_2`, 3840x2160, 50fps, Widevine CENC.
- Latest run used Akamai `f1prodlive.akamaized.net` segment URLs, so the CDN host is not the root cause.

DRM/decoder:

- Widevine L1 license path is accepted.
- Decoder is `c2.mtk.hevc.decoder.secure`.
- Media3 reports the decoder input format as `_HDR-UHD_HEVC_2`.

Surface/display experiments that have now been ruled out:

- Companion SDR audio merge removed.
- Embedded DASH audio removed via video-only `FilteringMediaSource`.
- Automatic custom-radio injection removed.
- ExoPlayer bound explicitly to the Leanback `SurfaceView`.
- `SurfaceView.setSecure(true)` and `FLAG_SECURE` applied.
- Player startup delayed until the video surface exists.
- SurfaceHolder fixed buffer size forced to 3840x2160 after video size is known.
- App-forced 4K50 / `Surface.setFrameRate` path tested.
- App-forced 4K50 / `Surface.setFrameRate` path then disabled, allowing platform negotiation.
- MediaTek synchronous MediaCodec queueing tested.
- Media3 tunneling tested earlier and removed after it did not fix the green output.

Latest failed run proof points:

- Logs include the older protected-surface diagnostic markers, including video-only HDR filtering and app-forced timing experiments.
- Track selection still chooses `_HDR-UHD_HEVC_2`.
- Codec output switches to HLG/BT.2020:
  - `color-standard = 6`
  - `color-transfer = 7`
  - `android._dataspace = 302383104`
- Media3 reports `surfaceSize 3840x2160`.
- Media3 reports `onRenderedFirstFrame`.
- User still sees black until HDR engages, then full green.
- Repeated `CCodecBufferChannel: no present fence` appears after HLG frame output.

Conclusion for our flow:

- Stream discovery is working.
- DRM/license is working.
- Decoder selection is working.
- Secure surface allocation is working.
- 4K surface size is working.
- The remaining failure is the protected HLG presentation handoff from secure MediaCodec output through the normal Android/Media3 `SurfaceView` path.

## Official Working Flow

Package:

- `com.formulaone.production`

Activity:

- `com.formulaone.production/com.avs.f1.ui.tiledmediaplayer.TiledPlayerActivityTv`

Layout/resources:

- `res/layout/activity_tiled_player.xml` contains `com.tiledmedia.clearvrview.TiledmediaView`.
- `res/layout/activity_player_tiled_tv.xml` contains the TV controls/overlays around the tiled player.
- Official also ships Bitmovin layouts/assets:
  - `activity_player_bitmovin.xml`
  - `fragment_bitmovin_player.xml`
  - `view_bitmovin_player.xml`
  - `assets/bitmovinplayer-ui.js`
  - `assets/bitmovinplayer-ui.css`
- The working UHD TV path in logs is the Tiledmedia activity, not the normal Bitmovin player activity.

Native libraries shipped by the official app:

- `libClearVRNativeRendererPlugin.so`
- `libClearVRUtils.so`
- `libClearVRGoogleCardboardSDKWrapper.so`
- `libGfxPluginCardboard.so`
- `libgojni.so`
- `libSigmaAudioAndroid.so`
- `libspatialaudio.so`
- `libbf93.so`

Native/bridge strings found in the official APK:

- `createVideoDecoder`
- `createDRMSessionBridge`
- `provideKeyResponse`
- `getKeyRequest`
- `getKeyRequestChallenge`
- `sendRendererFrame`
- `FrameOutputMSEStyle`
- `MSEInitSegments`
- `segmentDownloadStarted`
- `NetworkInterceptor`
- `DisplayOutput`
- `DisplayInput`
- `VideoDecoderOutput`
- `DRMBridgeResult`

HDR/protected GL strings found in the official APK:

- `EGL_EXT_protected_content`
- `EGL_PROTECTED_CONTENT_EXT`
- `EGL_EXT_gl_colorspace_bt2020_hlg`
- `EGL_GL_COLORSPACE_BT2020_HLG_EXT`
- `EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_HLG`
- `BT.2020 HLG OpenGL output isn't supported.`
- `isBt2020HlgExtensionSupported`

Official working log proof points:

- Official starts `TiledPlayerActivityTv`.
- Official uses a `SurfaceView` layer associated with that activity.
- Official creates `c2.mtk.hevc.decoder.secure`.
- Official output also switches to HLG/BT.2020:
  - `color-standard = 6`
  - `color-transfer = 7`
  - `android._dataspace = 302383104`
- Official works visually on the same device/display.

Conclusion for official flow:

- Official is not merely using the same normal MediaCodec-to-`SurfaceView` presentation flow.
- Official bundles a Tiledmedia/ClearVR native playback engine that owns DRM, decoder, MSE/network segment flow, frame output, and a protected EGL/HLG-capable renderer.
- The working difference is almost certainly the protected renderer/display bridge, not headers, stream type, bitrate, or the MediaTek decoder alone.

## Media3 Protected-GL Check

The cached Media3 `VideoDecoderGLSurfaceView` class is not a drop-in replacement for secure Widevine L1 MediaCodec output:

- `VideoDecoderGLSurfaceView` extends `GLSurfaceView`.
- Its public API consumes `VideoDecoderOutputBuffer`.
- That path is for decoder-output-buffer renderers, not the secure `MediaCodec` surface path used by Widevine L1 protected HEVC.

Media3 does expose OpenGL tone-mapping hooks through `MediaCodecVideoRenderer`, but those do not reproduce Tiledmedia's native protected EGL renderer. We already tested tone-mapping-related renderer factory changes without solving green output.

## Practical Decision

Stop spending cycles on small Media3 surface toggles. The following have already been ruled out as primary fixes:

- More F1 header changes.
- Switching HLS versus DASH-WV.
- Companion audio merge changes.
- Custom radio changes.
- SurfaceView security flag changes.
- Surface fixed-size changes.
- App-forced frame-rate/display-mode changes.
- Media3 tunneling.
- MediaCodec sync/async queueing changes.

Viable paths from here:

- Build or integrate a protected EGL/HLG renderer path equivalent to Tiledmedia/ClearVR. This is effectively a native player/rendering project, not a normal Media3 setting.
- Use a legitimate licensed player/renderer that provides that protected HDR presentation path. The official working path points more to Tiledmedia/ClearVR than ordinary Bitmovin.
- Keep Media3 as the fallback for SDR/HD and non-problematic devices, but treat true UHD/HDR on this Google TV Streamer as blocked by the Media3 secure-surface presentation path unless we add a different renderer.

## Path 1 Started: Protected Renderer Boundary

New app-side boundary:

- `ProtectedHdrCapabilitiesProbe`
- `ProtectedHdrRendererRouter`
- `ProtectedEglSurfaceProbe`
- `ProtectedHlgGlObjectsProvider`

Files:

- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/protectedhdr/ProtectedHdrCapabilities.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/protectedhdr/ProtectedHdrRendererDecision.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/protectedhdr/ProtectedHdrStreamClassifier.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/protectedhdr/ProtectedEglSurfaceProbe.kt`
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/protectedhdr/ProtectedHlgGlObjectsProvider.kt`

What it does now:

- Probes EGL display extensions at runtime.
- Logs whether the device exposes:
  - `EGL_EXT_protected_content`
  - `EGL_EXT_gl_colorspace_bt2020_hlg`
- Probes the actual playback `SurfaceHolder.surface` by attempting to create and bind a protected BT.2020 HLG EGL window surface, then immediately destroys it.
- Adds an explicit renderer decision before opening playback.
- Opens the Media3 protected-HLG graph path from `openWithProtectedHdrRenderer` when the stream is UHD/HDR Widevine and EGL exposes protected HLG support.
- Keeps direct Media3 `SurfaceView` playback as fallback when the protected graph is unavailable or throws a player error.
- Adds an experimental Media3 protected-HLG graph path:
  - forces Media3 to use `VideoSink`/`PlaybackVideoGraphWrapper` for HDR streams by setting an empty video-effect list
  - supplies a custom `GlObjectsProvider`
  - creates protected BT.2020 HLG EGL output surfaces with `EGL_PROTECTED_CONTENT_EXT` and `EGL_GL_COLORSPACE_BT2020_HLG_EXT`
  - keeps HDR video real HDR
  - merges a standard audio companion for UHD/HDR when available instead of leaving normal playback in video-only diagnostic mode
  - retries the same HDR stream once with direct Media3 `SurfaceView` output if the protected graph throws a player error

Why this matters:

- The code no longer treats Media3 as the only internal rendering path.
- The Activity route now matches the actual implementation; `openWithProtectedHdrRenderer` is no longer a placeholder.
- If a native renderer bridge becomes available later, routing HDR playback no longer requires reworking stream acquisition, F1 auth, Widevine URL construction, or Media3 fallback.
- This is the closest available Media3-native attempt at the official protected EGL/HLG presentation boundary without a licensed Tiledmedia/ClearVR SDK.

What is still missing:

- A native bridge equivalent to the official Tiledmedia/ClearVR stack:
  - protected EGL surface creation
  - Widevine/MediaCrypto session bridge
  - secure decoder creation/configuration
  - segment/MSE demux path
  - protected frame scheduling/presentation
  - audio sync/output

Important constraint:

- Media3's built-in `VideoDecoderGLSurfaceView` is not enough for this protected Widevine L1 stream because it consumes `VideoDecoderOutputBuffer`, not secure `MediaCodec` output surfaces.
- A real path 1 implementation must either own the native decode/display path or integrate a renderer SDK that does.
- The new protected-HLG graph may still fail if the device/DRM stack rejects secure decoder output into Media3's GL input surface. The direct Media3 HDR fallback remains in place for that case.
