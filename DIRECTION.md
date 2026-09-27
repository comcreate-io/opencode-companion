# Native OpenCode companion — proposed direction

> Historical proposal retained for context. [PLAN.md](PLAN.md), [Architecture](docs/ARCHITECTURE.md) and [UX](docs/UX.md) are the current implementation-planning baseline and take precedence over this earlier outline. In particular, pairing/gateway details remain conditional on the M1 auth decision.

Status: Kotlin/Android initial scope and OpenCode V2 as the primary UI/UX source confirmed. Native layout adaptations and connection architecture remain proposed. No application implementation or infrastructure provisioned.
Evidence checked: 2026-09-26.

## Product

A quiet, polished native Android interface for directing OpenCode on multiple computers, built in Kotlin with Jetpack Compose. Swift/SwiftUI is an optional future implementation only if coworkers want an iOS app; it is not part of the initial delivery. Each host owns its repositories, credentials and sessions. Switching machines does not migrate a running session.

Assumption: single-user, bring-your-own-machines first. Team accounts, hosted billing and automatic cross-machine workspace synchronization are outside the first release.

## Visual direction

Port OpenCode V2's UI/UX into Kotlin/Compose: use its actual semantic colors, Inter typography direction, icon geometry, composer hierarchy, tool rows and review controls. OpenCode V2 is the primary visual source; T3 Code is a secondary implementation reference. Preserve the recognizable design while adapting touch targets, keyboard behavior and multi-panel layouts to Android. Support light and dark appearance, large text, screen readers and reduced motion. See UPSTREAM-REUSE.md for pinned source evidence and reuse boundaries.

Navigation: machine picker -> projects -> sessions -> conversation. Keep machine, project and branch visible when composing or approving an action. The conversation has collapsible tool activity, a thumb-reachable composer, and a dedicated changes view with unified diffs. Distinguish awaiting approval, running, reconnecting, offline and unknown execution outcome.

## Architecture

- Kotlin-native presentation, lifecycle and application logic for Android. No iOS scaffolding or cross-platform runtime in the initial scope.
- Versioned API schema, behavioral specification, fixtures and acceptance scenarios that a future Swift client can reuse. Avoid abstractions whose only purpose is hypothetical iOS support.
- OpenCode remains the execution engine. Target the inspected V2 protocol under `/api/`; the earlier unversioned server documentation is V1-oriented and must not define our V2 request models.
- Prototype against a pinned OpenCode V2 release and its source/schema; the inspected development commit is a research baseline, not a validated release pin. Check server version and supported capabilities before enabling actions. V1 compatibility is outside the initial scope.
- A small host companion is a conditional proposal for pair/revoke devices, protected access and route restrictions. First validate V2's built-in connection facilities and add only missing functions; do not assume a new daemon is necessary.
- Candidate companion path: phone -> HTTPS named Cloudflare Tunnel -> authenticated host companion -> loopback OpenCode server. OpenCode's own server password remains enabled. Validate whether the companion can be omitted without losing required access controls.
- Companion implementation language remains a separate choice; evaluate a small Go binary when implementing host setup. The Android app remains Kotlin.

## Setup and trust

Target experience: configure the computer and remote access through a guided flow, scan an expiring single-use pairing QR on phone, confirm machine, open project. This complete flow is a product requirement, not a verified upstream feature. The live [V2 introduction](https://opencode.ai/v2/docs) advertises `opencode pair` in its Web section; this was not confirmed in the inspected clone. Its availability and suitability for our credential lifecycle need runtime validation before deciding whether to build a companion.

Named Cloudflare Tunnel setup requires the user's Cloudflare account and a suitable domain/hostname. Do not promise account-free one-command setup. Quick Tunnels do not support SSE and are excluded from the supported connection path.

The proposed device-pairing flow exchanges a short-lived bootstrap secret for a revocable device credential over authenticated TLS. Keep credentials in platform secure storage, redact logs and exclude secrets from URLs. Specify replay prevention, rate limits, revocation and key rotation before implementing pairing. Never embed Cloudflare account credentials or tunnel-management tokens in the app. Cloudflare terminates HTTPS: this route is not an end-to-end encryption claim.

Allow a separately tested private-network connection mode later. Public exposure always requires authentication. Do not expose provider credential-management or arbitrary host filesystem APIs through a blanket proxy.

## Reliability contract

- Durable draft scoped to machine + project + session; switching hosts cannot redirect an in-flight send.
- Server state is authoritative. Reconcile messages, run status and pending approvals on launch, foreground and reconnect.
- V2 distinguishes global live SSE (`/api/event`) from per-session durable replay (`/api/session/:sessionID/event?after=...`) and paginated durable history. Persist session sequence cursors with applied state; verify replay boundaries, deduplication, transient streaming updates and snapshot/stream races against a pinned release. Do not assume global SSE has durable replay.
- A dropped response after sending means outcome unknown. Reconcile before retrying; require proven server deduplication before any automatic resend.
- Approval responses must target the exact host/session/request and handle stale or already-resolved requests.
- Phone suspension must not be required for continued host execution. Host sleep, process failure or restart is a separate interruption; do not promise jobs survive those events.
- No continuous mobile background stream assumption. Add push notifications only as a later explicit service; opening a notification always refreshes authoritative state.
- Test long histories, malformed/unknown events, slow streams, Wi-Fi/cellular changes, token revocation, host restarts and two clients observing the same session.

## Delivery slices

1. Review this direction and native screen concepts. Validate the upstream API/auth/SSE contract against a disposable real server.
2. Android vertical slice: configure one protected host, list sessions, send a prompt, stream output, leave/reopen app and reconcile safely.
3. Two-machine acceptance: separate drafts/state, explicit host identity, permissions, cancellation and readable diffs. Test network loss during sends and approvals.
4. Guided pairing, named tunnel setup and host diagnostics. Test installation from a clean supported host and revocation from a second device.
5. Optional hosted Linux workspace, then notifications if useful. Avoid adding a hosted control service to the initial app.

Conditional future work: only if coworkers want iOS, build a Swift/SwiftUI app using the protocol fixtures and acceptance suite established by Android. This is not an Android release dependency. Build and physical-device validation would require a macOS/Xcode runner and iPhone.

Android release gates: Android builds pass, protocol and state recovery tests pass, physical Android device tests cover the failure scenarios above, and clean-machine setup is reproduced. HTTP documentation review alone is not runtime verification.

## Cloud later

Start with existing computers to avoid an additional compute bill. Represent a hosted machine exactly like another host; repository transfer and credentials remain explicit.

For a persistent first hosted workspace, evaluate AWS Lightsail before introducing orchestration. Current published Linux/public-IPv4 bundles include 2 GB at $12/month and 4 GB at $24/month. These are compute examples, not an all-in quote: model usage, snapshots, transfer overages and taxes may add costs. Benchmark a real repository before selecting memory.

Cloudflare Sandbox is an alternative for disposable task environments. Evaluate persistence, sleep/resume, runtime/toolchain support and actual workload pricing before choosing it. Cloudflare Tunnel handles connectivity; it does not itself provide development compute. AWS versus Cloudflare does not need to be decided for the mobile clients now.

## Sources

- OpenCode V2 introduction (live documentation, separately versioned from inspected source): https://opencode.ai/v2/docs
- OpenCode server API, authentication, OpenAPI and events: https://opencode.ai/docs/server/
- T3 Code reference: https://github.com/pingdotgg/t3code
- Cloudflare named/quick tunnel setup and SSE limitation: https://developers.cloudflare.com/tunnel/get-started/
- Cloudflare Sandbox overview: https://developers.cloudflare.com/sandbox/
- AWS Lightsail pricing: https://aws.amazon.com/lightsail/pricing/
- Android Compose: https://developer.android.com/compose
- Apple SwiftUI: https://developer.apple.com/swiftui/
