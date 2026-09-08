# Axie Remote — Android screen stream + control

Native Android companion app (`com.axie.remote`): streams the phone screen via
**MediaProjection** and injects remote input (tap, swipe, back/home, typing) via an
**AccessibilityService**. No root required. Ships as a single side-loadable APK.

> Product brief: [`goal.md`](./goal.md) · Implementation spec: [`SPEC.md`](./SPEC.md) ·
> Agent/human manual: [`SKILL.md`](./SKILL.md)

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

1. Open **Axie Remote** → **Start sharing** → accept the system screen-cast dialog.
2. Settings → Accessibility → enable **Axie Control** (required for input).
3. Test buttons: tap-center, Back, Home. `adb logcat -s AxieRemote:D` shows frame counts.
4. Stop anytime in-app, or revoke via the system screen-share chip.

## Protocol

The app speaks the JSON-over-WebSocket protocol in [`SPEC.md`](./SPEC.md) §5
(`hello` / `frame` / `tap` / `swipe` / `key` / `text`). Point it at a relay to drive
it from a web viewer — the viewer side lives outside this repo.
