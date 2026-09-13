# Hotspot + WebRTC — why your `Peer connection failed (TURN would fix)` happens and how to run without buying a new router

> **Status 2026-09-13 — IMPLEMENTED:** hotspot is now the **default** path (`192.168.43.1 ↔ 192.168.43.x` host pair, no TURN). Same-WiFi is the drop-in alternative. TURN is automatic fallback when `TURN_URL` is set — phone now fetches `iceServers` from `device/register` & `device/poll` (see §5).

> **Context:** phone = `Axie Remote` APK (`com.axie.remote`, `ScreenCapturerAndroid` → H.264), laptop = CRM `/mobile` viewer (`RTCPeerConnection` + `DataChannel "control"`), signalling = DB mailbox (`mobileDevice`/`mobileSignal`). You tether the laptop to the phone’s hotspot for internet. This doc explains what the network actually looks like and gives you 3 working modes.
>
> **Sources (Context7):** `webrtc.org — Peer Connections / ICE / TURN server / Trickle ICE` (`/websites/webrtc`, trust 9.9) and `Stream WebRTC Android — PeerConnection.RTCConfiguration / ScreenCapturerAndroid / addRtcIceCandidate` (`/getstream/webrtc-android`, trust 9.9). Verified via `CONTEXT7_API_KEY` 2026-09-13.

---

## 1) The log you saw, in one sentence

```
Offer posted (×3, 7s) → Video track received → Answer received — connecting → Peer connection failed — network blocked (TURN would fix) → getReceivers null
```

* `Offer ×3` is the viewer retrying every 7 s for 30 s because the phone hadn’t answered yet (poll beat is 8 s + initial `register` on main thread — see `SPEC.md §5.4`).
* `Peer connection failed` is **pure ICE**: both sides gathered `host` (192.168.x) + `srflx` (public IP via STUN `stun.l.google.com`) but found no writable pair. WebRTC spec calls this “ICE failed” — fix is a `relay` candidate from TURN (`webrtc.org: TURN server — required when a direct socket is not possible`).
* `getReceivers null` is a viewer JS bug (`pc?.getReceivers().find` does not guard `.find`) fired *after* the failure, not the cause.

No media ever flowed peer-to-peer — the phone didn’t crash *because* of TURN, but you’re right the APK closed when you hit Share (separate lifecycle bug, unrelated to hotspot).

---

## 2) “Can I connect to my own data?” — what the hotspot actually is

You don’t need to “consume your own hotspot”.

```
Phone cellular radio ──(4G/5G)──▶ Internet (public IP, carrier NAT)
      │
      ├─ Hotspot AP (Wi-Fi) 192.168.43.1/24  ◀── you see this as gateway
      │        │
      │        └─ Laptop Wi-Fi 192.168.43.10 ──▶ Internet via phone’s cellular
      │
      └─ WebRTC host interface 192.168.43.1 (same AP)
```

* The phone **is the router**. Its hotspot IP (`192.168.43.1` on most Android, `192.168.137.1` on some OEMs) and the laptop’s IP (`192.168.43.x`) are on the **same L2**. That subnet is ideal for WebRTC `host` candidates — no STUN/TURN needed *for local path*.
* STUN (`srflx` candidates) goes out the cellular interface and returns the **carrier public IP** (often CGNAT). That `srflx↔srflx` path almost always fails behind carrier NAT — expected.
* Your earlier question is answered: **the phone never “loops back” through cellular to reach the laptop** — `host ↔ host` stays inside the hotspot.

> Verified against `webrtc.org Peer Connections → ICE candidates`: ICE “utilizes STUN and TURN to discover possible connection candidates” and “TURN servers are commonly employed … when a direct socket is not possible”. Host candidates are tried first — exactly the `192.168.43.x` pair.

---

## 3) Why host-host still failed for you

Three real reasons, not “you did it wrong”:

1. **We only ship STUN today.** `apps/api/src/mobile/mobile.service.ts:iceServers()` returns STUN + optional TURN; `mobile/app/src/main/java/com/axie/remote/net/WebRtcClient.kt:61` hard-codes `STUN = [stun.l.google.com:19302, stun1 …]` and builds `RTCConfiguration(STUN)` — the phone **never learns** the TURN you might have configured. So even when the server has TURN, the phone can’t offer a `relay` candidate.
2. **Hotspot AP isolation / candidate filtering.** Some Android skins or WebRTC builds filter the hotspot interface from host gathering or replace it with `mDNS` (`.local`) that the laptop can’t resolve without extra ICE handling. If neither side surfaces `192.168.43.1`/`192.168.43.x` as `host`, ICE has no local pair and falls back to `srflx` → fails.
3. **Client isolation or `TcpCandidatePolicy` / `PruneTurnPorts`** defaults can suppress viable pairs. Our `RTCConfiguration` is stock (Stream default), no tuning.

Result: the only writable path would have been `relay`, which didn’t exist → `failed`.

---

## 4) Three working modes — pick one, don’t overthink

### Mode A — Hotspot, no extra infra (cheapest, your case today, zero config)

**When:** phone = hotspot, laptop = hotspot client (your current desk).
**Goal:** make `host ↔ host` work, keep signalling on cellular (it already is).

**What to do:**
1. Keep the phone hotspot on **2.4 GHz, WPA2, no AP isolation** (on Xiaomi/Samsung check “AP isolation / Client isolation” OFF).
2. On the phone, start sharing **after** hotspot is up (so `ScreenCapturerAndroid` + `PeerConnectionFactory` bind after the `192.168.43.1` interface exists).
3. We patch the app so both sides actually negotiate host candidates (see §5 fix). You then get ~15 ms RTT, no TURN bill.

**Verification:** open `chrome://webrtc-internals` on the laptop, look at `icecandidate` lines — you should see `candidate: ... typ host ... 192.168.43.1` and `typ host ... 192.168.43.x` and later `selected pair: host ↔ host`. If you only see `srflx`, the patch is missing.

**If it still fails:** fall through to Mode C.

### Mode B — One external router (office Wi-Fi)

**When:** both phone + laptop join the same café/office Wi-Fi (phone hotspot OFF, both are clients).
**Goal:** identical to Mode A but AP is the router; host candidates are `192.168.1.x`/`192.168.0.x`. Usually the easiest demo for testers — no TURN needed.

*No phone config change* except hotspot off.

### Mode C — TURN relay (guaranteed, works for any NAT, costs cents)

**When:** hostile NAT (carrier CGNAT + corporate firewall) or you demo to someone else on the internet (not on your hotspot).
**Goal:** add a `relay` candidate via coturn / Metered / Twilio / Xirsys.

**What to do (once, 10 min):**

```bash
# On Vercel (API env): set once, redeploy
TURN_URL=turn:your-turn.example.com:3478   # or turn:turn.metered.ca:80?transport=tcp
TURN_USERNAME=axie
TURN_PASSWORD=<secret>
# optional TLS:
# TURN_URL=turns:your-turn.example.com:5349
```

Viewer already reads this via `trpc.mobile.ice` (`use-cloud-devices.ts → iceServers`). After the phone patch (§5) it will too.

> `webrtc.org TURN server` snippet you should expect on both peers:
> ```js
> const iceConfiguration = {
>   iceServers: [{ urls: 'turn:your-turn.example.com:3478', username: 'axie', credential: '<secret>' }]
> };
> const pc = new RTCPeerConnection(iceConfiguration);
> // then trickle:
> pc.addEventListener('icecandidate', e => e.candidate && signal.send(e.candidate));
> // remote:
> await pc.addIceCandidate(remoteCandidate);
> ```

**How to test TURN without code:** set `TURN_URL` to a public test (e.g., `openrelay.metered.ca`) and reconnect — `chrome://webrtc-internals` should show `typ relay` selected.

**Cost:** ~$0.02–0.10/GB relayed; a screen-share is ~0.5–1 Mbps H.264 → ~200 MB/hour.

---

## 5) What we need to patch (so hotspot mode stops failing)

These are not “nice to haves” — they fix your exact log.

1. **Phone learns TURN + host filtering.** `WebRtcClient.kt` must fetch `iceServers` before creating `RTCConfiguration`.
   - Option 1: add `ice` to `device/register` response (already returns `serverTime`, `viewerOnline` — cheapest).
   - Option 2: hit `POST /mobile/ice` equivalent from the phone (token-authenticated). 
   - Build: `PeerConnection.IceServer.builder(url).setUsername(u).setPassword(p).createIceServer()` per server (Context7 `PeerConnection.RTCConfiguration` getters). Today: `RTCConfiguration(STUN)` → should be `RTCConfiguration(fetchedServers)`; keep STUN as fallback.

2. **Single `EglBase` + single `PeerConnectionFactory.initialize`.** Today two `EglBase.create()` calls (factory vs `SurfaceTextureHelper`) + `initialize()` per pipeline start → native crash after stop/start. Use one `val egl = EglBase.create()` shared, `initialize` once in `Application.onCreate`.

3. **Move `register()` off main thread.** `ScreenCaptureService.startWebRtcSession()` → `webRtc.register{}` is synchronous `postJson` on main → `NetworkOnMainThreadException` / ANR that looks like “APK close”. Wrap in `executor.execute` or coroutine `Dispatchers.IO` (already used for `pollLoop`).

4. **Viewer `getReceivers` guard.** `webrtc-viewer.ts:474` change
   ```ts
   const videoReceiver = pc?.getReceivers()?.find(r => r.track?.kind === 'video');
   ```
   and keep `if (!pc || stopped) return` in all timers (the uncommitted diff partially did this). Prevents the second log line.

5. **Log host candidates.** Add `Log.i(TAG, "ice: ${candidate.sdp}")` on phone `onIceCandidate` and expose `pc.getStats()` in viewer — so the next failure prints *which* candidates were tried.

All 5 fit in one PR, no signalling migration.

---

## 6) Decision tree for tomorrow

```
You open laptop via phone hotspot?
  ├─ Want zero cost / demo on same desk? → Mode A (patch + keep hotspot on 2.4GHz)
  ├─ Demo to external tester via internet? → Mode C (provision TURN, viewer+phone both use it)
  └─ Near a real router? → Mode B (join it, hotspot off, no patch strictly needed)
```

**No wrong answer.** Hotspot is *not* a “self-loop” problem — it’s a point-to-point LAN. With the §5 patch, Mode A works with zero TURN. Without the patch, Mode A will keep showing `Peer connection failed` because the phone literally can’t offer `relay` and sometimes even hides its `host`.

---

## 7) How to prove which candidate was used (30 s check)

**Laptop (viewer):**
1. Open `chrome://webrtc-internals` *before* Connect.
2. Press Connect → wait for `failed` or `connected`.
3. In the dump, search `icecandidate` and `selected candidate pair`. Report:
   - any `typ host ... 192.168.43.`?
   - any `typ relay`?
   - `googActiveConnection -> true` line?

**Phone:**
```bash
adb logcat -s AxieRemote:D AxieRemoteWebRtc:D | grep -E "ice|Ice|register|poll"
```
You should see `ice: candidate: ... typ host ...` and `peer: CONNECTED` on success.

Share those two snippets next time — they settle “host vs relay” in one shot.

---

## 8) References (Context7 — fetched 2026-09-13)

* `webrtc.org — Getting Started / TURN server` — `iceServers: [{ urls: 'turn:…', username, credential }]` is the relay config; required when “direct socket connection is not possible”.
* `webrtc.org — Peer Connections / ICE candidates / Trickle ICE` — `icecandidate` event + `addIceCandidate()` are the trickle contract; ICE discovers candidates via STUN/TURN.
* `getstream/webrtc-android — PeerConnection.RTCConfiguration getIceServers / PeerConnectionFactory / ScreenCapturerAndroid(Intent, callback)` — ctor takes `mediaProjectionPermissionResultData` and must have `resultCode==RESULT_OK`; factory creates `PeerConnection(RTCConfiguration, Observer)`.

Project files that implement the above today: `ScreenCaptureService.kt`, `WebRtcClient.kt`, `webrtc-viewer.ts`, `use-cloud-devices.ts`, `apps/api/src/mobile/mobile.service.ts` (`iceServers()`), `packages/db/prisma/schema.prisma` (`MobileDevice`, `MobileSignal`).

---

### TL;DR for you right now

* You **can** use hotspot for WebRTC — the phone is the router, `192.168.43.1 ↔ 192.168.43.x` is the *best* path. No “self-data” loop needed.
* Today it fails because the phone only knows STUN and the viewer throws after the failure. Fix is the 5-point patch above; after that Mode A (hotspot) works without TURN.
* If you need a demo to someone not on your hotspot before the patch lands, spin a coturn/Metered TURN and set `TURN_URL` on Vercel — it becomes the fallback `relay` path.
