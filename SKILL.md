# SKILL.md — Axie Remote Android app (`mobile/`)

Operating manual for any agent/human working in this directory. Read `SPEC.md` first —
it is the source of truth for goals, architecture, and protocol. This file is the
hands-on procedure: environment, commands, docs lookup, and iteration loop.

## 1. When to use this skill

Use it whenever the task touches `mobile/` — building the APK, editing Kotlin/manifest
code, adding streaming or control features, debugging on-device, or answering Android
API questions. Outside `mobile/`, ignore this file.

## 2. Environment (Linux)

- OS: Ubuntu 24.04 · JDK 21 (system) — Gradle runs with its own toolchain; AGP 8.5 works on 17+.
- SDK location (standard): `~/Android/Sdk` — `local.properties` points at it (`sdk.dir=…`).
  Never commit `local.properties`.
- Key binaries: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`,
  `~/Android/Sdk/platform-tools/adb`. Add platform-tools to PATH per-shell, don't rely on global installs.
- Required SDK packages: `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
  Accept licenses once: `yes | sdkmanager --licenses`.
- Network: Gradle downloads AGP/Kotlin/Compose from `dl.google.com` + `repo.maven.apache.org`
  on first build (~500 MB–1 GB). Re-runs are offline-capable (`--offline`).

## 3. Commands (run from `mobile/`)

```bash
./scripts/doctor.sh                 # verify JDK + SDK + packages (run first when unsure)
./gradlew assembleDebug --console=plain        # build installable APK
./gradlew assembleDebug --offline --console=plain  # no network
./scripts/install-apk.sh            # adb install -r app-debug.apk (device must authorize)
adb logcat -s AxieRemote:D         # watch app logs (tag AxieRemote)
adb shell dumpsys activity services | grep -i axie  # is the FGS alive?
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. Install: `adb install -r` it,
or copy to the phone and side-load. Debug APK is already signed (debug key) — installable as-is.

Release (distributable) APK: `./gradlew assembleRelease` →
`app/build/outputs/apk/release/app-release.apk`. It signs with the keystore in
`~/.keystores/axie-remote-release.jks` via `keystore.properties` (both local-only,
git-ignored, never committed). Back up the keystore + password: losing them means the
app can never be updated in place (Android pins updates to the signing cert).
Use one shared store/key password (PKCS12 can't do distinct key passwords reliably).

On-device checklist (every manual test):

1. Install, open **Axie Remote**, tap **Start sharing** → accept system screen-cast dialog.
2. Foreground notification "Axie Remote is sharing your screen" must appear ≤ 5 s.
3. Settings → Accessibility → **Axie Control** → enable (required for tap/back/home test buttons).
4. Revoke capture from Quick Settings → app must stop cleanly (see `MediaProjection.Callback.onStop`).

## 4. Code map (where things live)

- `app/src/main/AndroidManifest.xml` — permissions + both services. Touch with care (FGS type,
  `BIND_ACCESSIBILITY_SERVICE` are load-bearing).
- `MainActivity.kt` — consent launcher (`createScreenCaptureIntent`), start/stop FGS, deep-links.
- `capture/ScreenCaptureService.kt` — owns `MediaProjection`, `VirtualDisplay`, `Callback.onStop`.
  Streaming phases land here (Phase 1 `ImageReader`, Phase 2 `MediaCodec`, Phase 3 WebRTC).
- `control/ControlAccessibilityService.kt` — `dispatchGesture` taps/swipes, `performGlobalAction`
  keys, `ACTION_SET_TEXT` typing. Keep it stateless; input arrives via `SharedFlow`/broadcast.
- `net/SignalingClient.kt` — OkHttp WebSocket, JSON protocol per `SPEC.md §5`. Single connection.
- `sensors/OrientationReporter.kt` — rotation-vector pose stream for the viewer mockup
  (`SPEC.md §5.2`); throttled, own thread, only forwarded while watched.
- `pairing/PairingPrefs.kt` — `axie-remote://pair` URI codec + shared prefs keys (`SPEC.md §5.3`).
- `scan/ScanActivity.kt` — QR scanner (zxing, offline); valid code persists + `RESULT_OK`.
- `res/xml/accessibilityservice.xml` — `canPerformGestures="true"` (injecting input breaks without it).

Rules: no screen frames in logs or disk; coordinates normalized 0..1 until dispatch;
`VirtualDisplay`/`ImageReader`/`MediaProjection` always released in `onDestroy` + `onStop`.

## 5. Context7 — mandatory for Android API questions

Never guess Android API signatures. Root `.env` (repo root, NOT committed usages) holds
`CONTEXT7_API_KEY`. Key libraries and starter queries:

| Library ID | Use for | Example query |
|---|---|---|
| `/websites/developer_android_media` | MediaProjection, VirtualDisplay | `MediaProjection createVirtualDisplay screen capture streaming` |
| `/websites/developer_android_guide` | Accessibility gestures, FGS types | `AccessibilityService dispatchGesture performAction remote control` |
| `/websites/developer_android_reference` | Exact signatures, permissions | `MediaProjection.Callback foregroundServiceType mediaProjection` |
| `/getstream/webrtc-android` | Phase 3 WebRTC | `ScreenCapturerAndroid peer connection screen share` |

Procedure (key never leaves the shell — don't paste it into files/chats):

```bash
source ../.env  # exposes CONTEXT7_API_KEY (mobile/ → repo root is ..)
# 1. resolve: curl -s -H "Authorization: Bearer $CONTEXT7_API_KEY" \
#      "https://context7.com/api/v1/search?query=android+media+projection"
# 2. fetch:  curl -s -H "Authorization: Bearer $CONTEXT7_API_KEY" \
#      "https://context7.com/api/v2/context?libraryId=<id>&query=<q>&tokens=8000"
# shortcut: ./scripts/fetch-context7-docs.sh "<libraryId>" "<query>"
```

Prefer Context7 snippets over memory; if Context7 and local code disagree, trust a
successful local `assembleDebug` + on-device run, then update the spec.

## 6. Iteration loop

1. `SPEC.md §8` — pick the milestone; keep changes inside one phase (don't mix MJPEG + WebRTC).
2. Context7 fetch for any new API before coding.
3. Edit Kotlin/XML, `./gradlew assembleDebug`, fix errors, `install-apk.sh`, test on device with `logcat`.
4. Update `SPEC.md` if protocol/permissions/architecture changed; keep this file's commands accurate.

## 7. Troubleshooting

| Symptom | Cause → fix |
|---|---|
| `sdk.dir … does not exist` / `SDK location not found` | `local.properties` wrong — rerun `scripts/doctor.sh`, it regenerates from `~/Android/Sdk`. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION permission required` / `SecurityException` on `startForeground` | Android 14+: manifest permission missing or `foregroundServiceType` mismatch — see `SPEC.md §4`. |
| `assembleDebug` fails downloading dependencies | No network to Google/Maven — retry; first build needs internet. |
| Consent dialog denied / projection null | User cancelled — `getMediaProjection` only valid on `RESULT_OK`; re-launch intent. |
| Capture keeps running after revoke | `MediaProjection.Callback.onStop` not releasing `VirtualDisplay` — fix `ScreenCaptureService`. |
| `dispatchGesture` returns false / no tap | Accessibility service not enabled, or `canPerformGestures` missing — re-check XML + Settings. |
| `adb` shows `unauthorized` | Accept the on-phone RSA prompt, toggle USB debugging, `adb kill-server; adb start-server`. |
| Play Protect blocks side-load | Expected for accessibility + projection combo — "Install anyway" for internal builds. |
