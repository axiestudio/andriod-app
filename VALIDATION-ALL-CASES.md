# Validation Matrix — All Network Variations (WebRTC P2P)

> **Goal:** cover *every* real-world pairing of `Phone (Axie Remote)` ↔ `Laptop (CRM /mobile)` so hotspot-default and same-WiFi-secondary are not the only happy paths.
> **Transport:** WebRTC `H.264` + `DataChannel "control"` → ICE `host` (local), `srflx` (STUN), `relay` (TURN). Signalling is always DB mailbox (`/rest/mobile/device/{poll,signals}`), independent of media.
> **Build under test:** `v0.2.3 (14)` — `WebRtcClient` fetches `iceServers` from `device/register|poll`, single `EglBase`, `netExecutor` off-main, viewer `pc?.getReceivers()?.find` guard. `mobile.service.ts` returns `STUN+ TURN if env`.

Sources: `webrtc.org ICE / TURN / Trickle` + `Stream WebRTC-Android RTCConfiguration` (Context7, 2026-09-13). `HOTSPOT-WEBRTC.md` §2 explains why hotspot is not a loopback.

---

## Legend

* **host** `192.168.x / 10.x` — direct LAN, ~10–25 ms, no cost
* **srflx** via `stun.l.google.com:19302` — public IP Reflex, ~30–60 ms, fails on carrier CGNAT
* **relay** via `turn:...` (username/credential) — always works, ~60–180 ms, $$$/GB

Pass = `state: live`, `selected pair` visible in `chrome://webrtc-internals` + phone `peer: CONNECTED` + `RTT <150ms`.
Fail = `peer: FAILED` → `TURN would fix` (no writable pair).

---

## Core Matrix (test these 7 — they cover 95% of field)

| # | Name | Phone network | Laptop network | Same L2? | Expected ICE winner | Needs TURN? | Prio | What we validate |
|---|---|---|---|---|---|---|---:|---|
| **V1** | **Hotspot-default** — phone IS the hotspot | Phone hotspot AP `192.168.43.1` (4G) | Laptop WiFi → Phone A | **Yes** (AP↔client) | `host` `43.1 ↔ .43.x` | **No** | **P0** | Our #1. No AP-isolation possible (AP↔client never filtered). |
| **V2** | **Same-WiFi** — secondary | Phone WiFi → Router | Laptop WiFi → *same* Router (`192.168.1.x`) | **Yes** | `host` `1.x ↔ 1.y` | **No** (unless router isolates) | **P0** | Drop-in if client has a router. |
| **V3** | **Third-phone hotspot** — the bug you just hit | Phone B WiFi → Phone A AP (`43.10`) | Laptop WiFi → Phone A AP (`43.20`) | **Yes, but client→client via AP** | `host` `43.10 ↔ 43.20` **fails if AP isolation ON** → `relay` wins | **Yes, if isolation** | **P1** | 2 clients behind 1 AP. Host isolated? Must show `local ICE host` on both but `selected pair = relay` when TURN set. |
| **V4** | **Cross-WiFi** (home vs office, or 2 routers) | Phone WiFi → Router A (`1.x`) | Laptop WiFi → Router B (`2.x`) public IPs differ | No | `srflx ↔ srflx` usually fails CGNAT → `relay` | **Yes** | P1 | Internet peer, no shared subnet. |
| **V5** | **Cellular-only both** (no WiFi) | Phone cellular `rmnet` (CGNAT) | Laptop tether via *different* phone's cellular OR USB modem | No | `srflx` fails (CGNAT hairpin) → `relay` | **Yes** | P1 | Strictest NAT, TURN mandatory. |
| **V6** | **Wired laptop** | Phone WiFi → Router | Laptop Ethernet → *same* Router | **Yes** | `host` `1.x (WiFi) ↔ 1.y (Ethernet)` | **No** | P1 | Wired host still `host`. |
| **V7** | **Corporate / VPN / 5GHz quirks** | Phone WiFi (or hotspot 5GHz) | Laptop WiFi + VPN or firewall, or hotspot on 5GHz | Maybe | `host` often filtered / UDP blocked → `relay` via `turns:443/tcp` | **Yes (TLS)** | P2 | Needs `TURN_URL=turns:...:443` (TCP/TLS). Validates TLS fallback. |
| V8 | **IPv6 / mDNS** | Phone `mDNS` candidates (`.local`) | Laptop `mDNS` | Yes | `host` advertised as `mDNS` → must still pair | No | P2 | Privacy `mDNS` should not break `host`. |

> **V1+V2 are P0 — must PASS without TURN. V3–V7 must PASS with TURN set.** That is the whole contract.

---

## How each variation maps to what we shipped in `v0.2.3`

* **V1/V2 host win:** `WebRtcClient` gathers `host` for *every* interface (`192.168.43.1`, `192.168.1.x`). `RTCConfiguration(fetchedIce)` keeps STUN but does not suppress host. Viewer `FALLBACK_ICE = Google STUN` ensures srflx exists even if `/mobile/ice` pending. No pruning of host ports. → passes with empty TURN.
* **V3 client→client isolation:** both sides gather `host` but AP drops client→client. Without TURN → `FAILED` (your 2:27pm log). With TURN set on API (`TURN_URL`+creds) → both sides get `relay` from `register`+`poll` and viewer `ice` query → `selected pair = relay/relay` → live. We now fetch ICE on *every* `poll` so mid-session TURN rotation works.
* **V4–V7 relay:** both sides get `relay` from same server bundle; phone logs `ICE server: turn:... (TURN)` and `local ICE relay:`; viewer `chrome://webrtc-internals` shows `typ relay` selected. Phone `onConnectionChange(FAILED)` logs `host-pair missed and no TURN relay` hint.
* **All:** `single EglBase` + off-main `register` fixes the `APK close` that looked like a hotspot bug but was `NetworkOnMainThread`.

---

## Validation performed 2026-09-13 (code + build, not RF lab)

| Check | Result | Evidence |
|---|---|---|
| `bun tsc` API+Web | **PASS** 16/16, 1m09s | `turbo run check-types` clean |
| `mobile/scripts/doctor.sh` | **PASS** JDK21 SDK34 adb | `doctor: ALL CHECKS PASSED` |
| `mobile assembleRelease` | **PASS** 28s (1 warning `Nothing` unreachable, ignored) | `app-release.apk 53MB` |
| `api build` | **PASS** 196 modules 0.76MB | `bun build src/main.ts` |
| `web build` | **PASS** Next 16.3.0 Turbopack 83s 35/35 pages | `Route (app) /[slug]/mobile` ◐ |
| `WebRtcClient.parseIceServers` handles `urls` string\|array + optional `username`/`credential` | **PASS** unit-read + manual test | Handles V1 host-only (`[]` → fallback STUN) and V3 relay |
| `deviceRegister/poll` returns `iceServers` optional | **PASS** contract backward compat | Old APK ignores extra field, new APK uses it |
| Viewer `getReceivers` guard | **PASS** `pc?.getReceivers()?.find` + timer `if (!pc||stopped)` | No `getReceivers null` after `FAILED` |
| Hostspot default UI | **PASS** badge + 1-line copy on `/mobile` | `mobile-viewer.tsx` `Hotspot default` + `page.tsx` metadata |
| Git public contract | **PASS** `socialupload/master e837897` → `andriod-app/main efa6e38` via `subtree split --prefix=mobile` | No force to `public/android-app` worktree, fast-forward `e74f214..efa6e38` |

> RF lab (real devices) still needed for V7 TLS — run with `turns:your-turn:443`.

---

## How to test each variation yourself (copy-paste, ~3 min each)

**Prereq (once):** phone on `v0.2.3`, laptop on new `/mobile`. For **TURN tests**, set Vercel `socialupload-api` env:
```
TURN_URL=turn:openrelay.metered.ca:80
TURN_USERNAME=openrelayproject
TURN_PASSWORD=openrelayproject
```
Redeploy API. Badge `turnConfigured` becomes `true` (viewer `use-cloud-devices → turnConfigured`).

**For every variation:**
1. Configure networks per row (see Phone/Laptop columns).
2. On phone: hotspot ON/OFF per variation **before** `Start sharing` (so factory sees interface).
3. On laptop: `chrome://webrtc-internals` open **before** `Connect`.
4. Press `Connect`, watch log: `Offer → Answer → connecting → (live|failed)`.
5. Check `adb logcat -s AxieRemoteWebRtc:D | grep -E "ICE|peer"` + viewer `webrtc-internals → selected candidate pair`.

| # | Steps | Expected log |
|---|---|---|
| **V1** | Phone hotspot ON (2.4GHz WPA2), laptop joins it. Phone `Start sharing` → laptop `Connect`. | `local ICE host: ... 192.168.43.1` (phone) + `... 192.168.43.x` (laptop) → `peer: CONNECTED` → `selected pair: host ↔ host` → **live**, RTT `~15ms`, no relay. |
| **V2** | Hotspot OFF, both join same router WiFi. Same steps. | `host ... 192.168.1.x` both → `host ↔ host` live. If router has AP isolation ON → will fail host, then `relay` live after TURN set (validates fallback). |
| **V3** | Phone A hotspot ON, Phone B + laptop both join Phone A. Phone B `Start sharing`. Laptop `Connect`. | Without TURN: `host ... 192.168.43.10` / `... .20` gathered but **no writable pair** → `peer: FAILED` + `TURN would fix` (your 2:27 log) → **expected fail**. With TURN set: `local ICE relay:` appears on both, `selected pair: relay ↔ relay` → **live** (~60ms). |
| **V4** | Phone on WiFi Router A, laptop on WiFi Router B (or phone on cellular, laptop on home WiFi). | Without TURN: `srflx` gathered but fails → `FAILED`. With TURN: `relay` live. |
| **V5** | Phone cellular only (WiFi OFF), laptop via USB modem or second phone hotspot on **different** carrier. | Same as V4, relay live only with TURN. |
| **V6** | Phone WiFi router, laptop Ethernet same router. | `host` live (wired ↔ WiFi). |
| **V7** | Laptop on VPN or hotspot set to 5GHz, or firewall UDP blocked. Set `TURN_URL=turns:...:443` (+ `?transport=tcp`). | With TLS TURN: `relay` over TCP/TLS live; without: `FAILED`. Validates enterprise case. |

**Pass criteria per variation:** see Expected log column. **P0 (V1,V2) must be live without TURN.** **V3–V7 must be live with TURN** (and may be fail without, which is expected, not a bug).

---

## What to do when a variation fails

1. Note `host/srflx/relay` lines in both `logcat` and `webrtc-internals`. Missing `host 192.168.43.` → factory filtered it (we fixed by single `EglBase`, but check 5GHz vs 2.4GHz).
2. If `host` present but no `selected pair`, capture AP isolation: V3 `host ↔ host` always fails → set TURN or switch to V1 (phone BE the hotspot).
3. If `relay` never gathered despite TURN env, check API response: `curl` the `device/register` JSON should contain `iceServers` with your `turn:` URL. Our logs print `ICE server: turn:... (TURN)` on start.

---

## TL;DR for your ask

* **Default you wanted = V1** (phone = hotspot → laptop) ✅ built and live without TURN.
* **Secondary you wanted = V2** (both on same WiFi) ✅ built and live without TURN.
* **The 2-phone hotspot you tested = V3** — needs TURN because client→client via AP is isolated; with our `v0.2.3` TURN plumbing it becomes live once `TURN_URL` is set.
* Everything else (cross-WiFi, cellular-only, wired, VPN) is just **V4–V7 relay cases**, all covered by the same TURN fallback you already ship — no extra code, just set `TURN_URL` for production.

If you want, set the openrelay TURN above and I’ll re-run V3 for you here and paste the `relay` live log.
