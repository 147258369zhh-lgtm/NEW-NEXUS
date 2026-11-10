# A2A Mobile Transport Review

Status: reviewed against Eta's current implementation on 2026-11-06.

## Decision

The proposed architecture has a sound layering: Headscale supplies identity and
coordination, `tsnet` supplies userspace connectivity, and the existing A2A HTTP
service remains the protocol boundary. This is the strongest option currently
available for bidirectional A2A on Android because it provides a stable peer
address without taking Android's single system `VpnService` slot.

It must enter the product as an optional transport module, behind a feature flag.
LAN endpoints and ordinary HTTPS endpoints remain supported. A2A protocol code
must not depend on the transport implementation.

The proposal is not yet approved as a direct implementation: Android `tsnet`
startup, connection recovery, APK size, and Play policy behavior require a spike
on the target device before a native dependency is added to the main build.

## What Eta Already Has

- An inbound A2A 1.0 JSON-RPC endpoint with Bearer authentication.
- Persistent inbound tasks, idempotent `messageId`, `GetTask`, `CancelTask`, and
  context history.
- Runtime execution with tools disabled for unpaired inbound work.
- An outbound Agent Card client and task polling fallback.
- Encrypted A2A secrets and exact-peer URL checks. Tailnet address ranges are not
  automatically trusted.

What is missing is transport reachability. Being able to call another Agent and
being callable by it are two different capabilities; both ends need a reachable
listener and a discovery URL.

## Required Changes To The Proposal

1. Do not use a reusable pre-authorization key embedded in the app. Provision one
   short-lived key per device through a pairing flow and store the result through
   Android Keystore. The control plane must be able to revoke that node.
2. Pin every build input: Go, gomobile, `tsnet`, Tailscale dependencies, Headscale,
   Android NDK, and the AAR checksum. `@latest` is not reproducible.
3. Treat Android issue 17311 as unresolved until the fixed interface provider is
   proven on a real phone. Do not ship a version downgrade as a workaround.
4. Do not use an indefinite `dataSync` foreground service. Evaluate
   `remoteMessaging` or a narrowly documented `specialUse` service, user-started
   and visibly controllable. Handle Android 15 foreground-service limits and
   reboot behavior explicitly.
5. Bind the A2A HTTP server to `127.0.0.1` when it is reached through a transport
   proxy. Current LAN mode may bind the LAN interface, but the two modes need an
   explicit setting instead of one implicit bind-all configuration.
6. Supply the advertised Agent Card endpoint from the active transport. Reading
   the current Wi-Fi address inside the HTTP handler is wrong when the request
   arrived through a Tailnet or HTTPS tunnel.
7. Keep Bearer authentication and exact-peer authorization inside `tsnet`. A
   Tailnet is a network boundary, not an application authorization boundary.
   Whitelist exact peers and port `43121` in Headscale ACLs; never allow all of
   `100.64.0.0/10`.
8. Restrict outbound access to configured peers. Give each peer a stable identity,
   let the user approve it, and show last-used state. A shared token alone cannot
   safely identify several peers.
9. Keep HTTP inside the WireGuard tunnel for the first release. If a peer requires
   HTTPS, terminate HTTPS at the tunnel edge rather than weakening URL validation.
10. Make startup cancellable and bounded. The proposed Go bridge blocks on
    `context.Background()` and can leak connections when either copy direction
    ends. Use a timeout, a cancel function, connection limits, and half-close
    aware proxying.
11. Save the node state in app-private storage, but do not restore a copied node
    identity onto a second running device. A restored backup should enroll a new
    node and replace the old registration.
12. Add task retention and rate limits at the A2A boundary. Persistent tasks and
    artifacts cannot grow without a TTL or user-visible storage quota.

## Recommended Topology

```text
external A2A client
        |
  tailnet identity + ACL (exact peer, port 43121)
        |
in-process tsnet listener
        | 127.0.0.1
Eta A2A HTTP server
        |
Bearer authentication + exact-peer policy
        |
unified Agent Runtime (tools disabled for inbound tasks)
```

The listener should be an optional composition around the existing A2A server,
not a second protocol implementation. Normal LAN mode and tunnel mode should use
the same HTTP server and runtime.

## Delivery Gates

### Gate 1: Android transport spike

- Build a fixed-version AAR with the Android interface-provider workaround.
- Run it on the physical target device without a system VPN service.
- Measure startup, arm64 and 32-bit APK growth, idle battery use, task survival
  during screen-off, and recovery after Wi-Fi/mobile switching.
- Confirm no conflict with an active third-party VPN.
- Ship nothing in the default build until these checks pass.

### Gate 2: Pairing and identity

- Add a one-time code or QR pairing flow and a revocable per-device enrollment.
- Add a server-side enrollment service that never exposes its issuance key to the
  client.
- Display local node identity, remote peer identity, authorization state, and
  active connection state in settings.

### Gate 3: End-to-end A2A

- Prove both directions: phone to computer and computer to phone.
- Prove duplicate message delivery, task polling, cancel, context continuation,
  artifact quarantine, and runtime cancellation.
- Test direct LAN, CGNAT, IPv6, relay-only, VPN-present, and network-switch paths.
- Add a health/status surface that reports control-plane, tunnel, listener, and
  Runtime readiness separately.

### Gate 4: Mainline integration

- Package the transport as an optional module and include its source build and
  license notices.
- Keep LAN, manual HTTPS, tailnet, and pull-only fallback selectable.
- Add upgrade, revocation, rollback, and lost-device procedures before enabling
  the feature for general users.

## Fallback

When `tsnet` cannot start reliably on a supported Android release, use a pull-based
relay for notifications and tasks while retaining direct LAN and manually
configured HTTPS endpoints. Do not silently fall back to an untrusted public
relay or to a reusable credential.
