# Carlink branch development

Updated 2026-11-11. Base: `zhujiankang/mcarplay` at `ab0cb93` (`0.1.6`).

## Goal and current boundary

One Xiaomi, vivo or other ICCOA-capable phone should receive its own projection stream
and send it to an existing wireless CarPlay head unit, without an external box.
This branch implements an experimental receiver and connects its media output to mcarplay.
**It has not established an actual CarWith/vivo session or a Mercedes CarPlay connection.**
The upstream README's CarLife compatibility evidence does not validate this Carlink path.
This is a development APK, not a compatible-device release.

```
OEM phone projection app
   [missing: same-device discovery/start and any required OEM certificate]
   → UCar AUTH / encrypted CONTROL
   → RTSP/WFD → RFC4571 RTP → MPEG-TS → H.264 / AAC-LC
   → mcarplay video sender / AAC-to-PCM decoder and existing audio bridge
   → existing Bluetooth iAP2 + Wi-Fi/AirPlay output → wireless CarPlay head unit
```

## Implemented

- Explicit persisted CarLife/Carlink selection. CarLife remains the default; selecting
  Carlink does not silently fall back to CarLife. The OEM start component is not called
  with invented parameters or privileged permissions.
- Bounded UCar framing, CRC16/ARC, singular protobuf fields, P-256 PIN pairing, ECDH,
  HKDF/HMAC and AES-GCM records. Unknown quick pairing requests ask for normal pairing.
  A fresh receiver identity is used; there is no persisted trust or OEM identity.
- Original CarWith 4.0.9 generated protobuf field numbers were checked. AuthConfirm accepts
  `IPv4:controlPort`, including dynamic ports; plain IPv4 uses legacy port 57219.
- Loopback-only AUTH listener `127.0.0.1:57209`, RTP listener `127.0.0.1:15550`;
  confirmed endpoints must be `127.0.0.1`. Outbound CONTROL uses the confirmed port,
  RTSP uses port 7236. No network-wide discovery or listening is enabled.
- Display negotiation requests 1280×720 at 30 fps, classic mode only. The WFD sink
  advertises H.264 baseline/720p30, AAC and TCP RTP; no HEVC, HDCP, fusion desktop or UIBC.
- RTSP OPTIONS/GET_PARAMETER/SET_PARAMETER/SETUP/PLAY/TEARDOWN and IDR requests;
  MPEG-TS PAT/PMT/PES with CRC, continuity checks, 33-bit PTS wrap and AAC ADTS reassembly.
- H.264 forwarding to the existing CarPlay output. Each output waits for an IDR plus
  SPS/PPS; congestion/discontinuity rearms that gate and requests another IDR.
  The existing MIT Annex-B codec helper is reused, but CarLife sockets, authentication
  and launching are not used by the Carlink receiver.
- AAC-LC decodes to PCM through Android MediaCodec and enters the existing resampling/
  CarPlay audio path. Decoder behavior and audible playback still require device tests.
- Private foreground service, explicit start/stop, two-minute idle acceptance window,
  separate source lifecycle, disconnect handling and main-screen stream reattachment.
- A diagnostic page shows incoming media counts, resolution, stage and a per-run PIN.
  Shared status and application logs exclude PINs, identity strings and raw media.

## Unimplemented or unverified

1. **Same-device bootstrap.** Opening Xiaomi settings does not create a session.
   CarWith 4.0.9 protects `CarlinkService` with a signature/privileged permission;
   its P2P path consumes BLE scan data, not just an arbitrary IP argument.
   The user's probe reported the permission denied and no incoming connection.
   Running a listener alone cannot solve this. Vivo bootstrap has not been analyzed.
2. **OEM certificate stage.** No OEM certificate/private key or synthetic substitute is
   shipped. After CONTROL, the receiver attempts standard WFD; an OEM peer may require
   certification first and refuse or time out. The attempt is explicitly logged.
3. Touch/UIBC, calls, microphone, navigation-audio mixing and media-key mapping for
   Carlink are not implemented. No assumption of ICCOA fusion-mode interoperability.
4. Only H.264/AAC-LC over TCP MPEG-TS is handled. UDP RTP, HEVC, encrypted video and
   other OEM transports are unsupported. Video is not locally decoded as a self-test;
   a framed IDR with parseable SPS is an admission check, not playback certification.
5. Wi-Fi/Bluetooth concurrency between OEM Carlink discovery and CarPlay has not been
   tested. Same-phone BLE discovery cannot be assumed to work. Xiaomi evidence cannot
   be extrapolated to vivo, and a successful source connection would not itself prove
   the target Mercedes supports the existing CarPlay output path.

## Local validation and device procedure

Unit tests use independent, locally generated JCA client keys and synthetic MPEG-TS.
They cover framing/limits, PIN and response proofs, tampering/replay, endpoint restrictions,
RTSP negotiation, H.264/AAC demux, timestamp units/wrap, partial AAC and socket shutdown.
A local synthetic source traverses AUTH → CONTROL → RTSP PLAY → RTP video and disconnects.
**These tests do not run an OEM app, Android MediaCodec or the native CarPlay receiver.**

Validation on 2026-11-11: 920 standard JVM tests (including 21 new Carlink tests) and
24 remote bridge tests passed, with no failures, errors or skipped tests. Spotless,
standard debug lint and standard debug APK assembly passed. APK signature verification
passed with the Android debug certificate. No physical Android device was attached.

Build with JDK 21, SDK 35 and Build Tools 35.0.0:

```sh
./gradlew spotlessCheck :app:testStandardDebugUnitTest :app:lintStandardDebug \
  :app:assembleStandardDebug :app:testRemoteDebugUnitTest --tests 'com.mcarplay.app.bridge.*'
```

APK: `app/build/outputs/apk/standard/debug/app-standard-debug.apk`, package
`com.mcarplay.app`, debug signing. This may replace a matching debug install and will
not update a release install signed with a different key. It is the integrated mcarplay
app; the earlier standalone Carlink Receiver Probe is a different package.

In mcarplay, open **Diagnostics → Carlink input (experimental)**, select Carlink and explicitly start
local reception. If no incoming peer appears, export status; there is no need to repeat
CarPlay pairing. `LISTENING_LOCAL_ONLY` means only that sockets opened.
`AUTH_CONFIRMED_PEER_APP_UNVERIFIED` still does not identify the sender as CarWith.
A video count greater than zero only proves media reached this receiver; identify the
OEM process separately. Only when a usable IDR arrives is CarPlay start permitted.

Next acceptance milestones: obtain an authorized OEM bootstrap/receiver integration;
observe a genuine OEM AUTH/CONTROL/certificate trace; receive sustained video and audio;
then test one specific phone/OS/OEM-app/vehicle-firmware combination for picture, sound,
latency, disconnect/reconnect, screen return and radio concurrency. Touch follows its own
UIBC implementation and device acceptance. Broader Xiaomi/vivo support follows those tests.

## Source provenance

New code in `carlink/` is independently written with JCA and Java/Kotlin standard APIs;
wire facts are checked against the user-provided, unmodified CarWith APK and public
protocol descriptions. No OpenXPlay AGPL or AionLink GPL source was copied into this MIT
repository. Third-party APKs, decompiled sources, device captures, private keys and OEM
certificates are absent from the repository and generated APK.
