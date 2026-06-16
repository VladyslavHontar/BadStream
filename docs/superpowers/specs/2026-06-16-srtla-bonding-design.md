# SRTLA Bonding — Design

**Status:** approved design, pre-implementation
**Date:** 2026-06-16
**Goal:** Combine multiple network links (Wi‑Fi + cellular, opportunistically more) into one resilient SRT stream so a single link dropping or congesting does not interrupt the broadcast — the core "IRL anywhere" capability. Matches what Moblin does on iOS, using the BELABOX-compatible `srtla` protocol.

## Locked decisions

1. **Protocol: SRTLA, BELABOX-compatible.** Interops with the existing `srtla_rec` ecosystem; not a custom scheme.
2. **Implementation: clean-room C++** in our native core (no copyleft entanglement; integrates with libsrt + our `SrtSession`/ABR; BELABOX's `srtla` is a *reference to learn from*, not copied). Also necessary because BELABOX's sender binds by source-IP, which does not work on Android — we must do per-socket `Network` binding regardless.
3. **Receiver: configurable `srtla_rec`** via an `srtla://host:port?streamid=…` endpoint. Bonding requires a public-IP rendezvous (cellular cannot reach a home NAT), so the receiver is a VPS running `srtla_rec` (recommended self-host) or BELABOX cloud. The app is receiver-agnostic.
4. **Links: N-link generic, device-agnostic.** Target any Android 15+ device. Bond every usable `Network` the OS exposes — Wi‑Fi + cellular as the reliable baseline, a second cellular automatically on DSDA hardware, a USB-modem network if attached. No SIM/device-specific logic (DSDS phones simply never deliver a 2nd active cellular network; the code path is identical).

## Architecture

SRTLA is a transport layer **below** SRT. libsrt continues to run end-to-end SRT with the final receiver; SRTLA only spreads those SRT packets across links and the receiver recombines them. We achieve this **without modifying libsrt** via a loopback proxy:

```
encoder → MPEG-TS mux → libsrt SRT caller ──(SRT/UDP on 127.0.0.1:localPort)──┐
                                                                              ▼
                                                                     SrtlaSender (C++)
                                                                ┌──────┬──────┬──────┐  N UDP sockets,
                                                              link0  link1  link2     each bound to a Network
                                                                └──────┴──────┴──────┘
                                                                              ▼
                                              srtla_rec (VPS) → single SRT stream → OBS / Twitch
```

- For **plain SRT** (today): `SrtLink` connects libsrt to the remote host directly.
- For **bonded SRT**: `SrtLink` connects libsrt to `127.0.0.1:localPort`; `SrtlaSender` owns the real network sockets and the srtla protocol.
- Everything upstream of the transport tail — MPEG-TS mux, the keyframe-gated session, the reconnect loop — is **shared and unchanged**.

## Components

Each unit has one responsibility, a narrow interface, and is independently testable.

### 1. `SrtlaSender` (C++ core, NDK-only)
- **Does:** bridges the loopback SRT socket and N link sockets, implementing the srtla protocol.
  - **Registration:** REG1 (send a random group id on the first link) → REG2 (receiver completes the id) → REG3 (join every link to the group). All links of a stream share one group id so the receiver recombines them.
  - **Keepalives:** per-link periodic keepalive ⇄ echo, for RTT and liveness.
  - **Link selection:** tracks per-link in-flight (sent-but-unacked) vs a per-link window; sends each outbound SRT packet on the link with the most spare window. Window grows on clean delivery, shrinks on loss — per-link congestion control beneath SRT's end-to-end control.
  - **Return path:** forwards receiver→loopback packets (SRT ACK/NAK from the final receiver) back to libsrt so its own ARQ/congestion works over the aggregate path.
- **Interface:** `start(localPort, recHost, recPort, streamid)`, `addLink(fd)` / `removeLink(fd)` (fd = a UDP socket already bound to a `Network`), `stop()`, `stats()` (per-link + aggregate).
- **Depends on:** POSIX UDP sockets only. No libsrt, no Android — host-testable. The Android-specific socket binding happens before `addLink` (the fd arrives pre-bound).
- **Pure, unit-tested sub-pieces:** window/ACK accounting, link selection, registration state machine.

### 2. `BondingNetworks` (Kotlin)
- **Does:** discovers and maintains the set of usable networks. Issues a `ConnectivityManager.requestNetwork` per transport (Wi‑Fi, cellular, ethernet/USB); on each `onAvailable`, creates a `DatagramSocket`, binds it to that `Network` (`Network.bindSocket`), and registers its fd with `SrtlaSender.addLink`. On `onLost`, calls `removeLink`. Releases all requests on stop.
- **Interface:** `start(onAddLink, onRemoveLink)`, `stop()`, exposes the live link set for the UI.
- **Depends on:** `ConnectivityManager`; `CHANGE_NETWORK_STATE` + `ACCESS_NETWORK_STATE` permissions (to add to the manifest).

### 3. Config / endpoint
- New scheme `srtla://host:port?streamid=…` parsed by the existing `Endpoint`/`EndpointScheme` path. Choosing srtla implies bonding. Reuses the SRT settings (latency, streamid).
- `NativeSrtStreamer` (or a sibling) routes the SRT scheme to the loopback + `SrtlaSender` when the endpoint is `srtla://`.

### 4. UI — per-link status
- Per-link rows in the stream stats (each link: transport label, up/down, RTT, share of traffic) so bonding is visibly working. Folds into the existing `StreamStats`/health surface.

### 5. ABR integration
- The existing BELABOX-style ABR control law (`abr.cpp`) is unchanged. It is fed **aggregate** srtla health (summed spare window / throughput across live links) instead of a single link's `srt_bstats`. Encoder bitrate still adapts to total available capacity; per-link congestion is handled inside `SrtlaSender`.

## Data flow

- **Up:** encoder → mux → libsrt → loopback → `SrtlaSender` → chosen link → `srtla_rec` → final SRT receiver.
- **Down:** final receiver → `srtla_rec` → links → `SrtlaSender` → loopback → libsrt (SRT ACK/NAK).
- **Stats:** `SrtlaSender.stats()` → JNI → engine → ABR + UI.

## Error handling / resilience (the point of the feature)

- **One link drops** (Wi‑Fi off mid-walk): `SrtlaSender` stops scheduling on it (keepalive timeout / send error) and redistributes to survivors; `BondingNetworks` removes it on the `onLost` callback. **The stream continues on the remaining link(s) with no reconnect** — the headline win.
- **A dropped link returns / a new link appears:** registered into the group live and scheduling resumes on it.
- **All links gone:** behaves like a normal SRT disconnect → existing reconnect loop retries.
- **Receiver unreachable / registration fails:** retry with backoff, surfaced as the existing connecting/error states.

## Testing strategy

- **Host unit tests (GoogleTest, like the current core):** window/ACK accounting, link selection under varied per-link windows, registration state machine, keepalive timeout → link-down.
- **Integration:** build the reference `srtla_rec` on the dev Mac; stream phone→Mac on Wi‑Fi (single link first to validate the wire protocol and that recombined SRT reaches a local SRT sink), then exercise add/drop by toggling Wi‑Fi/cellular and confirm the stream survives.
- The exact srtla wire format (packet type constants, header layout) is pinned during implementation against the reference receiver — validated empirically, not assumed.

## Build phases (one sub-project, `feat/srtla-bonding`)

1. **Protocol core:** `SrtlaSender` + loopback proxy + registration/keepalive/link-select, validated single-link against `srtla_rec`.
2. **Multi-network:** `BondingNetworks` + manifest permissions + live add/remove; validate real 2-link bonding and survive a link drop.
3. **UI + ABR:** per-link stats surface + aggregate ABR feed.

## Risks / open items

- **Exact srtla wire format** must match the reference receiver — mitigated by empirical validation in phase 1.
- **Android per-socket binding** must route over the intended network even when another is the system default — `Network.bindSocket` / NDK `android_setsocknetwork`; verify cellular actually carries traffic while Wi‑Fi is up.
- **Dual-cellular** depends on DSDA hardware; design degrades gracefully and makes no promises on DSDS devices.
- **Background execution:** bonding must keep running under the existing foreground service; cellular kept alive via the explicit `requestNetwork`.
