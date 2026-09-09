# Axie Remote — Android Screen Stream + Control · SPEC

> Location: `mobile/` · Target artifact: **installable APK** (`app-debug.apk` → later `app-release.apk`)
> Host toolchain: **Linux (Ubuntu 24.04)** · Language: **Kotlin** · UI: **Views (M0) → Jetpack Compose (M1+)**
> minSdk **29** (Android 10) · targetSdk **34** · compileSdk **34** · AGP **8.5.x** · JDK **17+**
> Product brief: [`goal.md`](./goal.md) (phone stays a normal device; CRM web app is the viewer).
> This file is the implementation spec derived from it.

## 1. Goal

Build a small, real native Android app you install on your phone that:

1. **Streams the phone screen** to a viewer (browser/CRM) in near-real-time.
2. **Lets the viewer interact** — tap, swipe, back/home/recents, type text — as if holding the phone.
3. Ships as a **single APK** you can `adb install` or side-load, with no root required.

### Non-goals (v1)

- No Play Store release, no rooting, no custom ROM.
- No audio capture v1 (add later via `AudioPlaybackCapture`).
- No multi-device fleet management v1 (one phone ↔ one viewer session).
- No covert surveillance: every capture requires the on-device user to grant consent.

## 2. Canonical Android APIs (do not reinvent)

All design below follows the official docs (fetched via Context7 on 2026-09-08):

| Capability | API | Official doc |
|---|---|---|
| Screen capture consent | `MediaProjectionManager.createScreenCaptureIntent()` + `getMediaProjection(resultCode, data)` | https://developer.android.com/media/grow/media-projection |
| Screen → Surface | `MediaProjection.createVirtualDisplay(name, w, h, dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, …)` | https://developer.android.com/reference/android/media/projection/package-summary.html |
| Capture package | `android.media.projection` (`MediaProjection`, `MediaProjectionManager`, `MediaProjection.Callback`) | https://developer.android.com/reference/android/media/projection/package-summary.html |
| Remote input injection | `AccessibilityService.dispatchGesture()`, `performAction()`, `performGlobalAction()` | https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.html |
| Service setup / config | Accessibility service declaration + `accessibilityservice.xml` (`canPerformGestures`, `canTakeScreenshot`) | https://developer.android.com/guide/topics/ui/accessibility/service |
| Foreground capture service | `Service` with `foregroundServiceType="mediaProjection"` (mandatory since Android 10/Q, permission `FOREGROUND_SERVICE_MEDIA_PROJECTION` since Android 14/UpsideDownCake) | Context7 `/websites/developer_android_guide` + `/websites/developer_android_reference` |
| Future low-latency AV | WebRTC `ScreenCapturerAndroid(intent, callback)` → `PeerConnection` | Context7 `/getstream/webrtc-android`, `/websites/webrtc` |

Key facts verified via Context7 (`CONTEXT7_API_KEY` in root `.env`, endpoint `GET /api/v2/context`):

- Consent flow is an `ActivityResultLauncher(StartActivityForResult)` that launches
  `mediaProjectionManager.createScreenCaptureIntent()`; only `RESULT_OK` yields a token
  (`mediaProjectionManager.getMediaProjection(resultCode, data)`).
- The `MediaProjection` renders into a `Surface` via `createVirtualDisplay(...)`. Recommended
  size source on API 30+: `WindowMetrics`; density via `Configuration#densityDpi` (not the
  deprecated `Display#getRealMetrics()`).
- The OS surfaces active projections in Quick Settings; the user can revoke at any time →
  `MediaProjection.Callback.onStop()` fires → the app **must** stop recording and release the
  `VirtualDisplay`. Handle this or you leak a mirror.
- `AccessibilityService` taps/swipes = build a `Path` → `GestureDescription.StrokeDescription`
  → `dispatchGesture(...)`; requires `android:canPerformGestures="true"`. Node actions
  (`ACTION_CLICK`, `ACTION_SCROLL_FORWARD`, …) and globals (`GLOBAL_ACTION_BACK/HOME/RECENTS`)
  cover buttons and scrolling. Always `recycle()` `AccessibilityNodeInfo`.
- `takeScreenshot()` / `takeScreenshotOfWindow()` exists on `AccessibilityService` but is for
  ML-style snapshots — **not** the streaming path. Streaming stays on MediaProjection.

## 3. Architecture

```
┌────────────────────────────── PHONE (APK) ──────────────────────────────┐
│ MainActivity (Compose)                                                   │
│  • pairing screen (server URL + device token)                            │
│  • "Start sharing" → MediaProjection consent intent                      │
│  • shows connection state, stops session                                 │
│                                                                          │
│ ScreenCaptureService (ForegroundService, type=mediaProjection)            │
│  • owns MediaProjection + VirtualDisplay + MediaProjection.Callback      │
│  • Phase 1: VirtualDisplay → ImageReader → JPEG → WebSocket               │
│  • Phase 2: VirtualDisplay → MediaCodec (H.264) → WebSocket               │
│  • Phase 3: VirtualDisplay → WebRTC ScreenCapturerAndroid → PeerConnection│
│                                                                          │
│ ControlAccessibilityService (AccessibilityService)                       │
│  • canPerformGestures=true, receives input cmds from signaling client    │
│  • dispatchGesture(tap/swipe) · performGlobalAction(back/home/recents)   │
│  • optional: findFocus + ACTION_SET_TEXT for typing                       │
│                                                                          │
│ SignalingClient (OkHttp WebSocket, JSON)                                 │
│  • single WS to server; multiplexes video frames + input events          │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │  wss://<crm>/device (JSON, §5)
┌──────────────────────────────▼───────────────────────────────────────────┐
│ SERVER (CRM NestJS API or tiny relay — NOT in this repo yet)             │
│  • authenticates device token, pairs viewer ↔ device                     │
│  • relays frames device→viewer, input viewer→device                      │
│ VIEWER (browser page — Phase 1: <canvas>/<img>; Phase 3: WebRTC player)  │
│  • renders stream, converts mouse/touch → normalized input commands      │
└──────────────────────────────────────────────────────────────────────────┘
```

Why this split: **MediaProjection cannot inject input** and **AccessibilityService cannot
capture video**. You need both services; the `SignalingClient` is the glue. Keep them in one
APK / one process for v1 (simplest lifecycle), split later if needed.

## 4. Permissions & manifest matrix

`AndroidManifest.xml` (v1):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Phase 2+ only: <uses-permission android:name="android.permission.RECORD_AUDIO" /> -->
```

Services:

```xml
<service android:name=".capture.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false" />
<service android:name=".control.ControlAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
  <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
  <meta-data android:name="android.accessibilityservice"
      android:resource="@xml/accessibilityservice" />
</service>
```

`res/xml/accessibilityservice.xml`:

```xml
<accessibility-service
    android:canPerformGestures="true"
    android:canTakeScreenshot="false"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityEventTypes="typeAllMask"
    android:notificationTimeout="50" />
```

Runtime/UX requirements per API level:

| API | Requirement |
|---|---|
| 29 (Q) | `foregroundServiceType="mediaProjection"` mandatory; start capture service with `startForegroundService()` + `startForeground()` ≤ 5 s with a visible notification. |
| 30–33 | Same + prefer `WindowMetrics` for capture size; handle Quick-Settings revocation via `MediaProjection.Callback.onStop()`. |
| 34 (U) | Must also hold `FOREGROUND_SERVICE_MEDIA_PROJECTION` or `startForeground()` throws `SecurityException`. `POST_NOTIFICATIONS` needs runtime grant or notification is silent. |
| 24+ | `dispatchGesture()` available (multi-stroke, tap, swipe). minSdk 29 ⇒ always available. |

Accessibility onboarding: the service is **off by default** — the app must deep-link the user to
`Settings.ACTION_ACCESSIBILITY_SETTINGS` with an explanation. Same for the MediaProjection
consent dialog (system-owned, cannot be bypassed).

## 5. Signaling protocol (v1, JSON over one WebSocket)

Base URL configurable in-app, e.g. `wss://api.example.com/device`. Auth: first message `hello`
with pairing token (query param also accepted).

Device → server:

```jsonc
{ "type": "hello", "deviceId": "pixel-8-01", "token": "<pairing-token>",
  "screen": { "width": 1080, "height": 2400, "dpi": 420 } }
{ "type": "frame", "codec": "mjpeg", "seq": 123, "ts": 1710000000000, "dataBase64": "<jpeg>" }
{ "type": "bye" }
```

Server → device (viewer input, normalized 0..1 coordinates):

```jsonc
{ "type": "tap",   "x": 0.5, "y": 0.3 }
{ "type": "swipe", "x1": 0.5, "y1": 0.8, "x2": 0.5, "y2": 0.2, "durationMs": 400 }
{ "type": "key",   "action": "back" }            // back|home|recents|power? (power NOT allowed)
{ "type": "text",  "text": "hello" }            // via focused node ACTION_SET_TEXT
{ "type": "ping" }                              // device replies {"type":"pong"}
```

Mapping to device: `tap`/`swipe` → `dispatchGesture`; `key` → `performGlobalAction`;
`text` → focused `AccessibilityNodeInfo.ACTION_SET_TEXT` (fallback: best-effort, may need app focus).
Coordinates are scaled by current `WindowMetrics` at dispatch time (rotation-safe).
Phase-3 extensions (same transport): `longpress {x,y,durationMs}`, `drag` (= `swipe` with
longer duration), multi-stroke `GestureDescription` for pinch/multi-touch (`goal.md` §3).

Throughput targets v1: 720p, 2–5 fps MJPEG over LAN is acceptable; tune JPEG quality (50–70)
and `ImageReader.acquireLatestImage()` (drop, never queue) to bound latency < 1 s on LAN.

### 5.1 Viewer extension (v1.1 — backwards compatible)

The web viewer (`apps/console` → `/mobile` page, Carbon UI) joins the same
room with the **same relay URL + device token** the phone uses:

```jsonc
// Viewer → relay (joins room for `token`)
{ "type": "hello", "role": "viewer", "token": "<pairing-token>", "viewerId": "web-…" }
// Relay → viewer (presence + forwarded stream)
{ "type": "welcome", "online": true, "deviceId": "pixel-8-01",
  "screen": { "width": 720, "height": 1600, "dpi": 420 } }
{ "type": "device", "online": false }   // phone left / not sharing yet
{ "type": "frame", "codec": "mjpeg", "seq": 123, "ts": 1710000000000, "dataBase64": "<jpeg>" }
// Viewer → relay (forwarded to the phone as-is): tap/swipe/key/text/
// longpress/drag/scroll/ping/bye — same shapes as above.
```

Relay duties (`apps/console/scripts/device-relay.mjs`, one Node process, `ws`
only): pair by token, fan out frames latest-only (drop for backed-up viewers
instead of queueing — `bufferedAmount` ceiling), answer `ping` locally, never
log or persist frames/tokens.

Resource saver: the relay sends the phone `{ "type": "viewers", "count": N }`
on every viewer join/leave. `ScreenCaptureService` skips JPEG encode + upload
while `count == 0` (unknown/`null` on old relays → keep sending, so this is
backwards compatible). Viewer side likewise renders nothing while its tab is
hidden. Net effect: an idle session costs the phone only its local capture
loop — no encode, no uplink, no render.

### 5.2 Pose stream (v1.2 — aesthetic device mockup)

While watched, the phone also streams its physical pose so the web
viewer can lean a 3D device frame with the real hardware (flip it, slant it —
the mockup follows):

```jsonc
// Device → relay → viewer (relayed latest-only, like frames)
{ "type": "orientation", "azimuth": 12.5, "pitch": -73.2, "roll": 4.1,
  "rotation": 0, "ts": 1710000000000 }
```

Source: `sensors/OrientationReporter` — `TYPE_ROTATION_VECTOR` (never the
deprecated `TYPE_ORIENTATION`) → `getRotationMatrixFromVector()` →
`remapCoordinateSystem()` for the current display rotation →
`getOrientation()`, per the Android position-sensor guide. Degrees, no
permission required. Budget: own thread at `SENSOR_DELAY_UI`, ≥ 250 ms apart,
≥ 1.5° of movement, and samples only leave the phone while `viewers > 0`
(`null`/unknown relay → keep sending, same policy as frames). Purely
presentational — input coordinates stay normalized, so control is unaffected.

### 5.3 QR pairing (v1.3)

Typing a LAN URL by hand is the top pairing failure. The web viewer renders
one QR per phone; the phone scans it instead:

```
axie-remote://pair?v=1&url=<urlencoded relay URL>&token=<urlencoded token>&name=<label>
```

- `pairing/PairingPrefs.parsePairUri()` is total: wrong scheme/host/version,
  unnormalizable URL, or blank token → null, never partial state. Saved keys
  (`server_url`, `device_token`) are shared with MainActivity — one source.
- `scan/ScanActivity` (zxing, offline, no Play Services): continuous scan,
  first valid code persists + `RESULT_OK`; stray barcodes toast and keep
  scanning. CAMERA permission with the standard educate→ask flow; denial keeps
  manual pairing. Torch toggle for dark rooms. URL/token normalization is
  shared with the viewer (scheme case-insensitive on both sides).

## 6. Streaming phases (ship incrementally)

- **Phase 0 — skeleton (this repo state after scaffold):** consent screen + foreground service +
  `VirtualDisplay` to a `SurfaceView` preview + fake input echo. Proves permissions/lifecycle, no network.
- **Phase 1 — MJPEG over WebSocket (v1 shippable):** `ImageReader.newInstance(w,h,RGBA_8888,2)` →
  JPEG encode → base64 → `frame` messages. Viewer = `<img>` swapping `data:` URLs. ~200 lines, no NDK.
- **Phase 2 — H.264 over WebSocket:** `MediaCodec` AVC encoder surface as VirtualDisplay target;
  annex-B → fragmented MP4 or raw NAL relay; viewer uses MSE/`MediaSource`. 3–5× bandwidth win.
- **Phase 3 — WebRTC:** replace socket video with `ScreenCapturerAndroid` + `PeerConnection`
  (see Context7 `/getstream/webrtc-android`); keep the WS only for signaling + input. Sub-second,
  adaptive bitrate. Needs TURN for non-LAN.

## 7. Project layout (target)

```
mobile/
  SPEC.md                  ← this file
  SKILL.md                 ← agent/human operating manual
  settings.gradle.kts      ← root project "axie-remote"
  build.gradle.kts         ← plugin versions (AGP, Kotlin, Compose)
  gradle.properties        ← android.useAndroidX, kotlin.code.style
  gradle/wrapper/…         ← gradle-8.7 wrapper (no global install needed)
  gradlew*                 ← entry point (./gradlew assembleDebug)
  local.properties         ← sdk.dir=<SDK> (generated, git-ignored)
  app/
    build.gradle.kts       ← applicationId, min/targetSdk, Compose, OkHttp
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      java/com/axie/remote/… (MainActivity, capture/, control/, net/, ui/)
      res/xml/accessibilityservice.xml · values/ · …
  scripts/                 ← doctor.sh, install-apk.sh, fetch-context7-docs.sh
  .gitignore
```

`applicationId` (v1): `com.axie.remote` · `versionCode 1`, `versionName "0.1.0"`.

## 8. Milestones & acceptance

- [ ] **M0 scaffold:** `./gradlew assembleDebug` on Linux produces `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **M1 consent+preview:** install on device, grant screen-capture, see live mirror in-app; revoking
      in Quick Settings stops cleanly (no crash, service released).
- [ ] **M2 accessibility:** enable service in Settings, "Send test tap" button dispatches a visible tap;
      back/home buttons work from inside the app.
- [ ] **M3 stream:** two phones or phone+browser on LAN: viewer sees 720p ≥ 2 fps, latency < 1 s;
      viewer tap produces a tap on the device.
- [ ] **M4 hardened:** reconnect on network drop, rotation-safe scaling, release APK signed, README install steps.

## 9. Risks & Play-policy notes

- MediaProjection shows a **system dialog every session** (no silent capture) — by design.
- Accessibility APIs are restricted on Play (declaration + justification); side-load avoids this for
  internal use, but never ship to Play without the disclosure flow.
- OEM battery optimizers kill foreground services — guide the user to disable optimization for the app.
- Expect 300–800 ms LAN latency on MJPEG; WAN needs Phase 3 + TURN.
- Never log or persist screen frames; TLS (`wss://`) mandatory outside LAN.

## 10. Context7 usage (mandatory for Android API work)

Root `.env` holds `CONTEXT7_API_KEY`. Researched libraries for this spec:

- `/websites/developer_android_media` — MediaProjection start + `createVirtualDisplay` snippets.
- `/websites/developer_android_guide` — Accessibility gestures/global actions + FGS types.
- `/websites/developer_android_reference` — `AccessibilityService.takeScreenshot*`, FGS permissions.
- `/getstream/webrtc-android` — `ScreenCapturerAndroid`, `PeerConnectionFactory`, renderers.

Refresh with `scripts/fetch-context7-docs.sh` (writes to `/tmp`, never commits the key).
REST shape: `GET https://context7.com/api/v2/context?libraryId=<id>&query=<q>&tokens=<n>`
with `Authorization: Bearer $CONTEXT7_API_KEY` (resolve ids via `GET /api/v1/search?query=`).
