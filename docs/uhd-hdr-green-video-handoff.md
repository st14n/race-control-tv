# UHD/HDR Green Video Handoff

Date: 2026-06-06
Device under test: Google TV Streamer at `192.168.178.158:5555`
Workspace: `C:\race-control-tv-st14n`

## TL;DR

The app can now obtain F1TV's UHD/HDR Widevine stream (`HDR_UHD_DASHWV`) and Media3 can decrypt/decode it with the secure MediaTek HEVC decoder. The remaining failure is visual presentation: the video plane is green even though logs show frames render.

Official F1TV playback works on the same device. The main known architectural difference is that the official TV UHD path uses Tiledmedia's `TiledPlayerActivityTv` / `TiledmediaView` with a protected EGL render target, not our Leanback + Media3 `VideoSupportFragment` surface path.

Copying the official app "exactly" is not realistic or safe as-is because it would mean shipping proprietary Bitmovin/Tiledmedia SDK code/assets/native libraries and likely a licensed player key. A proper migration to Bitmovin SDK may be possible if a valid SDK/license is available, but the official UHD TV player appears to be Tiledmedia-backed. Bitmovin alone may still use the same Android MediaCodec/Surface path and may not fix the green HDR output.

Current direction after rejecting licensed SDKs: keep Media3, keep requesting the real UHD/HDR stream, and fix the protected HDR presentation path instead of avoiding HDR.

## 2026-10-13 10:20: Real Streamer L1 GRANT — HW_SECURE_DECODE ACHIEVED (READ FIRST)

**STATUS: L1 GRANT ACHIEVED.** On the real Google TV Streamer
(`adb -s 192.168.178.151:5555`, kirkwood / MediaTek mt8696 / OP-TEE fused,
Android 14, Widevine CDM `18.0.0@343700000`), the debug app
(`com.st14n.f1.debug`, auto sign-in with the stored premium creds) played the
2026 Singapore GP replay and received an **HTTP 200 L1 (HW_SECURE_DECODE)
license** from the TV endpoint
`https://f1tv.formula1.com/3.0/R/ENG/BIG_SCREEN_HLS/ALL/CONTENT/LA/widevine_l1?contentId=1000010398&player=player_bm`
(POST 2542B attestation -> 694B license, 118.4ms). CDM key status:
`PlayAllowed=True, PersistAllowed=True, LicenseType=Streaming,
PlaybackDurationRemaining=345600 (96h), RenewAllowed=False`. The replay
played back in HDR on the streamer.

**Why the 09:36 streamer run fell back to 1080p SDR:** the debug build's
`disable_uhd_manifests` pref was still ON, so `preferHdrManifestForDevice=false`
and the app never even requested the HDR manifest (no Widevine UHD license
POST at all). The user turned the setting OFF; the 10:20 run then logged
`preferHdrManifestForDevice=true` -> `actualStreamType=HDR_UHD_DASHWV`,
`drmType=widevine`. Manifest token: kid=104, exp=1791966007, ttl=1440,
geo:NL, sessionId `35198171_01M4T5HWD4E7ZP044GHBJSQ1D_PP`.

**Decoded L1 request (2542B)** — pristine natively-signed streamer
attestation (rewriter OFF, `spoofOfficialIdentity=false`): app_info KVs
`application_name=com.st14n.f1.debug` (DEBUG name),
package_certificate_hash = debug cert
`bu9VD8NtGpFtE03emuyECzreLlE3vfDk42pqGalzUyA=`, `company_name=Google`,
`model_name=Google TV Streamer`, `device_name/product_name=kirkwood`,
`architecture_name=armeabi-v7a`,
`build_info=google/kirkwood/kirkwood:14/UTTK.260317.003/15205007:user/release-keys`,
`widevine_cdm_version=18.0.0@343700000`, oem_json
`{"soc_vendor":"MediaTek","soc_model":"mt8696","ta_ver":"18.5.0+Jul 29 2025...",
"uses_opk":true,"tee_os":"OP-TEE","tee_os_ver":"3.18","form_factor":"Tablet",
"fused":true,...}`. Device block (f6) all-varint caps
`{1,1,5,18,0,0,0,1,0,3,1,1}`; 72B DER signature; oem f9 90B
('MediaTek'/'mt8696'); f10=2. Requested keys: content
`6a6c85fbebf14256a708b901de6abe90` + provider
`05DDE7F99D3AAD0C8404000000000000` ('buydrmkeyos').

**Decoded L1 grant (694B):** f1=2; f2 LEN=391 { signing KeyContainer
`05DDE7F99D3AAD0C8404000000000000` + `5d2ab38a...`; policy f1=f2=1,
f4=f5=f6=345600, f15=1; 102B KeyContainer; content KeyContainer
f1=`6a6c85fbebf14256a708b901de6abe90`, f2=`e1d88421865c6ecef54e0eacf38675f1`,
f3=32B IV, f4=2, f5=1, f6/f7 12B, f8 18B, f12='HD' }; f4=**91B ECDSA P-256
DER signature**; f7='19.15.0'; **f8=2**; f9=153B oem; f11=0.

**CORRECTED L1/L3 DISCRIMINATOR (supersedes "KeyContainer f6=2"):** diffing
the streamer 694B grant vs the emulator 860B grant
(`tools/wv/diff_l1_l3.py`): exactly two envelope fields differ — **f8: 2 (L1)
vs 1 (L3)** and **f4 signature: 91B ECDSA P-256 DER vs 256B RSA**. Everything
else is identical (f1=2, f2 LEN=391, f3 LEN=32, f7 LEN=9, f9 LEN=153, f11=0)
and the **content KeyContainer is structurally identical in both grants**.
Success criterion: **envelope f8=2 + 91B ECDSA P-256 DER sig in f4 => L1
granted**.

**KID rotates per manifest:** `84BF0641...` (10-12) -> `4096C585...`
(10-13 emulator) -> kid 104 / `6a6c85fbebf14256a708b901de6abe90` (10-13
streamer). `splicer.py`'s hardcoded F1_KEY_ID is stale — read the kid live
from the DASH manifest ContentProtection.

**Artifacts (copied to `tools/wv/`, originals in `C:\Users\stijn\.qwen\tmp\wv\`):**
`key_request_hdr_20261013.bin` (2542B streamer L1 request),
`license_response_hdr_20261013.bin` (694B streamer L1 grant),
`license_response_20261013.bin` (860B emulator L3 baseline),
`key_request_20261013.bin` (2922B emulator L3 request),
`decode_out_20261013.txt` (full decoded dumps), `decode2.py` (protobuf
dumper), `diff_l1_l3.py` (envelope diff), plus the carried rewriter/matrix/
replay infra (`rw.py`, `matrix.py`, `host_replay.py`, `splicer.py`,
`find_session.py`).

**Validated:** replay path on a real L1 device (L1 grant + HDR playback),
AND the live code path via dry run (next section).
**Remaining:** definitive LIVE validation — drive `ChannelPlaybackActivity`
with `ARG_IS_LIVE_SESSION=true` + a currently-live contentId/sessionId
(seasonYear 2026) and confirm `widevine_l1` + envelope f8=2 at the live
edge. Next live window: **US GP 2026-10-23 00:00 → 2026-10-25 23:59 UTC**
(meetingKey 1297). Path B (host-side splice/replay) is unblocked: the
streamer produces pristine L1 attestations on demand via ADB, and the
emulator JWT is valid until ~2026-10-16/17.

## 2026-10-13 12:18: Live-Path Dry Run — ARG_IS_LIVE_SESSION=true Re-Granted L1

**Result: live code path validated on the real streamer.** Same Google TV
Streamer (`adb -s 192.168.178.151:5555`), replay content `1000010398`
(sessionId `1000010398`, seasonYear 2026) launched with
`ARG_IS_LIVE_SESSION=true` → viewing = `HDR_UHD_DASHWV` + `widevine`
(laUrl `.../LA/widevine_l1?contentId=1000010398&player=player_bm`), and the
app took the official-like secure SurfaceView path
(OfficialLikeHdrPlaybackFragment / ClearVrLikeRenderer). Fresh license
exchange: **2541B request → 694B response (HTTP 200)**; decoded response =
**envelope f8=2 (L1) + 91B ECDSA P-256 DER sig in f4** (identical shape to
the 10:20 grant; content key `6a6c85fbebf14256a708b901de6abe90`, f12='HD',
96h/345600 policy). CDM key status: PersistAllowed=True, PlayAllowed=True,
PlaybackDurationRemaining=345600, RenewAllowed=False,
LicenseType=Streaming.

**Playback verified continuous in real time:** first frame
(`ExoPlayerPlaybackTransportControlGlue: onTracksChanged`) at
12:18:28.590; at the capture tail (12:34:45) still playing — `mediaPos≈975s`
(16:15) matching ~16.25 min of real time since start,
`ClearVrLikeRenderer` rendering (`codec=true`), fetching HDR_UHD_DASHWV
video + SDR_HD_DASH_SINGLE audio segments with the **auto-renewed manifest
token (exp 1791973107)** — token renewal during long playback works.

**Live flag code path (verified in source):** `ARG_IS_LIVE_SESSION` only
affects the `F1DashInitSegmentFixingDataSource.Factory` (live vs replay
hardcoded hvcC fallback, `F1DashInitSegmentFixingDataSource.kt:339`) and
custom-radio gating (`ChannelPlaybackFragment.kt:1330`). It does NOT change
the license request path — consistent with the dry run re-granting L1
identically.

**Gotcha (repeat for the live test):** the streamer display MUST be awake
before `am start`: `adb -s 192.168.178.151:5555 shell input keyevent 224`
(verify `dumpsys power | findstr mWakefulness` → Awake). With the display
off the fragment commit crashes (`IllegalStateException: Can not perform
this action after onSaveInstanceState`, ChannelPlaybackActivity.kt:823)
and the error dialog it tries to show crashes too (pre-existing
non-AppCompat theme bug).

**No live session exists as of 2026-10-13.** Next live window: **US GP
2026-10-23 00:00 → 2026-10-25 23:59 UTC** (meeting id 1500000324,
meetingKey 1297 — `find_session.py` requires the meetingKey, not the
numeric id). Definitive live test:

```
python tools/wv/find_session.py 2026        # pick the in-window session
adb -s 192.168.178.151:5555 shell input keyevent 224   # WAKE DISPLAY FIRST
adb -s 192.168.178.151:5555 shell am start -n com.st14n.f1.debug/fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity --es fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CONTENT_ID <liveId> --es fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SESSION_ID <liveId> --ei fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SEASON_YEAR 2026 --ez fr.groggy.racecontrol.tv.ui.channel.playback.ARG_IS_LIVE_SESSION true
```

Keep logcat capture running; success = `widevine_l1` + envelope f8=2 +
playing at the live edge.

**Artifacts:** `tools/wv/key_request_live_dryrun_20261013.bin` (2541B),
`tools/wv/license_response_live_dryrun_20261013.bin` (694B, f8=2),
`tools/wv/logcat_live_dryrun_20261013.txt` (~5MB full capture), extraction
script `C:\Users\stijn\.qwen\tmp\wv\extract_live_dryrun.py`.

## 2026-10-13: Autonomous Emulator Run — Verified Working End-to-End

No rewriter active (`spoofOfficialIdentity=FALSE`): the app auto signed in
(fresh JWT, `14stijn@gmail.com`), played the latest session (contentId
`1000010398`), took the **Widevine TV path** — viewing response
`profile=android_tv_bitmovin_HDR_UHD_DASHWV`, `laUrl=.../LA/widevine_l1?contentId=1000010398&player=player_bm`
(NOT plain HLS) — POSTed a 2922B attestation and received **HTTP 200, 860B
license** (fresh logcat `logcat_emu_20261012.txt`, 2026-10-13 00:5x local). CDM
key status: `PlayAllowed=True, PlaybackDurationRemaining=345600,
LicenseType=Streaming`, then `drmKeysLoaded`.

**Root cause of the "1080p SDR fallback" (repeated user observation):** NOT a
license rejection. The license IS granted; the emulator's CDM/Surface HDR
decode path then fails with `ERROR_CODE_FAILED_RUNTIME_CHECK (1004)`
(IllegalStateException "Invalid to call at Released state"), the app falls back
to `SDR_HD_DASH_SINGLE` (1080p h264, no DRM) and THAT plays fine
(`rawState=3 isPlaying=true`, position advancing). On a real L1 device the
HDR path is what gets the secure decode.

**Extracted from this run (in `C:\Users\stijn\.qwen\tmp\wv\`):**
`key_request_20261013.bin` (2922B pristine emulator attestation, fresh
challenge+signature), `license_response_20261013.bin` (860B grant; top fields
f1=2, f2 LEN=391, f3=32, f4=256, f7=9, f8=1, f9=153, f11=0 — same shape as the
10-12 pristine grant), `license_post_headers_20261013.txt` (all POST headers +
both JWTs). Extraction script: `extract_20261013.py`.

**NEW KEY ID:** `4096C5857020FC1F2B00000000000000` (was
`84BF0641BBD357402300000000000000` on 10-12) — confirmed in RAW_KEY_REQUEST
and inside the license binary. Key IDs rotate per manifest/session; any
replay/splice tool must read the kid live from the DASH manifest
ContentProtection, never hardcode it.

**License POST auth for host replay:** `x-f1-device-info: device=android_tv;screen=bigscreen;os=android;model=kirkwood;osVersion=34;appVersion=30482001;playerVersion=3.112.0;tms=1;`,
`entitlementToken` + `ascendonToken` JWTs (exp 1792191088 ≈ 2026-10-16/17 UTC
→ host-replay window currently open), `Content-Type: application/octet-stream`.
Note the grant came from the TV `widevine_l1` endpoint (not the WEB_HLS one).

**Next (superseded 2026-10-13 10:20):** the L1 grant was subsequently
ACHIEVED on the real streamer — see the top section. `splicer.py`'s
hardcoded F1_KEY_ID is still stale — read the kid live from the DASH
manifest (10-13 streamer run: kid 104 /
`6a6c85fbebf14256a708b901de6abe90`).

## 2026-10-12: L1 License — Decisive Finding & Cold-Resume

**Goal (restated):** make F1TV's Widevine/KeyOS license server grant a
`HW_SECURE_DECODE` (L1, `security_level=2`) license to our app
(`com.st14n.f1.debug`) so we get UHD/HDR instead of 1080p SDR. Spoofing the
official identity is in scope. The LICENSE RESPONSE is the deliverable; real HW
decode is deferred to a real L1 device (the Google TV Streamer).

### DECISIVE FINDING (host replay, `tools/wv/matrix.py` -> `matrix_out.txt`)

We built a Python rewriter (`tools/wv/rw.py`, canonical) and replayed ~60
combinations of app_info / device-block / oem_json / signature edits against the
live F1 license server. Result:

- **V0 pristine** (raw emulator identity, byte-for-byte unmodified) -> **HTTP 200**
  (860B license, L3/HD grant, `security_level` = SW_SECURE_DECODE, `f12='HD'`).
- **ANY change to app_info KVs** (application_name, package_certificate_hash_bytes,
  oem_json, company/model/device/product/build/arch, cdm version) -> **403
  INVALID_SIGNATURE** (err 371000005). These KVs are inside the signed message M.
- **ANY change to the device block (f6)** (e.g. add `f16="HW_SECURE_DECODE"` +
  `f5=2`) -> **403 LICENSE_REQUEST_PARSE_ERROR** (370000001). The device block is
  NOT signature-covered but has a strict parse schema (pristine is all-varint
  `{1,2,4,5,6,8,9,10,11,12,13,14}`); a wire-type-2 string breaks parsing.

**Consequence:** the app's in-flight rewriter (pkg+cert+oem+devkvs+devblock)
CURRENTLY produces a 403 — the app gets NO license. The "1080p SDR fallback" is
the license-REJECTED path, not a capped-L3 grant. To claim L1 without a 403, the
server must see a SIGNED L1 attestation. Host-side editing cannot produce one.

### The L1 claim lives in TWO signature-relevant places

1. `oem_json` KV -> `form_factor:"L1"` (signature-covered, so forging it 403s).
2. device block `f5`/`f16` (`f5=2` + `f16="HW_SECURE_DECODE"`) (strict-parse,
   NOT signature-covered).

### TWO REMAINING PATHS

- **Path B (robust, RECOMMENDED):** capture a real L1 device's coherent
  attestation (app_info + challenge + signature) and splice it in, keeping the
  F1 key ID + a valid JWT. Needs a real L1 device — the user HAS a Google TV
  Streamer. This also answers the open question: does F1 gate L1 on the DEVICE
  attestation (L1 CDM -> L1) or on the APP identity (only
  `com.formulaone.production` -> L1)?
- **Path A (hard):** forge a fully self-consistent L1 attestation from scratch.
  Needs the 1472-bit curve params, the exact RSA-flavored signing (S=65537), the
  exact message-M serialization, the h32 derivation, and a matching private key.

### CRYPTO SHAPE (non-standard)

- signature = 270B = complete DER `SEQ(266){ R=INTEGER(257B=2048-bit),
  S=INTEGER(3B=0x010001=65537) }` (RSA public exponent, NOT standard ECDSA).
- public key = 369B = `04`+X(184B)+Y(184B) = 1472-bit, not on any known curve.
- h32 = 32B opaque (NOT sha256 of pub/X/Y/dev_id/blob256).
- Attestation = app_info f2 (1694B signed blob): f1 = 734B payload {f1=2,
  f2=32B dev_id, f3 varint ts, f4=270B sig, f5=33100, f9=1, f10=410B{f1=1,
  f2=0, f3=369B pub, f4=32B h32}}; f2=256B opaque TEE blob; f3=695B block695
  (f1.f4 = second 270B signature, != sig).

### PRISTINE 860B RESPONSE (the 200 / L3-HD grant)

Top f1=2; f2 LEN=391 { f1 LEN=66 key {f1=key ID `84BF0641BBD357402300000000000000`,
f2=16B encrypted key, f9=1791817125}, f2=37 license-policy, f3 LEN=102,
f3 LEN=126 {f4=2,f5=1,f12='HD'}, f4=1791817125, f7=1667392371, f14 LEN=38
{f6='buydrm'} }. Quality marker = `f12='HD'`; NO top-level f6; 'UHD'/'4K'/
'HW_SECURE'/'level' ABSENT. The `KeyContainer f6=2` (L1) marker must be located
against a REAL L1 response, not this L3/HD grant.

### APP TOKEN FLOW (verified 2026-10-12)

Web login stores a `login-session` cookie -> `URLDecoder.decode` -> JSON
`F1LoginToken.data.subscriptionToken` (a JWT) -> `CredentialsService.saveToken`
-> `TokenService.getToken()` -> `F1Client` sends it as the `ascendonToken`
header. `loadEntitlementToken(token)` does GET
`.../2.0/R/ENG/BIG_SCREEN_HLS/ALL/USER/ENTITLEMENT` (headers: User-Agent, Origin,
Referer, `x-f1-device-info`=f1DeviceInfo, `ascendonToken`, `CD-DeviceType=49`,
`CD-DistributionChannel`) -> `F1TvEntitlementResponse.token()` ->
`cachedEntitlementToken`. PLAY requests are GET
`.../%s/R/ENG/%s/ALL/CONTENT/PLAY?contentId=..&player=..` (+&channelId) with
those headers + optional `x-f1-override-video-stream`. The license URL at playback
comes from the viewing response's `laURL` field (NOT buildWidevineUrl).

The JWT was valid at capture time (exp=1792080930 -> 2026-10-15 16:15 UTC).
Key IDs rotate; re-capture if the JWT has expired.

### WV-vs-HLS SELECTION (F1Client) — changed 2026-10-12

Main video path = `Settings.StreamType.DASH` + `preferHdrManifestForDevice`
(= !disableUhdManifests && shouldRequestHdrManifest && seasonYear>=2026).
`isAcceptedHdrCandidate` requires (override or returned streamType looks
UHD/HDR) AND, for CMAF: `WV` present + drmType==widevine + laURL non-blank; for
DASH: drmType==widevine + laURL non-blank.

Two fixes applied so the app ALWAYS takes the Widevine (TV) path:

1. `requestedCandidates` order changed from `tme + hdr + standard` to
   **`hdr + tme + standard`** so the TV DASH-Widevine route (BIG_SCREEN_HLS +
   `f1DeviceInfo` android_tv/kirkwood, which returns the TV `widevine_l1`
   endpoint) is tried BEFORE the browser-tier TME route (device=web, L3
   `widevine` endpoint).
2. `getViewing` fallback-keep now also requires `!laURL.isNullOrBlank()` so a
   plain-HLS (no-DRM) viewing is never returned — the app always lands on
   Widevine.

### ACTIVE DRM PROVIDER (changed 2026-10-12)

`ChannelPlaybackFragment`: FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC=false,
LOG_KEY_RESPONSE_STANDARD_PATH=true -> both DASH and HLS sources use
`KeyStatusLoggingDrmSessionManagerProvider(httpDataSourceFactory)`.
**Default `spoofOfficialIdentity` is now FALSE** (the rewriter is proven to 403;
off = the CDM sends its own clean, natively-signed L1 attestation). Requests
securityLevel=L1 (forceL3=false). RAW_KEY_REQUEST is logged POST-rewrite, so with
spoof OFF it logs the CLEAN attestation; RAW_LICENSE_RESPONSE logs the grant.

### COLD-RESUME STEPS (to get the L1 license)

1. Build: `gradlew.bat :app:assembleDebug`.
2. Install on the **Google TV Streamer** (a real L1 device).
3. Log in with the premium F1 account (web login -> subscriptionToken).
4. Play a **UHD/HDR** race (2026 season, seasonYear>=2026).
5. Capture logcat: `RAW_KEY_REQUEST` (clean L1 attestation) +
   `RAW_LICENSE_RESPONSE`.
   - If the response has **envelope f8=2 + a 91B ECDSA P-256 DER signature in
     f4** -> **L1 GRANTED**. (ACHIEVED 2026-10-13 10:20 on the real streamer —
     see the top section. The earlier "KeyContainer f6=2" marker was a
     misdecode: the content KeyContainer is structurally identical in L1 and
     L3 grants.)
   - If only `f12='HD'` with envelope f8=1 -> that CDM is L3 (e.g. emulator).
     A real L1 device grants L1 regardless of app identity (verified with the
     debug app on the streamer); Path B (host-side splice/replay) is only
     needed to replay against the server from a non-L1 host.
6. Host-side replay harness: `tools/wv/host_replay.py`; canonical rewriter:
   `tools/wv/rw.py`; matrix experiment: `tools/wv/matrix.py`.

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

As of 2026-10-13 (verified with `git status --short`, on top of checkpoint
commit `ab74763`), these files are modified/untracked:

- `app/src/main/java/fr/groggy/racecontrol/tv/f1/F1Client.kt` (M) — WV vs
  HLS selection (always take the Widevine TV path, never plain HLS).
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/ChannelPlaybackFragment.kt` (M) —
  live/replay + custom-radio gating; secure-surface binding.
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/KeyStatusLoggingDrmSessionManagerProvider.kt` (M,
  further modified after the checkpoint) — logs RAW_KEY_REQUEST /
  RAW_LICENSE_RESPONSE base64 chunks + CDM key status; this is how the L1
  blobs are extractable from logcat.
- `app/src/main/java/fr/groggy/racecontrol/tv/ui/channel/playback/WidevineIdentityRewriter.kt` (NEW,
  untracked) — optional attestation rewriter; OFF by default. All
  successful L1 runs used the pristine path with it disabled (any
  app_info/device-block rewrite → 403, see 2026-10-12 matrix).
- `docs/uhd-hdr-green-video-handoff.md` (M) — this doc.
- `tools/wv/` (NEW, untracked, 33 files) — Widevine tooling + artifacts:
  `decode2.py` (bounds-checked protobuf dumper), `diff_l1_l3.py`,
  `hexdump.py`, `rw.py` (canonical rewriter), `matrix.py` /
  `matrix_out.txt` (403 matrix), `host_replay.py`, `splicer.py` (stale
  hardcoded F1_KEY_ID — read the kid live from the DASH manifest),
  `find_session.py` (F1TV catalog; use meetingKey, not numeric id),
  extraction scripts, captured request/response blobs (`key_request_*`,
  `license_response_*` — incl. the 10:20 L1 grant and the 12:18
  live-dry-run grant, the 860B L3 baseline, 11 matrix responses),
  `license_post_headers_20261013.txt`, and logcat captures
  (`logcat_emu_20261012.txt`, `logcat_live_dryrun_20261013.txt`).
- `.qwen/` (NEW, untracked) — agent config; not part of the app.

Already committed to checkpoint `ab74763` (for reference): the secure
SurfaceView / ClearVr-like renderer stack (`ClearVrLikeVideoRenderer.kt`,
`CryptoAsyncMediaCodecAdapter.kt`, `OfficialLikeHdrPlaybackFragment.kt`),
`ChannelPlaybackActivity.kt`, `HdrToneMappingRenderersFactory.kt`,
`MediaSourceItemFactory.kt`, `DeviceInfo.kt`, `styles.xml`,
`AndroidManifest.xml`, dynamic-hvcC/DASH-fixing sources, and the first
version of `KeyStatusLoggingDrmSessionManagerProvider.kt`.

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

## 2026-08-22 (later) — Root cause located in the decoder, not the renderer

A fresh pass over the existing artifacts (no new device runs) found a per-frame decoder
error that had been in the logs all along, plus three load-bearing claims in the sections
above that do not survive checking. The picture changes substantially.

### The decoder fails on 100% of frames

`diagnostics/2026-08-investigation/logcat_l3.txt` — the forced-Widevine-L3 run, the one
case where decoding happens on the **non-secure** `c2.mtk.hevc.decoder` and therefore
still logs to the normal-world VPU daemon:

```
08-22 10:37:40.173  779  794 I VPUD: [LAT][Err] Decoding Error 0xc40e0220 at LCU (x,y)=(119/119, 67/67) at frame 0 at POC 0
08-22 10:37:40.181  779  794 I VPUD: [LAT][Err] Decoding Error 0xc4160020 at LCU (x,y)=(119/119, 67/67) at frame 1 at POC 3
...
```

- 361 errors between `10:37:40.173` and `10:37:47.433` — **50 per second on a 50 fps
  stream, i.e. every single frame, for the whole playback attempt.**
- Zero such errors in the synthetic-clip runs (`logcat_hvc1_test.txt`,
  `logcat_hev1_test.txt`) that play correctly.
- `[LAT]` is MediaTek's LAT/CORE split HEVC pipeline; LAT is the **bitstream parsing**
  stage. Parsing is what fails, not reconstruction or output.
- The reported LCU position `(119/119, 67/67)` is the last CTU of the picture:
  3840/32 = 120 columns, 2176/32 = 68 rows, with `CtbSizeY = 32` from the stream's own SPS.
  **The VPU therefore parsed the SPS correctly** — it knows the coded size is 3840x2176
  and the CTB size is 32. The failure is in the slice payload, not the parameter sets.

This explains the green screen completely and mundanely: the decoder produces no picture,
the output buffer stays zero-filled, and zero YUV in limited-range BT.2020/HLG converts to
roughly RGB(0,26,0) — exactly the `screencap` result recorded above. Every renderer,
surface, dataspace, EGL, Z-order, colorMode and HDCP experiment in this document was
chasing a symptom.

Secure runs (`logcat_probe2.txt`, and the official app's log) show no `[LAT][Err]` lines
because secure decoding happens inside the TEE and its per-frame errors never reach
logcat. So this is **not** evidence that the official app decodes cleanly — only that we
cannot see inside it. It is evidence about our own stream.

### Correction 1 — the "no counter-example in F1's ladder" claim is wrong

The earlier 2026-08-22 section says F1's only HEVC is 10-bit HDR and its only SDR is AVC,
so "cbcs + HEVC" and "cbcs + 10-bit" cannot be separated locally. That is not true.
`diagnostics/current_week_hdr/index.mpd` — and the live manifest, confirmed in
`logcat_l3.txt`'s EventLogger dump — carries a **second video AdaptationSet of 8-bit
Main-profile HEVC**:

```
AdaptationSet id="1168817270"  ContentProtection value="cbcs"  default_KID=b0ff9352-2db4-3574-ac66-610c2ed2a313
  _SDR-HD_HEVC_1..5   hev1.1.6.*         480x270 .. 1920x1080
  _SDR-UHD_HEVC_1     hev1.1.6.L150.B0   2880x1620
```

Same `cbcs` scheme, **same `default_KID`**, same packager, same segment template, same
Widevine licence and DRM session as the HDR set — the only difference is Main vs Main10.
EventLogger reports them `supported=YES`. They have **never been played**: across every
saved log, the only video formats ever handed to a decoder are `_HDR-UHD_HEVC_2` and
`_SDR-HD_AVC_5`.

### Correction 2 — "encrypted cbcs HEVC decodes to blank frames" is not established

The clear-vs-encrypted comparison is confounded. `diagnostics/hlg_test_4k.mp4` (plays
perfectly) and F1's `_HDR-UHD_HEVC_2` (green) differ in far more than encryption:

| | synthetic clip (works) | F1 `_HDR-UHD_HEVC_2` (green) |
|---|---|---|
| `tiles_enabled` | 0 | **1** (2 cols x 1 row live; 4x4 in the older working stream) |
| `entropy_coding_sync` (WPP) | 1 | 0 |
| slices per picture | 1 | **8** (16 in the older stream) |
| conformance window | none, coded 2160 | coded **2176**, cropped to 2160 |
| `sample_adaptive_offset_enabled` | 0 | 1 |
| `sps_temporal_mvp_enabled` | 1 | 0 |
| `deblocking_filter_control_present` | 0 | 1 |
| `vui_hrd_parameters_present` | 0 | 1 |
| `log2_min_luma_coding_block_size` | 4 | 3 |
| AUD NAL / duplicated in-band VPS+SPS+PPS | absent | present |
| encryption | none | cbcs 1:9, constant IV |

Any of these could be what the LAT parser chokes on. The experiment as run does not
isolate encryption.

(The comment in `OfficialLikeHdrPlaybackFragment.kt` claiming the software-decoder run
"showed the SDR/BT.709 tracks in F1's HDR manifest decode fine on the same MediaTek
decoder" also overstates it — that run decoded `_SDR-HD_AVC_5`, H.264 on
`c2.mtk.avc.decoder.secure`, a different decoder entirely.)

### Correction 3 — the "official plays the same representation" premise is unverified

`diagnostics/2026-08-investigation/logs_official_app_playback_2026-08-21.txt` shows the
official app configuring one `c2.mtk.hevc.decoder.secure` at **960x544** with SDR defaults
and no colour aspects, then adapting in place: `configUpdate(1920 x 1088)` (crop 960x540,
HLG) at t+0.2 s, then `configUpdate(3840 x 2160)` six seconds later. It never sets
`KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT` (CCodec defaults `raw.max-size` to the configure size,
960x544), never sets `KEY_OPERATING_RATE`, and never pushes colour aspects into the
decoder (`DefaultColorAspectsSetter ... r 0 p 0 t 0 m 0`).

Our app configures directly at 3840x2160 with `max-width/height = 3840x2160`,
`max-input-size = 6220800` (official: MediaTek's own 3145728), `algo.rate.value = 50`
(which triggers `Update frc property from 0x0 to 0x2`), and forced colour aspects
`r 2 p 6 t 7 m 5`.

Three of these are already dead ends: the **working** synthetic clip has identical
operating-rate, FRC and colour-aspect values. The `max-width`/`max-height`/`max-input-size`
delta has never been tested on its own — note that the earlier "adaptive start at 480x270"
experiment still had Media3 setting `max-width/height` to 3840x2160, because Media3 derives
them from the whole track group, not from the starting format.

Separately: the official TV app requests `player=player_tm` (Tiledmedia). We request
`player=player_bm` and get `tmePresent=false`. Whether `player_tm` is served the *same*
packaging has never been checked. If it is not, the "identical CCodecConfig, identical
stream, works vs green" contradiction dissolves entirely.

### What is now ruled out, with evidence

- Renderer / surface / Leanback / EGL / dataspace / Z-order / window colorMode / tunneling
  / HDCP / fixed size — the decoder emits nothing, so none of this can matter.
- Protected EGL, at Java and NDK level — extension absent, `EGL_BAD_ATTRIBUTE`.
- Media3 `VideoGraph`/GL path — secure decoder refuses a non-secure texture (`NO_MEMORY`).
- Parameter sets / `hvcC` / `colr` injection — the failing L3 run used freshly extracted
  dynamic parameter sets (143 bytes) and the VPU demonstrably parsed the SPS correctly.
- `operating-rate` / FRC / forced colour aspects — present and identical in the run that
  works.
- Unencrypted HDR stream types (`HDR_UHD_DASH`, `HDR_UHD_CMAF`) — HTTP 403 from AWS
  MediaPackage, server-side, not fixable client-side.
- Google's software HEVC decoder as a fallback — `c2.android.hevc.decoder` reports
  `NoSupport [codec.profileLevel, hev1.2.4.*]` at every level, i.e. no Main10 at all.

### Plan

Ordered so that each step is cheap and its outcome redirects the next. Steps 1-3 need a
live or current-week session on the device; step 4 needs nothing.

**Step 1 — play `_SDR-UHD_HEVC_1` (the controlled counter-example).**
Add a diagnostic `TrackSelectionOverride` in `onTracksChanged` selecting the track whose
`Format.id` matches a constant, and run it with `FORCE_WIDEVINE_L3_DIAGNOSTIC = true` so
the VPU error stream is visible and the result is objective rather than visual.
- Decodes cleanly → cbcs decryption, tiles, multi-slice, F1's packaging and the whole
  crypto path are all proven good, and the failing variable is **Main10 / 10-bit**. It also
  hands us a shippable 2880x1620 encrypted-HEVC fallback, far better than today's 1080p AVC.
- Fails the same way → the fault is in the encrypted-HEVC feeding path; go to step 3.

**Step 2 — walk the HDR ladder with VPU visibility.** Under the same L3 setup, force
`_HDR-HD_HEVC_5` (1080p Main10), `_HDR-HD_HEVC_1` (480x270 Main10) and
`_HDR-UHD_HEVC_2_iframe` (all-intra Main10) in turn and count `[LAT][Err]` lines. This
replaces the earlier "green at every resolution" observation with a measurement, and the
i-frame rung separates a parsing failure from an inter-prediction/DPB failure.

**Step 3 — build a clear clip that matches F1's bitstream shape.** Re-encode
`hlg_test_4k.mp4` with the delta features from the table above and play it via
`LOCAL_TEST_FILE_DIAGNOSTIC`, no DRM involved. x265 covers `--slices 8`, `--sao`, `--aud`,
`--repeat-headers`, `--hrd`, `--ctu 32` and the conformance window; tiles need
libkvazaar (`--tiles 2x1`). If a clear clip in F1's shape also errors, encryption is
irrelevant and the remaining bisection is pure encoder flags.

**Step 4 — ask the PLAY API for `player=player_tm`** with the Google TV Streamer device
profile and log the whole response. One HTTP call, no player changes. Establishes whether
the official app is even served the same bytes.

**Step 5 — the untested config deltas**, only if steps 1-3 point back at how Media3 feeds
the codec: drop `KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT` (or pin them to the configure size),
force `max-input-size` to MediaTek's 3145728, and disable
`CONFIGURE_FLAG_USE_CRYPTO_ASYNC` on API 34+ in isolation. Each is a few lines in
`HdrToneMappingRenderersFactory`'s renderer subclass.

**Step 6 — cbcs + ClearKey on the synthetic clip** (shaka-packager, free, no licence).
Only worth doing if step 1 fails, and only to produce a clean upstream bug report.

Product fallbacks that follow from the above, in order of preference: 2880x1620 HDR
(`_HDR-UHD_HEVC_1`) if any Main10 rung decodes; otherwise 2880x1620 SDR HEVC
(`_SDR-UHD_HEVC_1`); otherwise today's 1080p AVC. The `ToneMappedHdr` path black-screening
is a separate defect and should be fixed on its own merits as the honest fallback.

### Reusable analysis scripts

The MP4/HEVC parsing used for this section is worth keeping — it turns any saved init or
media segment into `tenc`/`senc`/subsample/SPS/PPS detail without a device:

- box + crypto dump: walks `moov`/`moof`, prints `tenc` (scheme, pattern, KID, constant
  IV), `frma`, `hvcC` (including each VPS/SPS/PPS NAL), `colr`, `saiz`/`senc` subsample
  counts.
- HEVC parameter-set parser: full SPS (conformance window, bit depth, CTB size, DPB, VUI
  colour description) and PPS (`tiles_enabled`, tile grid, WPP, deblocking,
  `num_extra_slice_header_bits`).
- sample walker: splits a `trun` sample into length-prefixed NALs and lines the NAL layout
  up against the `senc` clear/encrypted subsample boundaries.

## 2026-08-22 (device session) — bit depth, resolution, and encryption all cleared; multi-slice cleared

Live on-device follow-up to the section above, run the same evening. Added a small
diagnostic (`OfficialLikeHdrPlaybackFragment.applyForcedTrackOverrideIfNeeded`, gated by
`DIAGNOSTIC_FORCE_VIDEO_FORMAT_ID`) that forces `DefaultTrackSelector` to select an exact
`Format.id` instead of the normal highest-bitrate ABR pick, so specific representations in
F1's own ladder could be played on demand. Also exported `ChannelPlaybackActivity` in the
debug manifest only (`app/src/debug/AndroidManifest.xml`), so a specific
channel/content/season could be launched directly via `adb shell am start` without
navigating the TV UI — this is now a reusable tool for future sessions, not just tonight's.

All three tests below ran with `FORCE_WIDEVINE_L3_DIAGNOSTIC = true`, i.e. Widevine forced
to L3 software decryption: the CDM decrypts in software and hands MediaCodec a plain,
non-crypto access unit. **The decoder was therefore fed genuinely decrypted F1 video in
every test below** — this directly settles whether encryption/DRM/CryptoInfo plays any
role, not just an inference from it.

### Test 1: `_SDR-UHD_HEVC_1` (2880x1620, hev1.1.6 Main, 8-bit) — fails identically

Same `cbcs` scheme, same `default_KID` as the HDR set (see the AdaptationSet table above),
now actually played instead of merely reasoned about:

```
videoInputFormat id=_SDR-UHD_HEVC_1 ... res=2880x1620 ...
onRenderedFirstFrame ...
[LAT][Err] Decoding Error ... at LCU (x,y)=(89/89, 50/50) at frame 0 at POC 0
```
518 errors over ~10.6s (2880/32=90 cols, 1620/32≈51 rows -- LCU position matches this
resolution exactly, confirming these errors belong to this track, not a stale session).

**Bit depth (Main vs Main10) is ruled out.** The only earlier basis for suspecting 10-bit
specifically was that HDR failed and AVC (a different codec entirely) worked; this is a
direct counter-example within HEVC itself.

### Test 2: `_SDR-HD_HEVC_5` (1920x1080, hev1.1.6 Main, 8-bit) — fails identically

```
videoInputFormat id=_SDR-HD_HEVC_5 ... res=1920x1080 ...
onRenderedFirstFrame ...
```
244 errors, LCU (59/59, 33/33) -- matches 1920/32=60, 1080/32≈34. **Resolution and
"UHD-tier encoding ladder" are ruled out too.** Every F1 HEVC representation tested so far
fails, regardless of size.

### Combined with Test 1 and the original L3 HDR run: every F1 HEVC representation tested fails; only F1 AVC works

Three different resolutions (3840x2160, 2880x1620, 1920x1080), two bit depths (Main10,
Main), all cbcs-encrypted-then-L3-decrypted -- 100% failure rate, always at the LAT parsing
stage, always at the exact last-LCU position for that resolution. The only F1 video that
has ever decoded cleanly on this device is `_SDR-HD_AVC_5` (H.264). This makes "MediaTek's
LAT parser cannot handle *something structural* that is common to F1's entire HEVC ladder"
a much stronger claim than the original doc's narrower "10-bit HEVC" framing.

### Test 3: synthetic clip, F1-shaped multi-slice, no tiles, no encryption — plays cleanly

Built with `ffmpeg`/`libx265` (`diagnostics/f1shape_test_4k.mp4`,
`diagnostics/tools/hevc_params.py` used to verify the actual encoded shape, since x265
silently drops unsupported params rather than erroring):

```
tiles_enabled = 0        (requested tiles=2x1; this libx265 build requires wpp=1
                           for multi-slice and silently drops tiles when wpp=1 -- see
                           "still open" below)
entropy_coding_sync_enabled_WPP = 1
slices = 8                (matches F1's slice count)
log2_min_luma_coding_block_size = 3  (8x8 min CU, matches F1; the ORIGINAL working
                           clip from the section above used 16x16 and also worked, so
                           this was never a live suspect -- included for completeness)
sample_adaptive_offset_enabled = 1   (matches F1)
sps_temporal_mvp_enabled = 1         (does NOT match F1's 0; not testable in isolation
                           from tiles/wpp with this encoder build)
```

Played via `LOCAL_TEST_FILE_DIAGNOSTIC` (no DRM at all, pushed to
`/sdcard/Android/data/com.st14n.f1.debug/files/hdrtest.mp4`):

```
videoInputFormat id=1 ... codecs=hvc1.2.4.L153.90 res=3840x2160 ...
onRenderedFirstFrame ...
```
**Zero `[LAT][Err]` lines.** 8 slices per picture, with WPP, at 4K, decodes perfectly.

**Multi-slice itself (at least combined with WPP enabled) is ruled out** as the sole cause.

### What's left

Only two candidates from the original difference table survive, and they are entangled
with each other in the available tooling:

- **`tiles_enabled`** -- never actually tested. Requested via `x265-params tiles=2x1` and
  ffmpeg accepted the string without complaint, but the x265 log's `tools:` line never
  listed tiles and `hevc_params.py` confirmed `tiles_enabled = 0` in the output. A second
  attempt with `wpp=0` (to match F1 exactly) hit `Unknown option: tiles` -- this
  Windows/gyan.dev ffmpeg build's bundled libx265 appears not to expose tile support at
  all (mainline x265 does; this may be a version or build-flag issue). No standalone
  `x265.exe` is on this machine either.
- **`entropy_coding_sync_enabled_WPP = 0`** (F1 has it off; every clip tested here,
  including the original working one, had it on) -- confounded with tiles above, since
  this x265 build ties multi-slice to WPP and drops tiles when WPP is on.

Everything else in the original difference table (conformance window crop,
`sps_temporal_mvp_enabled`, `deblocking_filter_control_present`,
`vui_hrd_parameters_present`, AUD/duplicated in-band parameter sets) remains untested in
isolation, but is lower-priority than tiles: none of them are historically associated with
hardware HEVC decoder bugs the way tiles are, and HRD/AUD are pure signaling with no
bearing on entropy-coded slice data.

### Updated plan

Steps 1-2 from the original plan (`_SDR-UHD_HEVC_1`, ladder walk) are now **done** --
folded into this section. Remaining, in order:

1. **Get a tile-capable HEVC encoder.** Either a different libx265 build/version (a Linux
   build or one compiled with tile support explicitly enabled), or ffmpeg's `libkvazaar`
   encoder, or Bento4/GPAC's `mp4hevc` tools, or a properly configured `x265.exe` CLI
   (which exposes `--tiles` directly rather than through `-x265-params`). Encode the same
   3840x2160 10-bit HLG content with `tiles=2x1, wpp=0`, matching F1's PPS as closely as
   possible, and play it via the now-proven `LOCAL_TEST_FILE_DIAGNOSTIC` path -- no DRM,
   no device-side complexity, same test harness already built and working tonight.
   - Decodes cleanly -> tiles are also cleared, and the remaining candidates are
     `wpp=0` itself, the conformance window, or something not yet in the difference table
     at all (e.g. worth re-diffing the two bitstreams' slice header
     `num_extra_slice_header_bits` / `dependent_slice_segments_enabled` more carefully, or
     comparing NAL-level short-term reference picture set encoding).
   - Fails -> tiles are very likely the actual root cause. At that point there is no
     client-side fix: tiles are baked into the entropy-coded slice payload, not
     patchable pre-decode the way `hvcC`/SPS/PPS bytes are, and re-encoding would require
     decrypting the content key, which Widevine L1 never exposes to the app. This would be
     a platform decoder defect worth reporting to MediaTek/Google, and the practical
     product answer becomes "this device's hardware cannot play this stream," full stop --
     not a bug in this codebase.

2. **`player=player_tm` PLAY API probe** -- unchanged from the original plan, still
   untried, still one HTTP call.

3. **cbcs+ClearKey on the tile-capable clip once step 1 exists** -- only if step 1's clear
   clip reproduces the failure and a DRM-specific angle still seems worth ruling out
   on top of it (unlikely to add information beyond what L3 already showed tonight, but
   cheap once the clip exists).

### Reusable infrastructure from tonight, kept in the tree

- `OfficialLikeHdrPlaybackFragment.DIAGNOSTIC_FORCE_VIDEO_FORMAT_ID` / a new
  `onTracksChanged` override -- forces an exact DASH `Format.id` instead of ABR selection.
  Off by default (`null`). Match by suffix (`formatId.endsWith(":$targetId")`), since
  Media3 prefixes DASH representation ids with the period index.
- `app/src/debug/AndroidManifest.xml` now also exports `ChannelPlaybackActivity` (debug
  builds only). Launch any known channel/content directly:
  ```
  adb shell am start -n com.st14n.f1.debug/fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity \
    --es "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SESSION_ID" "test" \
    --es "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CHANNEL_ID" "1028" \
    --es "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CONTENT_ID" "1000011042" \
    --ez "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_IS_LIVE_SESSION" false \
    --ei "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SEASON_YEAR" 2026
  ```
- `diagnostics/f1shape_test_4k.mp4` -- clear (no DRM), 3840x2160 10-bit HLG BT.2020, 8
  slices, WPP on, no tiles, decodes cleanly. Useful as a known-good baseline for the next
  tile-capable-encoder attempt.

## 2026-08-22 (device session, part 2) — isolated to `cbcs` pattern encryption on MediaTek's HEVC decoder specifically; matches an open, unresolved upstream bug

Continuation of the same evening's session, prompted by one question: since *some* app on
this exact hardware plays this content, the decoder silicon must be capable -- so what is
different about how we feed it? (Correction to the prompt that raised this: our own
decompiled evidence says the official TV app's actual UHD path uses **Tiledmedia/ClearVR**,
not Bitmovin -- Bitmovin is F1TV's mobile/web player. Doesn't change the logic: whichever
SDK, it's still standard Android MediaCodec underneath, so the hardware is not fundamentally
incapable.)

### Codec adapter (sync vs async, timestamp shifting): ruled out

`OfficialLikeHdrPlaybackFragment` unconditionally wraps every codec adapter with
`SynchronousMediaCodecAdapter.Factory()` (via `shouldForceSynchronousCodecQueueing()`,
which matches this exact device: "kirkwood"/"Google TV Streamer") plus
`TimestampShiftingMediaCodecAdapterWrapper`. The one thing that had ever decoded
successfully (`_SDR-HD_AVC_5`) went through a *different* fragment/renderer-factory
configuration that never applies either wrapper -- so this had never actually been
excluded as a cause for the HEVC failures.

Tested directly: forced `_SDR-HD_HEVC_5` (1080p) through `enableCryptoDiagnostics=true`,
which uses Media3's stock `MediaCodecAdapter.Factory.DEFAULT` (plain async queueing, no
synchronous forcing, no timestamp shifting) wrapped only with a CryptoInfo logger. Still
161 `[LAT][Err]` lines, same LCU position (59/59, 33/33) as every other run of this track.
**Our custom codec-adapter wrapping is not the cause.** The `CryptoInfo` logged for this
track: `numSubSamples=4` (not 8 -- HD-tier F1 HEVC uses 4 slices, UHD-tier uses 8),
`pattern(encryptedBlocks=1 clearBlocks=9)`. Fails even at only 4 subsamples, so "subsample
count overflow" is not the shape of the bug either.

### Tiles isolated and cleared

The previous session's ffmpeg/libx265 build had no tile support at all (`tiles=2x1` was
silently accepted by the CLI but never appeared in x265's own `tools:` log line, confirmed
via `hevc_params.py` showing `tiles_enabled=0` in the actual output). Downloaded a static
`ffmpeg-n8.1` build (BtbN/FFmpeg-Builds, GPL, includes `libkvazaar`) specifically to get
real tile support -- x265 in that build *also* silently ignores `tiles=`, but `libkvazaar`
accepted it immediately (`Disabling WPP because tiles were enabled`, `Disabling TMVP
because tiles are used`).

Encoded `diagnostics/f1shape_tiles_4k.mp4`: 3840x2160, confirmed via `hevc_params.py` --
`tiles_enabled=1`, `num_tile_columns=2`, `num_tile_rows=1`, `entropy_coding_sync_enabled_WPP=0`,
`sps_temporal_mvp_enabled=0`, `sample_adaptive_offset_enabled=1`. This is F1's exact PPS
tile/WPP/TMVP shape. Played clear (no DRM) via `LOCAL_TEST_FILE_DIAGNOSTIC`:
**zero `[LAT][Err]` lines.** Tiles, at F1's exact configuration, decode perfectly on this
MediaTek decoder.

Combined with the previous session's multi-slice+WPP=1 test (also zero errors): every
bitstream-shape candidate from the original difference table -- tiles, WPP, multi-slice,
SAO, temporal MVP, min-CU size -- is now cleared, tested individually and matching F1's
exact values, all in isolation from encryption.

### The decisive test: `cenc` vs `cbcs`, same clip, same MediaTek decoder

This is the one variable never isolated: every real F1 test that failed carried `cbcs`
pattern encryption; every clean synthetic test had zero encryption. Downloaded
`shaka-packager` (v3.9.3, official static Windows binary) to close this gap with a
same-content, single-variable comparison.

Packaged `f1shape_tiles_4k.mp4` (the tile-matching clip above) with raw-key encryption,
`--protection_scheme cbcs`, pattern 1:9 to match F1 exactly (confirmed via `mp4_crypto_dump.py`:
`cryptBlock=1 skipBlock=9`, byte-identical pattern to F1's own `tenc`). Tried to play it via
Media3's `DefaultDrmSessionManager` + `C.CLEARKEY_UUID` + a local (offline, no network)
ClearKey JSON response:

```
Caused by: android.media.MediaCodec$CryptoException: selected encryption mode is not
supported by the ClearKey DRM Plugin: ERROR_DRM_CANNOT_HANDLE
```

Android's own AOSP ClearKey CDM on this device rejects `cbcs` entirely, at the DRM-plugin
layer, before any video decoding is attempted -- this is a genuine, separate, and
well-known ClearKey limitation (it only ever fully implemented `cenc`), unrelated to
MediaTek. It means ClearKey can never directly test `cbcs` on this device. So instead:
repackaged the *identical* clip and pattern layout with `--protection_scheme cenc`
(confirmed via `mp4_crypto_dump.py`: scheme changes, everything else -- KID, key,
tiles/WPP/slice structure -- stays the same) and played that.

**Zero `[LAT][Err]` lines. `onRenderedFirstFrame` fires immediately, clean.** Encrypted,
tiled, multi-subsample (same 8-ish-subsample-per-picture layout), 4K HEVC decodes
perfectly on this exact MediaTek decoder component -- as long as the scheme is `cenc`
rather than `cbcs`.

### Correction: F1 does not offer a `cenc` alternative -- their AVC content is also `cbcs`

Before concluding "just ask F1 for `cenc`," checked whether F1's own AVC representations
(the ones that decode successfully) use `cenc`. `EventLogger`'s `drm=[widevine,cenc]` label
turned out to be a **generic Media3 placeholder** -- it printed identically for the
already-confirmed-`cbcs` HEVC tracks, so it proves nothing about the real scheme. Fetched
the actual `SDR-HD-DASH-WV-SINGLE` manifest live (`diagnostics/2026-08-investigation/sdr_avc_index.mpd`)
and read its real `<ContentProtection>` box:

```xml
<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cbcs"
                    cenc:default_KID="975c870f-8637-3a70-80bf-d8e1a50e08f2"/>
```

**F1's AVC content is also `cbcs`-encrypted**, and it decodes correctly on
`c2.mtk.avc.decoder.secure`. This sharpens the finding rather than undermining it: it is
not "this device's DRM stack can't do `cbcs`" (MediaTek's own AVC decoder proves it can) --
it is specifically **MediaTek's HEVC decoder component (`c2.mtk.hevc.decoder` /
`.secure` -- both variants, since the non-secure one was used throughout tonight's L3
testing and failed identically) mishandling `cbcs` pattern-encrypted HEVC**, while its AVC
sibling component handles the identical scheme without issue. There is no alternate
F1 manifest/profile to request instead -- `cbcs` is universal across their whole catalogue.

### This matches a known, currently open, unresolved upstream bug

Searched the `androidx/media` (Media3) and `google/ExoPlayer` issue trackers for this
signature. Three relevant hits:

- **androidx/media #2711** -- "H.265/HEVC Playback Fails on MediaTek Dimensity 700/900/1080
  after Android 15 Upgrade." Widevine L1, MediaTek, HEVC fails (black/frozen), while
  **"H.264/AVC content works fine with hardware decoding" on the same devices** -- the
  identical HEVC-broken/AVC-fine/MediaTek/Widevine fingerprint as ours. CBCS isn't named
  explicitly in the report, but Widevine has defaulted to CBCS industry-wide for HDR/4K
  content for years, so it plausibly is the same root cause under a different symptom
  (black/frozen rather than green, and "no error entries in logcat" -- but that report
  never looked for VPU `[LAT][Err]` lines specifically, which don't appear in secure-path
  logcat anyway). **Still open, unresolved, as of this search.**
- **google/ExoPlayer #10890** -- "widevine DRM cbcs content decode error," MediaTek-family
  devices (Vivo/OnePlus/Redmi), explicitly CBCS-specific -- but AVC (`OMX.MTK.VIDEO.DECODER.AVC.secure`),
  an older OMX-era decoder, and a crash (`IllegalStateException`) rather than silent
  corruption. Confirms CBCS+MediaTek bugs are a recurring, cross-component pattern on this
  vendor's stack, not this exact bug.
- **androidx/media #2765** -- MediaTek HEVC black/frozen on XiaoMi TV, but explicitly
  *unencrypted* content -- a different bug, useful only as further evidence that
  `c2.mtk.hevc.decoder` has an independent history of platform-specific defects.

No workaround is documented on any of these. #2711 is the closest match and is still open.

### Device firmware

`ro.build.version.security_patch=2026-04-01`, `ro.build.date=Tue Apr 14 20:39:42 UTC 2026`
-- about 4.5 months old as of this session (2026-08-22). Did not attempt to trigger an OTA
check via adb (that changes real system state on the user's device and needs their
decision, not an automated one). **Action for the user**: check Settings > System > About >
System update on the device itself. A vendor decoder defect exactly matching this shape is
plausibly fixed in a later MediaTek firmware/driver push, and this is the cheapest possible
next step -- it costs nothing to check and requires no code change.

### Where this leaves the investigation

Root cause is now as precise as it can get without MediaTek's own source or a rooted
device: **`c2.mtk.hevc.decoder`[`.secure`] cannot correctly decode `cbcs` pattern-encrypted
HEVC on this SoC/firmware, independent of resolution, bit depth, tiles, slice count, WPP,
temporal MVP, or SAO** -- all tested and cleared, individually, matching F1's exact values.
`cenc`-encrypted HEVC in the identical shape decodes perfectly. F1's own AVC content uses
the identical `cbcs` scheme and decodes correctly on the sibling AVC decoder component. This
is a narrow, single-component vendor defect, and it matches an open upstream report
(androidx/media #2711) with no known workaround.

**No client-side fix is possible for the HDR/UHD HEVC tier as currently packaged.** `cbcs`
is universal across F1's catalogue (confirmed for both their HEVC and AVC ladders), so
there is no alternate manifest/profile/platform combination to request. The pattern
parameters (`encryptedBlocks`/`clearBlocks`) cannot be altered without also altering
decryption correctness, since they are a property of the actual encrypted bytes on the
wire, not just a hint to the decoder. Re-encoding or re-packaging client-side would require
the plaintext or the content key, neither of which Widevine L1 ever exposes to the app.

### What is left to try, in order of cost

1. **Check for a Google TV Streamer system update** (free, five minutes, the user's own
   device). If MediaTek/Google have patched this in the ~4.5 months since this firmware was
   built, this alone fixes it with zero app changes.
2. **File or contribute to androidx/media #2711.** This investigation's data (exact VPU
   `[LAT][Err]` codes and LCU positions, a clean `cenc`-vs-`cbcs` same-content isolation, a
   confirmed AVC-works/HEVC-fails split on identical encryption) is more granular than
   anything currently posted on that issue and could accelerate an actual fix, benefiting
   every app hitting this on MediaTek hardware, not just this one.
3. **Re-verify against `player=player_tm`** (the original plan's step 4, still untried) --
   if F1's Tiledmedia-targeted profile happens to request a different security level or
   packaging (unlikely given `cbcs` is otherwise universal, but one HTTP call to rule out).
4. **Ship the honest fallback**: since this is now confirmed device/firmware-specific
   rather than a bug in this codebase, the product-level answer is to detect this
   failure mode reliably (the `[LAT][Err]` signal itself isn't visible on the secure path,
   but the existing black/green presentation-failure watchdogs already detect the visual
   symptom) and fall back to the best `cenc`-or-AVC-compatible tier automatically, rather
   than to keep attempting HEVC decode on this hardware. This does not mean settling for
   1080p AVC as "the fix" -- it means the fallback should trigger reliably and the user
   should not be left on a green screen while it decides.

Nothing about tonight's testing was destructive: all diagnostic flags
(`FORCE_WIDEVINE_L3_DIAGNOSTIC`, `LOCAL_TEST_FILE_DIAGNOSTIC`,
`LOCAL_TEST_FILE_CLEARKEY_DIAGNOSTIC`, `DIAGNOSTIC_FORCE_VIDEO_FORMAT_ID`,
`enableCryptoDiagnostics`) are reverted to their original `false`/`null` defaults, and the
device has the clean rebuild reinstalled. The new `LOCAL_TEST_FILE_CLEARKEY_DIAGNOSTIC`
path and the `onTracksChanged`-based forced-track-override are left in the tree, off by
default, as reusable tools for the next session.

### Tools acquired this session (not part of the repo, kept outside it)

- `C:\tmp\ffmpeg-tiles\ffmpeg-n8.1-latest-win64-gpl-8.1\bin\ffmpeg.exe` -- static build with
  full `libx265`+`libkvazaar` (tiles work via kvazaar; x265 in this build silently drops
  `tiles=` regardless of syntax, in both the original and this newer build).
- `C:\tmp\shaka-packager\packager.exe` -- v3.9.3, for raw-key `cenc`/`cbcs` test packaging.

## 2026-08-22 (device session, part 3) — software HEVC decode also corrupts; the bug is in decryption, not the decoder chip; no AVC tier above 1080p exists

Two follow-up questions from the same evening, both answered with fresh device evidence.

### "Can we route around MediaTek's HEVC decoder chip entirely, e.g. via software decode?"

Closed a gap noted earlier: nobody had checked whether Google's own software HEVC decoder
(`c2.android.hevc.decoder`) supports F1's *Main*-profile (8-bit) HEVC -- only Main10
support had ever been probed (`NoSupport` at every level). Tested
`FORCE_WIDEVINE_L3_DIAGNOSTIC=true` together with `SOFTWARE_DECODER_DIAGNOSTIC=true`
(never tried together before -- the existing code comment for `SOFTWARE_DECODER_DIAGNOSTIC`
predates this combination and only found `requiresSecureDecoder=true` because L3 wasn't
also forced in that earlier run) against `_SDR-HD_HEVC_5` (1080p, Main, 8-bit).

Result: `c2.android.hevc.decoder` (Google's software decoder) *did* get selected and
initialized this time -- so Main-profile HEVC is supported in software on this device.
It then failed immediately:

```
Caused by: androidx.media3.exoplayer.video.MediaCodecVideoDecoderException: Decoder failed: c2.android.hevc.decoder
Caused by: android.media.MediaCodec$CodecException: Error 0xe
```

`0xe` = 14 decimal = `C2_CORRUPTED` in AOSP's Codec2 status enum. **Google's own reference
software HEVC decoder -- no MediaTek code involved at all -- received corrupted bytes and
refused to proceed.**

This is the single most important result of the whole investigation, because it relocates
the bug. If a bitstream-parsing defect lived inside MediaTek's HEVC decode chip
specifically, a completely different decoder implementation (Google's, running on the
CPU) would have no reason to also receive corrupted data. It didn't -- which means the
corruption happens **before** any decoder ever sees the bytes, at the decryption stage
itself. On this device, cbcs decryption for Widevine content is handled by a vendor
crypto plugin/OEMCrypto implementation that is shared infrastructure ahead of *whichever*
decoder ends up consuming the output -- hardware or software, secure or not. All three
decoder components tested tonight (`c2.mtk.hevc.decoder.secure`, `c2.mtk.hevc.decoder`,
`c2.android.hevc.decoder`) receive the same corrupted result for cbcs-encrypted HEVC; only
`c2.mtk.hevc.decoder[.secure]`'s failure mode is silent (LAT parse errors, zero-filled
output, green screen) rather than a hard `CodecException`.

**This closes off "software decode as a workaround" definitively** -- it was worth testing
precisely because it was the one remaining thing that could have bypassed MediaTek's
decoder, and it didn't, because the corruption isn't in the decoder.

It also sharpens the earlier conclusion. Not: "MediaTek's HEVC decoder mishandles cbcs."
Rather: "this device's cbcs *decryption* path, upstream of every available HEVC decoder,
corrupts HEVC-shaped content specifically (while the identical scheme applied to AVC
content decrypts correctly)." The AVC/HEVC split is therefore most likely rooted in
something about the two codecs' differing NAL/subsample shapes interacting with the
crypto plugin's subsample-boundary handling, not literally "HEVC decode logic." Either way,
it's below the application layer, below Media3, and below any decoder we can select --
there is nothing in app code to intercept, patch, or re-encode, because there is no point
in the whole pipeline (for genuine Widevine-protected content, at any security level) where
the app or Media3 ever holds either the correct plaintext or the corrupted intermediate
bytes. Decryption is opaque end-to-end by design; this is what "no client-side fix is
possible" concretely means.

### "Can we just force H.264 (AVC)?"

Checked F1's actual ladder across every manifest and platform combination captured this
session and previously: **F1 has no representation above `_SDR-HD_AVC_5`, 1920x1080,
`avc1.4D402A`, anywhere** -- not in any platform (`BIG_SCREEN_HLS`, `WEB_HLS`, and every
stream-type variant probed by `STREAM_VARIANT_PROBE` back in the original investigation).
There is no 4K/UHD AVC tier to force; AVC's ceiling is the exact same 1080p the app already
falls back to automatically today. AVC does decode correctly (confirmed again tonight,
`c2.mtk.avc.decoder.secure`, same `cbcs` scheme, onRenderedFirstFrame every time).

So "force AVC" cannot recover resolution -- it can only remove the several-second
HEVC-then-tone-mapped-HDR-then-error retry sequence the app currently goes through before
landing on the identical 1080p AVC fallback it already reaches today, replacing a visible
failure-and-recovery with an immediate clean start. **User's decision: leave the existing
retry behaviour as-is for now**, in case a future MediaTek/Google firmware update fixes the
underlying cbcs+HEVC decrypt bug and HEVC starts working again on this hardware without
requiring an app change.

### Summary of the full elimination, end to end

| Layer | Tested | Result |
|---|---|---|
| Renderer / surface / EGL / dataspace / Z-order / colorMode / tunneling / HDCP | Multiple experiments, prior sessions | Ruled out |
| Codec adapter (sync/async queueing, timestamp shifting) | Stock async adapter vs forced sync, same track | Ruled out |
| Bitstream shape: tiles, WPP, multi-slice, SAO, temporal MVP, min-CU size | Synthetic clips matching F1's exact PPS values, clear (no DRM) | Ruled out -- all decode cleanly |
| Bit depth (Main10 vs Main) | Real F1 `_SDR-UHD_HEVC_1`/`_SDR-HD_HEVC_5` (8-bit) fail identically to Main10 | Ruled out |
| Resolution / UHD-tier encoding | 1080p fails identically to 2160p/1620p | Ruled out |
| Encryption scheme: `cenc` vs `cbcs`, same clip | `cenc` decodes clean; `cbcs` (AOSP ClearKey can't even attempt it; real Widevine reaches the decoder and fails) | **`cbcs` implicated** |
| Decoder component: MediaTek secure vs non-secure vs Google software | All three fail identically on cbcs-encrypted HEVC (LAT errors or `C2_CORRUPTED`) | **Bug is upstream of all decoders, in decryption** |
| Same `cbcs` scheme on AVC | Decodes correctly, every time | Confirms it's HEVC-shaped-content-specific, not a device-wide `cbcs` failure |

Matches androidx/media issue **#2711** (open, unresolved): MediaTek + Widevine L1 + HEVC
fails while AVC on the same hardware works.

No further client-side avenue is known. Remaining options, unchanged from the prior
session: check for a system update on the device; file/contribute to #2711 with this
session's data (it is more precise than anything currently on that issue -- the `cenc`
vs `cbcs` isolation and the software-decoder cross-check in particular); or wait.

All diagnostic constants reverted to their `false`/`null` defaults again; device reinstalled
with the clean build.

## 2026-08-22 (device session, part 4) — `CONFIGURE_FLAG_USE_CRYPTO_ASYNC` tested; last framework-level knob closed

Final test of the evening, closing the one remaining variable identified in part 3:
whether `MediaCodec.CONFIGURE_FLAG_USE_CRYPTO_ASYNC` (API 34+) -- a platform/HAL crypto
dispatch mode that neither of Media3 1.10.1's own bundled adapters ever sets (confirmed
by decompiling `media3-exoplayer-1.10.1-runtime.jar` with jadx: `SynchronousMediaCodecAdapter`
never sets it; `AsynchronousMediaCodecAdapter.Factory`'s `flags = 0 | 4` is
`CONFIGURE_FLAG_USE_BLOCK_MODEL`, a different flag entirely, gated to API 36+, which this
device is not) -- changes anything.

Also resolves the "clone/fork Media3, submit a PR" question from earlier: Media3's own
adapters were decompiled and inspected directly. Neither contains the bug (both just
forward to `android.media.MediaCodec.configure()`/`queueSecureInputBuffer()`), and neither
exposes this flag as a configurable option -- so testing it required writing a new
`MediaCodecAdapter.Factory` from scratch (`CryptoAsyncMediaCodecAdapter.kt`), not modifying
Media3. This is a concrete, first-hand demonstration of the earlier claim that a Media3
fork has no leverage here: the library's own source contains nothing to patch for this bug.

### First attempt: synchronous adapter + the flag -- fails to even initialize

A first version mirrored Media3's `SynchronousMediaCodecAdapter` (poll-based
`dequeueInputBuffer(0L)`/`dequeueOutputBuffer(bufferInfo, 0L)`, no callback) plus
`CONFIGURE_FLAG_USE_CRYPTO_ASYNC`. Failed immediately:

```
Caused by: java.lang.IllegalStateException: Block model is only valid with callback set (async mode)
```

On this device/platform, requesting crypto-async apparently implies block-model buffers
internally, which require a genuine async `MediaCodec.Callback` -- it cannot be requested
from a polling-style adapter at all.

### Second attempt: proper async adapter (real `MediaCodec.Callback`, own `HandlerThread`)

Rewrote `CryptoAsyncMediaCodecAdapter` as a minimal but real async adapter -- a
`MediaCodec.Callback` registered before `configure()`, a dedicated `HandlerThread`,
synchronized index queues for input/output buffers -- functionally equivalent to Media3's
own `AsynchronousMediaCodecAdapter` (whose class and constructor are package-private and
whose `Factory` is `final`, so it cannot be reused or subclassed; this had to be an
independent implementation using only the public `MediaCodecAdapter` interface and public
`android.media.MediaCodec`/`MediaCodec.Callback` API).

Tested against `_SDR-HD_HEVC_5` (1080p) under `FORCE_WIDEVINE_L3_DIAGNOSTIC` for VPU
visibility, as in every prior test this session:

```
videoDecoderInitialized c2.mtk.hevc.decoder
[LAT][Err] Decoding Error 0xc40e0260 at LCU (x,y)=(59/59, 33/33) at frame 0 at POC 0
[LAT][Err] Decoding Error 0xc4160240 at LCU (x,y)=(59/59, 33/33) at frame 1 at POC 4
... (6 more, frames 2-7, same LCU position)
```

Identical corruption signature -- same LCU position matching this track's exact geometry,
starting at frame 0 -- to every other test this investigation has ever run on this track,
regardless of codec adapter, queueing mode, or now, crypto dispatch mode. The player then
crashed (`NullPointerException` in `MediaCodecVideoRenderer.onOutputFormatChanged`,
`Preconditions.checkNotNull`) a moment later -- a real gap in this minimal diagnostic
adapter's async output-format-change signalling (a corner of Media3's async contract this
throwaway implementation doesn't fully replicate), not a signal about the vendor's
behaviour. The 8 frames captured before the crash already fully reproduce the corruption,
so the crash doesn't undermine the result.

**`CONFIGURE_FLAG_USE_CRYPTO_ASYNC` makes no difference.** This was the last variable at
the framework/HAL crypto-dispatch boundary. Combined with the software-decoder cross-check
from part 3 (a completely different decoder implementation, still corrupted), every layer
between the app and the vendor's OEMCrypto blob has now been varied independently, and the
corruption is invariant across all of them. There is no remaining framework-level knob to
try.

### Files added, left in the tree (off by default, real reusable value)

- `CryptoAsyncMediaCodecAdapter.kt` -- a working, from-scratch async `MediaCodecAdapter`
  implementation using only public Android/Media3 APIs. Not wired to any default path
  (`HdrToneMappingRenderersFactory.FORCE_CRYPTO_ASYNC_DIAGNOSTIC = false`). Useful as a
  template for any future test needing custom `MediaCodec.configure()` flags Media3 itself
  doesn't expose -- but note its output-format-change handling has a known gap (see above)
  and would need that fixed before reuse beyond a short diagnostic run.

All diagnostic constants confirmed back to their defaults
(`FORCE_CRYPTO_ASYNC_DIAGNOSTIC=false`, `FORCE_WIDEVINE_L3_DIAGNOSTIC=false`,
`DIAGNOSTIC_FORCE_VIDEO_FORMAT_ID=null`, `enableOfficialLikeDirectHdrCodecConfig=false` in
the renderersFactory construction), clean rebuild reinstalled on device.

### Where this leaves things, finally

Exhaustive, first-hand testing across: renderer/surface/EGL/dataspace/Z-order/colorMode,
codec-adapter sync vs async threading, tiles/WPP/multi-slice/SAO/temporal-MVP/min-CU
(matched to F1's exact values via a real tile-capable encoder), bit depth, resolution,
three separate decoder implementations (MediaTek secure, MediaTek non-secure, Google
software), and now platform crypto-dispatch mode -- all converge on one thing: `cbcs`
pattern-encrypted HEVC is corrupted before any of this code runs, in the OEM Widevine
crypto plugin, which is not part of Media3, not part of AOSP, not open source, and not
reachable through any public Android API. `cenc`-encrypted HEVC in the identical shape,
and `cbcs`-encrypted AVC, both decode correctly on this exact hardware.

No further avenue is known within Media3, ExoPlayer, or standard Android MediaCodec/DRM
APIs. Matches androidx/media #2711 (open, unresolved). Remaining options unchanged: check
for a system update on the device; file/contribute this session's data to #2711.

## 2026-08-23 (live side-by-side session) — full parameter parity with the official app confirmed; player_tm rejected server-side; every codec-level lead now closed

A live F1 Academy qualifying session was running. This gave a real opportunity the
investigation hadn't had before: capturing the official app (`com.formulaone.production`,
`TiledPlayerActivityTv`) and our app decoding the *exact same live content at the exact
same moment*, not an old capture from a different day/CDN/manifest generation.

### `player=player_tm` (Tiledmedia's own player identity): rejected server-side

Tested directly (temporarily reordering `F1Client.requestedCandidates()` so the existing
but never-reached `TILED_MEDIA_PLAYER` candidate is tried first, then reverted). Result:

```
HTTP 500 -- {"resultCode":"OK","message":"Failed to evaluate stream rule","errorDescription":"ACN_2008","resultObj":{}}
```

Different from the response an earlier (June) session recorded for the same player
identity (`tmePresent=true`, no manifest -- also unusable, but at least accepted). Either
way, this **confirms `player_tm` was never a simple header/query-param swap**: F1's backend
gates it on something beyond what a `player_bm`-identified client sends -- almost certainly
device attestation or capability declaration tied to an actual Tiledmedia SDK integration.
There is no way to reach whatever manifest/session Tiledmedia gets by changing a parameter.

### Live A/B: official app vs. ours, same content, same moment

With the live session open in both apps, captured full `CCodecConfig`/`WVCdm` logs from
the official app for the first time against genuinely current (CloudFront-era, live)
content, not the June Akamai-era capture the "everything matches" conclusion was
originally based on. Two previously-undetected differences turned up in the raw
`C2MtkVdec` configure sequence (not visible in the earlier, coarser CCodecConfig diff):

- **Operating rate**: official = `0.000000` (unset), ours (default path) = `50.000000`
  (Media3's own computed value from the 50fps format).
- **`MaxInputSizeSetter` called twice** in our pipeline, escalating from MediaTek's own
  default `3145728` to `6220800`. The official app's decoder only ever sees `3145728`.
  Ruled out simple explanations: bitrate isn't the driver (a synthetic test clip at 87 Mbps
  never escalated); codec level isn't the driver (a synthetic clip declaring the identical
  level, `L153`, never escalated either). The escalation appears specific to real F1
  content passing through this app's own DASH/extractor pipeline.

Both differences are real, and both were *already* addressable by existing code
(`OfficialLikeHdrMediaFormat.configure()`, gated behind `enableOfficialLikeDirectHdrCodecConfig`,
already forces `KEY_MAX_INPUT_SIZE=3145728` for exactly this 3840x2160 case and already
suppresses the operating-rate hint) -- but that flag had never actually been tested against
*real, live, 4K* content in this investigation. Every prior test that exercised it used a
1080p forced-track override, where the `format.width==3840 && format.height==2160` guard
never fires.

Tested directly on the live session: `enableOfficialLikeDirectHdrCodecConfig=true` against
the live `_HDR-UHD_HEVC_2` track (`FORCE_WIDEVINE_L3_DIAGNOSTIC=true` for VPU visibility).
Confirmed both parameters now match the official app exactly --
`RealTime: priority 0, operating rate 0.000000` and `MaxInputSizeSetter: 3145728 ->
3145728` (no second call). **Still 495 `[LAT][Err]` lines, still the identical corruption
signature.**

### Where this leaves it

With this test, every `MediaFormat`/`MediaCodec.configure()`-level parameter this
investigation has ever been able to observe or control now matches the official app's live
session byte-for-byte, and the corruption is unchanged. This was the last concrete,
testable "maybe it's a config parameter" lead, found via a live side-by-side comparison
that hadn't been possible before tonight. It's now closed the same way as everything else:
ruled out, cleanly, with evidence.

Combined with the prior sessions' results (three decoder implementations, `cenc` vs `cbcs`
isolation, crypto-async dispatch mode, every bitstream-shape parameter), the corruption
has now been shown to be invariant across literally every layer this app's code, Media3,
or the public Android MediaCodec/MediaDrm API surface can influence. What's left uncontrolled
and unobservable is the actual DRM session negotiation/decrypt step inside the OEM crypto
plugin, and whatever `player=player_tm`/Tiledmedia's native pipeline does differently there
-- which, per F1's own server response above, isn't reachable through this app's client
identity at all.

All diagnostic flags reverted to their defaults; `F1Client.kt` confirmed byte-identical to
the committed version (`git diff` clean); device reinstalled with the clean build.

## 2026-08-23 (TME clean-room route) — authorized HDR TME feed reaches decode but remains green

After wireless ADB was restored, build `1.0.3` was installed and tested against a real broadcast. The new route did not use the rejected Android-TV `BIG_SCREEN_HLS/player_tm` request. It used the browser-authorized `WEB_HLS/player_tm` response and selected the channel feed from the returned TME catalog.

Observed:

- `profile=web_tme_HDR_UHD_CMAFWV`, `resultCode=OK`, `actualStreamType=HDR_UHD_CMAFWV`, `drmType=widevine`, `tmePresent=true`, no top-level manifest URL.
- The selected TME feed was the channel-specific HDR-UHD CMAF feed. The returned TME license URL was used with the web-route device identity.
- The main video configured `c2.mtk.hevc.decoder.secure`, queued encrypted samples, and produced a decoded frame. Output then transitioned from 640x360 to 1920x1080 to 3840x2160.
- Output remained HLG/BT.2020 (`color-standard=6`, `color-transfer=7`, Android dataspace `302383104`). No `[LAT][Err]` lines appeared in this secure TME run.
- The user still saw solid green video.

This is an important boundary result. The authorized TME feed and its HDR-UHD CMAF packaging can reach the secure decoder and produce output, so the TME response/catalog itself is not enough to solve the visual failure. The current app still presents the decoded protected output through its own Media3/Surface path; ClearVR additionally owns native access-unit scheduling, protected frame release, and the display/render bridge. The TME route is therefore a useful clean-room control, but not yet a replacement for the native ClearVR presentation architecture.

## 2026-08-23 (later) — the `tme` path reached, and a from-scratch ClearVR-style renderer built and tested; still `cbcs`, still corrupts

Independent work (not this session's) added two substantial pieces since the last entry:
a working parser for F1's `tme` response object, and `ClearVrLikeVideoRenderer.kt` -- a
complete, from-scratch `BaseRenderer` that drives raw `android.media.MediaCodec` directly,
bypassing Media3's `MediaCodecVideoRenderer` entirely. This is the most direct test yet of
whether *how* the codec is driven (as opposed to *what* it's fed) explains why the official
app works.

### The `tme` object is a real, reachable feed catalog

`F1Client.selectTmeFeedUrl()` parses `resultObj.tme.feeds[]`, matches by
`metadata.channel_id`, and extracts a real, playable manifest URL. Reaching it requires
spoofing a full desktop-browser identity (`DeviceInfo.tmeWebDeviceInfo`/`tmeWebUserAgent`,
Chrome/Windows) alongside `player=player_tm` and `platform=WEB_HLS` -- the
`BIG_SCREEN_HLS`-identified request tested in the prior entry (`ACN_2008`, rejected
outright) fails specifically because of the platform/device identity, not the player
parameter alone. With the browser identity, F1 returns `tmePresent=true` and a working feed
catalog: confirmed live, `HDR_UHD_CMAFWV`, channel 1025, resolving to
`WIF-F1-R1/HDR-UHD-CMAF-WV` on `ott-video-fl.formula1.com` -- HLS+CMAF, not DASH.

### The HLS playlist's stated encryption method is misleading

The master/media playlists declare `#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR`, which reads
as the HLS label for CTR-mode (`cenc`-equivalent) encryption -- a very promising signal at
first glance, since tonight's `cenc` vs `cbcs` isolation showed `cenc` decodes cleanly. It
is not to be trusted at face value: `ClearVrLikeVideoRenderer` logs the *actual* decrypted
`CryptoInfo` per sample, and the first real sample showed:

```
First encrypted sample pts=1000000000000 size=74179 keyFrame=true mode=2 subs=1
  clear=[286] encrypted=[73893] pattern=1:9 keyLen=16 ivLen=16
```

`mode=2` is `MediaCodec.CryptoInfo.MODE_AES_CBC`. Pattern `1:9` matches F1's cbcs pattern
exactly. **This CMAF-WV/TME feed is `cbcs`, identical in every cryptographically relevant
respect to the DASH-WV feed already exhaustively tested.** The HLS playlist's declared
`SAMPLE-AES-CTR` is packaging-level metadata that does not describe the actual sample
encryption inside the fragmented MP4 (a known real-world inconsistency in some CMAF
packagers) -- it should not have been trusted without checking the real `CryptoInfo`, which
is exactly what this test caught.

### The renderer itself: closest possible reimplementation of the observed ClearVR contract

`ClearVrLikeVideoRenderer` matches every behavioural detail visible from the official app's
logs and the earlier decompiled string evidence:
- A separate, independent codec-config buffer (`BUFFER_FLAG_CODEC_CONFIG`) instead of
  Media3's inline `csd-0` MediaFormat key.
- Configures the codec at the *coded* (pre-crop) dimensions (`format.decodedWidth`/
  `decodedHeight`), not the display/cropped size Media3 normally uses.
- A deliberately minimal `MediaFormat` -- only `rotation-degrees`, `priority`, `frame-rate`;
  explicitly no `csd-*`, no `max-width`/`max-height`, no `max-input-size`, no
  `operating-rate`.
- `flags=0` on every ordinary sample, including keyframes.
- Keeps one decoder alive across ABR ladder changes via MediaCodec's in-place output-format
  update, rather than hard-reconfiguring -- confirmed working live: `640x360 -> 1920x1080 ->
  3840x2160` in place, each step logged (`Updating compatible decoder in place ...`), each
  producing a clean `Decoder output format` callback.

Live result: `Configured c2.mtk.hevc.decoder.secure ... secure=true`, decoded continuously
for 90+ seconds through all three resolution steps up to full 3840x2160, audio in sync the
whole time (continuous `AudioFlinger` activity, no gap), zero exceptions, zero crashes.
No `[LAT][Err]` visibility this run (secure decoder, as expected -- TEE-internal errors
never reach normal logcat, consistent with every other secure-path test all session).
**User-confirmed result: still green.**

### What this settles

This was the most direct possible test of "is it *how* we drive MediaCodec, in Java, that
differs from the official app" -- built independently, using no Media3 abstractions, hewing
as closely as the public API allows to the observed ClearVR bridge contract. It still
fails, on genuinely `cbcs`-encrypted content (confirmed by direct `CryptoInfo` inspection,
not by trusting a possibly-misleading playlist label). Combined with the earlier
cross-decoder result (MediaTek secure, MediaTek non-secure, and Google's own software HEVC
decoder all corrupt identically), this now closes off the Java/Kotlin MediaCodec API
surface as a whole, not just Media3's slice of it: no sequence of calls reachable from
application code -- through Media3 or hand-written directly against `android.media.MediaCodec`
-- avoids the corruption. Whatever the official app does differently must be beneath the
public `MediaCodec`/`MediaDrm` Java API entirely, in the DRM session/crypto negotiation
itself, which application code cannot observe or influence through any route this
investigation has been able to reach.

The `tme` path is still valuable to keep, independent of this result: it is a real,
working, previously-unreached manifest source (confirmed access, real feed catalog,
resolution ladder up to genuine 3840x2160 UHD) that was not available through the
`player_bm` route. It should be retained for whatever the eventual product decision is
(e.g. it could turn out to matter for content availability or CDN routing even though it
does not solve the decode corruption).

---

## 2026-09-11 -- Root cause found: F1 issues our app's Widevine key at SW_SECURE_CRYPTO, the official app gets HW_SECURE_DECODE

This session started from a live report that the same live-broadcast session was green,
and closed with a byte-for-byte comparison of both apps' actual Widevine licence
responses, captured on the same device minutes apart, for the identical key ID. It is the
first entry in this document that identifies a specific, verified mechanism for the green
screen rather than eliminating another candidate.

### Reframing: it isn't a per-weekend content difference, it's intermittent on identical content

The prior working hypothesis (see earlier entries) was that a specific past race
weekend's HDR stream decoded correctly while the current weekend's did not, pointing at
an encoding-level difference (tile grid, bit depth, etc). Live testing this session
falsified that: the same session (contentId=1000010367, channelId=1025, live broadcast)
was reported green, then a few minutes later reported as playing correctly, then green
again on a later relaunch -- with no app changes in between at first. Whatever is wrong is
not fixed to specific content; it is per-session/per-request.

### Three real bugs found and fixed along the way

None of these turned out to be the root cause, but all three are genuine defects,
confirmed with direct before/after evidence, and have been left in the fixed state:

1. **ABR crossed from the HLG ladder onto the parallel SDR ladder.** F1's HLS master
   playlist for HDR_UHD_CMAFWV content carries two independent variant ladders under the
   same set of EXT-X-STREAM-INF entries: an HLG ladder (hev1.2.4.*, up to 3840x2160) and
   an SDR ladder (hev1.1.6.*, capped at 2880x1620), distinguished only by the VIDEO-RANGE
   attribute, which Media3's HLS parsing does not surface into Format.colorInfo on this
   path. With no signal keeping the two apart, bandwidth-driven ABR selected the SDR
   2880x1620 rendition mid-ladder instead of the HDR one. Confirmed directly: the
   decoder's own output-format callback for that step reported color-standard=1 (BT.709)
   / color-transfer=3 (SDR) instead of color-standard=6 (BT.2020) / color-transfer=7
   (HLG) -- correct for what is genuinely SDR content, wrong content to have selected.
   Fixed in OfficialLikeHdrPlaybackFragment.onTracksChanged by excluding every video
   track whose Format.codecs starts with "hev1.1." before track selection runs,
   restricting ABR to the HLG ladder exclusively.

2. **ClearVrLikeVideoRenderer's in-place ladder update desynced the MediaTek Codec2
   driver.** The renderer normally keeps one MediaCodec instance alive across resolution
   changes by feeding a fresh codec-config buffer instead of a hard configure(),
   mirroring the official app's apparent behaviour. On this device that path was directly
   observed (a) losing correct HDR colour aspects on the next tier immediately after an
   in-place update (a hard configure() at the same resolution never showed this), and (b)
   desyncing MediaTek's internal work tracking a few seconds later ("PipelineWatcher:
   onWorkDone: frameIndex not found (-1); ignored", followed by "C2MtkVdec: Unknown
   output workId: <pts>"). Every hard configure() observed all session derived HDR colour
   aspects correctly; no in-place update ever did. Fixed by forcing a full codec
   teardown/reconfigure on any resolution or coded-size change instead of reusing the
   codec instance.

3. **No codec-config data was ever queued for this content.** F1's HLS/CMAF Format
   objects reach ClearVrLikeVideoRenderer with initializationData empty (initData=[] in
   every capture, every tier, all session), so queueHeaderIfNeeded() silently no-opped --
   no BUFFER_FLAG_CODEC_CONFIG buffer was ever sent to the secure decoder. Diffing every
   C2MtkVdec/CCodec log line between our app and the official app (normalized,
   deduplicated) turned up two lines that appeared only for the official app's decoder
   instance: "mSawInputCSD was set." and "CSD work returned: workId N, mFrameIndex N".
   Fixed by recovering VPS/SPS/PPS directly from the clear prefix of the first sample's
   own access unit (parameter sets always precede the first VCL NAL) and queuing them as
   an explicit codec-config buffer before the first real sample. After the fix, both log
   lines appear for our decoder instance too, matching the official app exactly.

After all three fixes, decode was clean by every observable signal: correct BT.2020/HLG
colour at every tier including straight-to-4K resumes, zero C2MtkVdec/PipelineWatcher
errors across sustained playback, and a `dumpsys SurfaceFlinger` comparison against the
official app's live video layer showing identical composition state -- same 0.5x SCALE
transform, same hasProtectedContent=true, same dataspace=BT2020_ITU_HLG, same composition
type=DEVICE. Still green.

Bisecting further: routing the identical stream through Media3's stock
MediaCodecVideoRenderer (enableTiledmediaLikeCodecPath=false) instead of
ClearVrLikeVideoRenderer produced the same green result. This exonerates both video
renderer implementations, and everything upstream of MediaCodec configuration, at once.

### The actual root cause: differential Widevine security-level grant by client identity

KeyStatusLoggingDrmSessionManagerProvider (wired into the HDR path -- previously only
into the DASH branch and gated behind a flag that defaulted false, both fixed this
session) logs the raw licence response bytes returned by F1's server. Decoding the
SignedMessage/License protobuf by hand surfaces each KeyContainer's id (KID), type,
level, and track_label fields in the clear.

Every one of our app's licence responses this session -- 12 separate requests, spanning
multiple relaunches, both ClearVrLikeVideoRenderer and the stock Media3 renderer, both
the DASH and TME/HLS routes -- decoded to:

    KeyContainer: id=87577e955fc630f6a507fb9ae90fc6a5, type=CONTENT,
                  level=SW_SECURE_CRYPTO, track_label="HD"

The official app (com.formulaone.production) was launched on the same device, playing the
same live broadcast, and its Widevine AddKey calls are visible in the system WVCdm log
(owned by the platform Widevine HAL process, not gated by anything either app controls).
Decoding those:

    KeyContainer: id=87577e955fc630f6a507fb9ae90fc6a5, type=CONTENT,
                  level=HW_SECURE_DECODE, track_label="SD"

Identical key ID. Different SecurityLevel. HW_SECURE_DECODE is required for a key to be
loaded into the TEE key ladder; SW_SECURE_CRYPTO cannot be. The HDR path uses
c2.mtk.hevc.decoder.secure, MediaTek's SVP hardware decoder, which decrypts directly into
protected memory inside the TEE. Handed a SW_SECURE_CRYPTO key, OEMCrypto has no valid
path to service that request: the protected output buffer is never filled, and a
zero-filled YUV buffer composites as uniform green -- with no exception, no
CryptoException, no HWC/SurfaceFlinger error, at any layer this investigation has been
able to observe. This is consistent with every symptom recorded across the entire life of
this document: green from the first frame at every resolution; correct-looking
CryptoInfo/KID/tenc/colour metadata throughout (none of that depends on the key's
security level); SDR/AVC playback unaffected (its decoder is not secure, so
SW_SECURE_CRYPTO is sufficient); and no sequence of Media3/MediaCodec API calls from
application code ever having made a difference, because the actual defect is in what the
licence server decides to grant before any of that code runs.

The discriminator is almost certainly the CDM-embedded client identity in the key request
itself, visible in the request protobuf logged this session:

    application_name: com.st14n.f1.debug
    package_certificate_hash_bytes: <our signing cert hash>

F1's Widevine policy service is evidently entitling com.formulaone.production's specific
package identity/certificate for hardware-secure delivery, and issuing everyone else
SW_SECURE_CRYPTO regardless of what security level the requesting device's CDM is
actually capable of ("IsSecurityLevelSupported] level = L1" succeeds for both apps' CDM
sessions on this device -- the device itself supports L1/hardware-secure; the server
chooses not to grant it to us).

### What this rules in and out for a fix

- **Not a bug in this app's Media3/MediaCodec/renderer code.** Every layer this session
  touched was brought to parity with the official app (SDR-ladder exclusion, in-place
  reconfigure, CSD delivery) or proven byte-identical (SurfaceFlinger composition state),
  and the green screen persisted through all of it, on both the custom renderer and
  Media3's stock renderer.
- **Not fixable by spoofing the official app's client identity to F1's server.** That
  would mean sending F1's licence endpoint a forged application_name and
  package_certificate_hash_bytes matching com.formulaone.production's real signing
  certificate, to obtain a security-level grant tied to an entitlement F1 has not
  extended to this app. That is circumventing a content-protection access control by
  impersonating another party's certified client, not a legitimate workaround, and is out
  of scope regardless of subscription status.
- **The open, legitimate path is decoding at the security level F1 actually grants us.**
  SW_SECURE_CRYPTO does not require the hardware SVP decoder. c2.mtk.hevc.decoder
  (non-secure) should be able to use that same key. An L3-forced run late this session
  did reach the non-secure decoder and produced VPU-visible decode errors ("[LAT][Err]
  Decoding Error 0x44160040" at the last LCU of affected frames) rather than green -- but
  that run is not trustworthy as a verdict: forcing L3 swaps out the entire
  DrmSessionManagerProvider, which bypassed the licence-logging path, so it is not known
  which licence (level/label) that specific run actually received, and several other
  flags were live at the same time. This needs to be re-run cleanly: L3 forced, the
  licence-logging provider still wired so the granted level is confirmed for that exact
  run, stock renderer, nothing else changed -- before concluding anything about whether
  SW_SECURE_CRYPTO plus non-secure decode can produce a correct picture on this content.

### Open question: is the SW_SECURE_CRYPTO grant specific to live broadcasts?

Every licence decoded this session was for a live broadcast (contentId=1000010367). This
investigation's earlier entries report replay/VOD sessions playing correctly on past
occasions, which raises the possibility that F1's policy service grants HW_SECURE_DECODE
for replay content but not live. That is unverified -- no replay licence has actually been
decoded and compared the way the live ones were. It is equally consistent with the data so
far that replay sessions are subject to the same per-request SW_SECURE_CRYPTO/
HW_SECURE_DECODE inconsistency as live, and earlier "replay works" reports simply did not
happen to land on a SW_SECURE_CRYPTO grant. Before treating "live-only" as established,
capture and decode a replay session's licence the same way (RAW_LICENSE_RESPONSE via
KeyStatusLoggingDrmSessionManagerProvider) and compare its KeyContainer level/track_label
against the live values recorded above.

### Diagnostic capability added this session, worth keeping

KeyStatusLoggingDrmSessionManagerProvider.KeyStatusLoggingExoMediaDrm.provideKeyResponse
now logs the complete raw licence response body (base64, chunked across log lines) in
addition to the pre-existing queryKeyStatus policy dump. This is what made the
level/label comparison in this entry possible, and was previously not being captured
anywhere in this investigation's history despite repeated hypotheses about licence
content (see the "KID mismatch" hypothesis in that file's own class-level comment, dated
2026-08-22, which was never actually verified against real licence bytes until now -- and
turned out to be false; the KID is correctly matched, only the granted security level
differs).

## 2026-09-11 (later) -- code change: clean L3 re-test now possible

The clean L3 re-test the previous entry specifies requires the licence-logging
provider (KeyStatusLoggingDrmSessionManagerProvider) to be wired AND L3 to be
forced in the same provider. Previously the FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC
flag in ChannelPlaybackFragment swapped in a separate, licence-blind provider
(ForceWidevineL3DrmSessionManagerProvider), so the granted SecurityLevel/track_label
for that run was unknown and any verdict was untrustworthy.

Code change (2 files, 68 insertions / 22 deletions, gradlew :app:compileDebugKotlin
clean):

- KeyStatusLoggingDrmSessionManagerProvider gained an optional orceL3: Boolean =
  false constructor parameter. When 	rue, the existing L1-request is replaced
  with the same MediaDrm.setPropertyString("securityLevel", "L3") mechanism
  ForceWidevineL3DrmSessionManagerProvider uses, applied to the same
  KeyStatusLoggingExoMediaDrm wrapper so RAW_LICENSE_RESPONSE and
  queryKeyStatus still fire. Default behaviour (L1 request, no force) is
  unchanged for every other call site.
- ChannelPlaybackFragment.FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC now wires
  KeyStatusLoggingDrmSessionManagerProvider(httpDataSourceFactory, forceL3 = true)
  instead of ForceWidevineL3DrmSessionManagerProvider(httpDataSourceFactory). No
  other flag is touched. Renderer, manifest parser, init segment repair, audio,
  tunneling all stock -- exactly what the previous entry asked for.

ForceWidevineL3DrmSessionManagerProvider.kt is left in place because
OfficialLikeHdrPlaybackFragment still references it for its own diagnostic
flags; that fragment is not used in this re-test.

### How to run the re-test

1. Flip ChannelPlaybackFragment.FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC to 	rue.
2. ./gradlew.bat :app:assembleDebug --console=plain.
3. Install on the device, open a live broadcast.
4. Capture logcat (PowerShell):

`powershell
adb logcat -c
adb logcat -v time > C:\race-control-tv-st14n\logcat_l3_clean_test.txt
`

5. After the test, filter for the signals that matter (PowerShell):

`powershell
Select-String -Path C:\race-control-tv-st14n\logcat_l3_clean_test.txt -Pattern 'Forced Widevine securityLevel|RAW_LICENSE_RESPONSE|CDM key status|queryKeyStatus|c2\.mtk\.hevc\.decoder(?!.*secure)|c2\.mtk\.hevc\.decoder\.secure|renderedFirstFrame|drmKeysLoaded|android\._dataspace|MtkHdmiService|setVideoResolution: hdrMode|setVideoConfig hdrMode|Player error|F1DashInit|F1DashChunk|F1DynamicHvcC'
`

### What to look for

- Forced Widevine securityLevel=L3 (software decode diagnostic) confirms L3
  was applied. Its absence means the reflection path failed and the test is
  invalid; rebuild without that line is suspect.
- RAW_LICENSE_RESPONSE begin size=... confirms the licence was logged. Decode
  the base64 chunks with the offline proto decoder this session has been using
  to confirm the granted KeyContainer.level and 	rack_label for THIS run.
  Expected on a clean re-test: same level=SW_SECURE_CRYPTO, same
  	rack_label="HD" as the official com.formulaone.production comparison --
  because F1's policy decides per package cert, and we are still presenting
  com.st14n.f1.debug's cert. If level=HW_SECURE_DECODE shows up, that is
  itself a new finding (the server granted L1 hardware to a session
  advertising L3 software, which the OEMCrypto spec says it can do but F1 has
  not been seen to do).
- c2.mtk.hevc.decoder (with no .secure suffix) confirms the non-secure
  MediaTek decoder was selected. If c2.mtk.hevc.decoder.secure appears
  instead, Media3 picked the secure decoder anyway and the L3 force was
  ignored at the decoder level; the test is then not a clean L3 run.
- enderedFirstFrame + visual: if first frame rendered and the picture is
  not green, SW_SECURE_CRYPTO + non-secure decode produces correct output on
  this content -- the green was strictly a key/decoder mismatch, fixable in
  app code by forcing L3. If still green, the previous entry's
  "unfilled protected output buffer" hypothesis is wrong and the search widens.
- Player error / [LAT][Err] for any new VPU error code, to capture
  whatever the non-secure decoder does on real F1 cbcs content.
