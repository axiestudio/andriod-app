#!/usr/bin/env bash
# doctor.sh — verify the Linux toolchain for building the Axie Remote APK.
# Usage: ./scripts/doctor.sh   (run from mobile/ or anywhere; resolves itself)
set -euo pipefail

MOBILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DEFAULT="$HOME/Android/Sdk"
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { say "  [OK] $*"; }
bad()  { say "  [FAIL] $*"; FAIL=1; }

say "== Axie Remote doctor =="
say "mobile dir: $MOBILE_DIR"

# 1. Java
if command -v java >/dev/null 2>&1; then
  ok "java: $(java -version 2>&1 | head -1)"
else
  bad "java not found — install OpenJDK 17+: sudo apt install openjdk-17-jdk"
fi

# 2. SDK location: local.properties wins, else ~/Android/Sdk
SDK_DIR="$SDK_DEFAULT"
PROP="$MOBILE_DIR/local.properties"
if [ -f "$PROP" ]; then
  FROM_PROP="$(grep -E '^sdk\.dir=' "$PROP" | tail -1 | cut -d= -f2-)"
  [ -n "${FROM_PROP:-}" ] && SDK_DIR="$FROM_PROP"
fi
say "SDK dir: $SDK_DIR"
if [ -d "$SDK_DIR" ]; then
  ok "SDK directory exists"
else
  bad "SDK directory missing — reinstall cmdline-tools to $SDK_DEFAULT (see SPEC.md §10 / SKILL.md §2)"
fi

# 3. Required packages
for pkg in "platform-tools" "platforms/android-34" "build-tools/34.0.0" "cmdline-tools/latest"; do
  if [ -e "$SDK_DIR/$pkg" ]; then ok "$pkg"; else bad "$pkg missing — run: sdkmanager \"$pkg\""; fi
done

# 4. adb
if [ -x "$SDK_DIR/platform-tools/adb" ]; then
  ok "adb: $("$SDK_DIR/platform-tools/adb" version | head -1)"
else
  bad "adb not executable at $SDK_DIR/platform-tools/adb"
fi

# 5. local.properties pointer
if [ -f "$PROP" ] && grep -q "sdk.dir=$SDK_DIR" "$PROP" 2>/dev/null; then
  ok "local.properties points at SDK"
else
  say "  [INFO] (re)writing $PROP"
  printf 'sdk.dir=%s\n' "$SDK_DIR" > "$PROP"
  ok "local.properties written"
fi

# 6. Gradle wrapper
if [ -x "$MOBILE_DIR/gradlew" ]; then
  ok "gradlew present"
else
  bad "gradlew missing — run: gradle wrapper --gradle-version 8.7 (see SKILL.md)"
fi

if [ "$FAIL" -eq 0 ]; then
  say "doctor: ALL CHECKS PASSED"
else
  say "doctor: SOME CHECKS FAILED (see above)"
  exit 1
fi
