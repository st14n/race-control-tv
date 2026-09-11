# ClearVR clean-room architecture notes

This document records observable behavior from the installed official APK and the independent implementation boundary for this app. It does not copy Tiledmedia source, native libraries, licenses, keys, or server authorization material.

## What the official path actually does

The official TV player has two layers:

1. F1 supplies either a TME payload or a Tiledmedia content URL. `ContentItemConfig` passes the payload/URL and DRM configuration to ClearVR's native core.
2. ClearVR's native core owns the MSE/media-flow loop. It chooses representations and tile feeds, downloads init/media data, parses access units and encryption metadata, and calls the Java decoder bridge with one header or one complete frame at a time.

The Java bridge then:

- configures `MediaCodec` with a small format (`mime`, coded width/height, optional frame rate, rotation, priority, and device quirks), without `csd-*` keys;
- queues initialization bytes separately with `BUFFER_FLAG_CODEC_CONFIG`;
- queues each access unit as clear or secure input using the core-provided PTS, KID, IV, and clear/encrypted subsample arrays;
- keeps a POID/PTS map from input frames to decoder output buffers;
- ignores the output format as a Media3 video-size signal and stores it only as frame metadata;
- holds decoded buffers until the native render stack explicitly releases them, using timestamped `releaseOutputBuffer` for direct-to-view mode;
- serializes surface changes, hard reconfiguration, discontinuities, drops, and decoder teardown.

The native-to-Java boundary is also materially richer than a normal DASH renderer callback. The
ClearVR core exposes a `NextFrameVideoOutput` containing separate `headers` and `data` byte arrays,
PTS, DTS, content time, and wall-clock time. The bridge turns those into a header input followed by
a frame input, while the native render loop supplies the presentation-order ID and the eventual
render timestamp. This is why a codec-config adapter alone cannot reproduce the full behavior:
Media3's extractor has already collapsed that MSE state into its own sample/timeline model before
the renderer sees it.

The direct TV mode uses a secure `SurfaceView` surface selected by the native renderer/view manager. It does not require the platform's protected EGL extension on this device; indirect texture mode is a separate capability-gated path.

## Why the current adapter was insufficient

The previous `TiledmediaLikeMediaCodecAdapter` reproduced only the separate header-buffer detail. Media3 still supplied the access units, encryption table, timestamps, format changes, crop/video-size interpretation, buffer release timing, and reconfiguration policy. The current logs show the consequence: the decoder reports a 3840x2160 protected output while the crop remains 640x360 or 1920x1080, and Media3 treats that as an ordinary video-format transition.

That is a different contract from ClearVR. Matching `MediaFormat` fields cannot make the two pipelines equivalent.

## Independent implementation target

The practical clean-room variant is a video renderer that keeps Media3 as the manifest/license/sample source but owns the codec boundary:

- read Media3's already-demuxed access units and `CryptoInfo` values;
- configure raw `MediaCodec` without Media3 `csd-*`/max-size/operating-rate additions;
- send initialization data separately;
- queue raw PTS and the source crypto subsample table unchanged;
- ignore vendor output crop as a representation-size signal;
- timestamp direct-surface releases from the playback position;
- make decoder/surface lifecycle explicit and log every reconfiguration/discontinuity.

This will tell us whether the remaining failure is caused by Media3's renderer contract or by the access units/DRM metadata already emitted by the DASH extractor. If it still produces the same `[LAT][Err]` signature, the next boundary is upstream of the renderer: the native Tiledmedia MSE parser is receiving a different F1 TME/player response or is constructing different access-unit/header/timestamp/DRM metadata. At that point the clean-room route is a purpose-built CMAF/MSE access-unit pipeline that emits the same kind of public MediaCodec inputs, not more `MediaFormat` tuning.

## Server-side boundary

The current `player_bm` response has `tmePresent=false`; the direct `player_tm` probe is rejected by F1 with `Failed to evaluate stream rule`. Therefore this app cannot obtain the official TME/native feed merely by changing a query parameter. An independent renderer can reproduce the public Android codec/render contract against the accessible subscription manifest, but it cannot manufacture Tiledmedia's server authorization or substitute for a legitimately supplied TME payload.

## Browser-authorized TME route (2026-08-23)

A browser capture from the same subscription shows a separate, authorized route:

- `WEB_HLS` + `player_tm` returns HTTP 200 with `tmePresent=true` and no top-level manifest URL.
- The response contains `streamType`, `drmType`, `laURL`, `pipelineVersion`, and a `tme` object.
- The TME object contains `metadata`, `advanced.tile_size` (`960x544`), `version`, and a `feeds` array. The captured response contained 26 channel feeds, each with a channel id, UUID, signed manifest URL, and audio/subtitle names.
- Without an override, the response is `SDR_HD_CMAFWV`. With authorized HDR overrides, the same route returned `HDR_UHD_CMAFWV` and `HDR_UHD_DASHWV` TME payloads. The channel feed paths were the corresponding HDR-UHD CMAF or DASH manifests.
- The Android-TV identity on `BIG_SCREEN_HLS` still receives the server-side rule failure, and changing only the Android identity on the web route also fails. The TME route is gated on the web identity/capability declaration, not just `player_tm`.

The app now has a clean-room diagnostic adapter that requests those web TME responses, selects the feed matching the requested channel, and passes that signed feed URL and returned license URL into the app's own Media3 pipeline. It does not embed the Tiledmedia SDK or reuse its native implementation. This isolates the useful question: whether the TME-delivered UHD CMAF/DASH packaging and DRM metadata avoid the green result. It is not yet equivalent to ClearVR's native MSE/tile scheduler, which still owns feed selection, access-unit parsing, DRM session handling, and protected frame presentation in the official player.

### TME playback test result

The diagnostic build was installed and tested against a real broadcast after wireless ADB was restored.

- The app selected `web_tme_HDR_UHD_CMAFWV`; the response was `OK`, `tmePresent=true`, Widevine, and contained no top-level manifest URL.
- The channel-specific feed was selected from the TME catalog. The app then used the returned TME license URL and the web-route device identity for the main video DRM request.
- The main feed reached `c2.mtk.hevc.decoder.secure`. It queued encrypted samples with the expected `cbcs` pattern metadata, produced a decoded frame, and transitioned through 360p, 1080p, and 3840x2160 output sizes.
- Decoder output reported BT.2020 HLG (`color-standard=6`, `color-transfer=7`, dataspace `302383104`). No `[LAT][Err]` lines were observed during this secure TME run.
- The user still observed solid green video.

Conclusion: TME feed acquisition and HDR-UHD CMAF packaging are not sufficient by themselves. The TME route changes the upstream feed and reaches decode, but the green result remains in the current app's Media3 protected-surface/presentation path. The remaining ClearVR difference is therefore still the native render/scheduling/DRM bridge, not merely the PLAY response or manifest URL. The TME adapter remains useful as a clean-room control path, but it is not yet a ClearVR-equivalent implementation.
