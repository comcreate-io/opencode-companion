# Verification and release evidence

Status: test plan with partial M0 local evidence. Formatting, four client state tests, Android lint and debug/unsigned release assembly passed in the pinned shell; M1 subsequently added protocol codec/SSE tests and real-host admission/replay, execution, request and basic diff probes; read-only HTTPS tests now cover authentication, redirects, bounds and cancellation; see [M1 transport evidence](evidence/M1/transport/REPORT.md). Emulator boot, debug APK install/cold launch and clean source-export assembly passed. See [M0 evidence](evidence/M0/REPORT.md). Requirement IDs come from [PLAN.md](../PLAN.md). The architecture defines behavior; this document defines how to prove it.

## Evidence format

For each executed scenario record: requirement/case ID, app commit/build variant, device model/API, OpenCode release/commit, auth/transport mode, fixture revision, preconditions, action, expected/observed outcome and artifact path. Redact credentials, prompts from real work and repository contents. Use disposable repositories with recognizable synthetic markers.

Proposed output location: `docs/evidence/<milestone>/<run-id>/`. Save concise results and relevant screenshots/log excerpts. A status is `not-run`, `pass`, `fail` or `blocked` with its reason. CI green does not replace device/remote-path evidence.

## Automated layers

| Layer | What it proves | What it does not prove |
|---|---|---|
| Pure Kotlin tests | Reducers, identity keys, compatibility, composer transitions, outbox state rules | Actual host semantics or Android lifecycle |
| Contract/HTTP fixture tests | DTO decoding, SSE framing/chunk boundaries, errors, adapters | That captured fixtures match a new upstream release |
| Database tests | Atomic cursor/projection commit, draft revisions, intents and migrations | Remote acceptance or encrypted database storage |
| Compose tests | Navigation, state presentation, accessible actions and draft behavior | Complete TalkBack, hardware performance or real remote execution |
| Real-host integration | Pinned release contracts, admission, replay and stale requests | All mobile network/process lifecycle failures |
| Physical-device acceptance | Keyboard, suspension, process death, network changes, interaction and performance | Every OS/vendor variant |

Prefer deterministic fake transports, controlled clocks and scripted disconnects. Keep fixture payloads traceable to a release/commit and redact them. Do not test a reducer only by duplicating its branches; assert complete user-visible outcomes, ordering and persistence boundaries.

## Required scenario matrix

| Case | Requirement | Injection / action | Pass condition |
|---|---|---|---|
| V01 | R02 | Two hosts use same session/project identifiers | Independent caches, drafts, cursors and approvals |
| V02 | R02, R06 | Switch host while send/storage awaits | Original destination remains fixed; no action reaches newly selected host |
| V03 | R06 | Drop response after host accepts prompt | Unknown intent recovered to admission via evidence; no blind resend |
| V04 | R06 | Local acknowledgement cleanup fails | Cleanup retried; network prompt count unchanged |
| V05 | R06 | Kill process before dispatch / during send / after ack | Prepared, unknown or admitted state recovered correctly for each boundary |
| V06 | R05 | Disconnect stream; events occur; replay same range twice | Final durable state equals authoritative state; no duplicate content |
| V07 | R05 | New events arrive during history/snapshot load | No gaps or double application; reproducible correct final state |
| V08 | R05 | Durable cursor transaction fails mid-commit | Both cursor and projection roll back; replay recovers |
| V09 | R05 | Unknown event, malformed JSON, split UTF-8/SSE frame, oversized payload | No crash/unbounded buffer; unsafe cursor advancement prevented; repair/error explicit |
| V10 | R05, R09 | Global transient deltas overlap durable final events | One coherent message; overlays replaced instead of appended twice |
| V11 | R07 | Desktop answers while phone approval is open | Stale phone action rejected/refreshed; no false success or broader grant |
| V12 | R07 | Double tap / lose approval or interrupt response | No automatic repeated mutation; authoritative state determines outcome |
| V13 | R09 | Wi-Fi to cellular, airplane mode, app background/return | Honest cached/reconciling state; bounded reconnect; final transcript correct |
| V14 | R09 | Host sleeps/restarts while phone remains active | Show unreachable/cause unknown until authoritative host status or verified instance identity establishes interruption/restart; re-read status |
| V15 | R10 | Wrong TLS, expired/revoked auth, redirect to other origin | No bypass or credential forwarding; clear recovery path |
| V16 | R03, R10 | Malformed/expired/reused QR, hostile endpoint | Validation rejects unsafe input; no silent host association |
| V17 | R03 | Fresh NixOS/Linux host + fresh app + named tunnel | Documented supported setup works, SSE works, unauthorized client denied |
| V18 | R08 | Empty/no-Git/binary/renamed/deleted/large diff | Accurate, bounded read-only presentation; truncation disclosed |
| V19 | R11 | Stream long conversation while reading old text / expanding tools | Reading anchor retained; no keyboard overlap or input lag from full-feed updates |
| V20 | R11 | TalkBack, large text, dark/light, reduced motion | Core flow operable; labels/focus meaningful; no clipped critical controls |
| V21 | R12 | Update app across every supported shipped schema | Drafts, profiles, pending intents preserved; derived cache rebuilt safely |
| V22 | R10, R12 | Inspect backup/export/log/crash output and remove-host flow | No credentials/content leakage; local cleanup does not delete remote sessions |
| V23 | R04 | Real session create/open, prompt, model/agent, file context, question and interrupt | Supported operations confirmed by host; unsupported ones explicitly unavailable |
| V24 | R12 | Unsupported/unknown OpenCode release or changed schema | Compatibility failure is actionable; no silent mutation attempt |

QR-specific acceptance applies only if M1 selects a pairing mode that supports it. Manual authenticated HTTPS setup is always covered. If a per-device revocation limitation is accepted, record the decision and test its rotation/re-pair behavior; do not mark a nonexistent per-device feature as passing.

## Performance and resource budgets

Proposed beta targets to calibrate in M0/M3 on the named physical device; they are not measured claims:

- Cached conversation content visible within 500ms after selecting a cached session, excluding app cold start.
- At 60Hz, target p95 frame duration at or below 16.7ms during ordinary transcript scrolling; report workload and observed jank rather than hiding outliers.
- No ANRs/crashes during a 30-minute scripted stream/reconnect session; no continuously growing memory after ten repeated open/close cycles.
- Stress fixture: 1,000 messages plus a file with 5,000 diff lines. Pagination/virtualization keeps reading and draft input usable; large files can be explicitly truncated with an expansion path.
- In background, no required permanent stream or wake lock. Record whether subscriptions release and whether foreground recovery restores state.

Measure before optimizing. If a target is unsuitable, document observed baseline and an accepted revised budget; do not quietly delete the benchmark.

## Build and CI contract

M0 established executable commands for formatting, Android Lint, current JVM state tests and debug/unsigned release assembly; the observed command is in README. Database/contract and device tests await their implementation. The debug app installed and cold-launched on the isolated API 36 emulator. Do not count these M0 checks as real-host, lifecycle or beta acceptance.

Every pull request: formatting/static analysis, affected deterministic tests and debug build. UI changes add Compose/visual evidence. Persistence changes add migration/transaction checks. Protocol changes add pinned real-host smoke evidence. Nightly/scheduled testing is optional future work, not an automation created by this plan.

CI must validate the wrapper and dependency inputs, keep release secrets out of untrusted contributions and avoid requiring production credentials for PR tests. Do not upload raw network captures or real project contents as public artifacts.

## Release checklist

- Requirements R01–R12 have linked evidence; conditional cases are explicitly marked with the decision that makes them inapplicable.
- Exact supported OpenCode version(s), app build, Android API range and host setup mode are documented.
- Physical-device and two-host acceptance passed on the recorded environment; emulator-only limits are disclosed.
- No known critical/high defect in data integrity, machine isolation, access control or core workflow.
- APK signature and checksum verified; signing material ownership documented outside source control.
- Install/update instructions reproduced; migrations tested; rollback compatibility or safe reinstall procedure stated.
- Provenance notices included for actually copied/translated code/assets. No T3 notice added merely because its design was studied.
- Known limits and privacy/storage behavior included in release notes. No telemetry/service added without an explicit product decision.

## Documentation checks for this planning baseline

Verify internal file links, source references/SHAs, requirement-to-milestone coverage, scope consistency, absence of claimed test success, and a fresh-reader review. Record the planning check result separately from application test evidence.

Planning review executed 2026-09-26:

- Eight Markdown documents checked: local file links resolve and code fences are balanced.
- All twelve requirement IDs appear in the verification plan.
- Both reference checkouts retain their recorded commit IDs and have no tracked modifications.
- Fresh-reader review passed after correcting ambiguous host-sleep attribution and aligning the shared-credential decision with the architecture gate.
- At the time of this planning review, no Android build, application test, visual review, real-host integration or remote setup acceptance had been executed. Later M0 local build/test evidence is recorded separately above; visual, real-host and remote acceptance remain open.

Android reference material: [architecture recommendations](https://developer.android.com/topic/architecture/recommendations) and [security guidance](https://developer.android.com/privacy-and-security/security-tips). Upstream-specific behavior remains tied to [the pinned source review](../UPSTREAM-REUSE.md).
