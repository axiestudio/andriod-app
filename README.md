# Axie Remote — Android screen stream + control

Native Android companion app (`com.axie.remote`): streams the phone screen via
**MediaProjection** and injects remote input (tap, swipe, back/home, typing) via an
**AccessibilityService**. No root required. Ships as a single side-loadable APK.

> Product brief: [`goal.md`](./goal.md) · Implementation spec: [`SPEC.md`](./SPEC.md) ·
> Agent/human manual: [`SKILL.md`](./SKILL.md)

## Download (no build needed)

Signed release APK, installable on any Android 10+ phone:

[`releases/AxieRemote-v0.1.3-release.apk`](./releases/AxieRemote-v0.1.3-release.apk)

On your phone: open that link → download → tap the file → *Install anyway*
(Play Protect warns on all side-loaded apps). Then follow *Use* below.

After that, the app updates itself: the Updates tile checks
[`releases/latest.json`](./releases/latest.json) for a newer signed build and
installs it on-device (you confirm each install; enable *Install unknown apps*
for Axie Remote once when asked).

## Requirements (Linux build host)

- JDK 17+, Android SDK with `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
- First build needs network (downloads Gradle/AGP deps); afterwards `--offline` works

## Build

```bash
./scripts/doctor.sh                 # verify toolchain (writes local.properties)
./gradlew assembleDebug --console=plain
# → app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
./scripts/install-apk.sh            # adb install -r (USB debugging authorized)
# or copy app-debug.apk to the phone and side-load it
```

## Use (on device)

1. **Fastest:** open the viewer `/mobile` page → Phones → QR, then in the app tap
   **Scan QR** and point at the code (relay URL + token fill in by themselves).
   Or type the pair by hand: open **Axie Remote** → **Start sharing** → accept the system screen-cast dialog.
2. Settings → Accessibility → enable **Axie Control** (required for input).
3. Test buttons: tap-center, Back, Home. `adb logcat -s AxieRemote:D` shows frame counts.
   The sharing tile also shows a live `↑ frames · watchers · pose` line while sharing.
4. Stop anytime in-app, or revoke via the system screen-share chip.

## Protocol

The app speaks the JSON-over-WebSocket protocol in [`SPEC.md`](./SPEC.md) §5
(`hello` / `frame` / `tap` / `swipe` / `key` / `text`). Point it at a relay to drive
it from a web viewer — the viewer side lives in the console app:

- Relay: `apps/console/scripts/device-relay.mjs` (`bun run relay` from `apps/console`)
- Viewer: `/mobile` page (Carbon UI) — same relay URL + device token as the
  phone's pairing screen. See `apps/console/SPEC.md` §13.
