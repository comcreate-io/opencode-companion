# Native connection and lifecycle recovery

Observed 2026-09-27 (America/Denver). This wave exercises part of V13 on the owned API 36 emulator (`sdk_gphone64_x86_64`). It uses the unchanged **0.1.1-dev-candidate** application from `686367d65f4da6744f8988ea60b1bebfce5e620b`, SHA-256 `bd2f3c0ef2229d4d15579d2d0bbfc5832003ffb4d45e20a609821343c2be24a9`. All new controls are in instrumentation; no application code, schema, dependency or production transport policy changes.

The host is official OpenCode **1.18.32**, binary SHA-256 `513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080`, in disposable repositories behind generated HTTPS with a deterministic local provider. Each run uses a separate fixture database. The device route is emulator-to-loopback HTTPS, not a tunnel or physical radio handoff. Test-only trust retains hostname verification.

## Scenario

The native flow sends `READ_CASE`, observes its completed durable response and acknowledged outgoing state, and stores an unsent draft. It then verifies:

- Both global and exact-session streams received successful SSE response headers. Stopping the Activity drains all running and queued calls without fault-induced cancellation.
- After resuming, the test blocks new socket connections and cancels existing connections. The UI becomes unavailable, preserves cached content and draft, removes Ready, and disables Send. A blocked-connect counter proves the gate was exercised.
- Backgrounding drains calls; resuming while still offline causes another blocked connection. Machine identity, session identity and draft key/revision/text remain unchanged.
- Restoring network access while stopped starts no call during a bounded three-second observation window, longer than the current two-second refresh interval.
- Foreground return restores Ready, accepted global SSE and a written durable subscription request for the exact session. One completed durable response remains, the draft is intact, and the prompt-attempt count stays at one. Stopping again drains the recovered subscriptions.

The fault gate is bound to one fixture client/coordinator. Earlier tests retain their own counters and gates. It observes calls before dispatch, so the no-resend assertion also counts attempts that could fail before reaching the host. It does not copy or log request bodies or credentials; per-call tracking is released on completion/failure. The gate returns online and the Activity resumes in the test's cleanup path.

## Idle-stream failure and correction

The initial dispatcher-based test passed. Review strengthened it to require received SSE headers; [that revision failed](initial-idle-header-failure.txt) while waiting for both streams after recovery (test APK `f3a3f09f4b252356d8ef5b769d1c70fbca4a69fc91932267416dc1b498338aba`). This failure is retained rather than treated as a passing run.

The pinned host's durable event stream emits stored/new events, with no initial event or heartbeat at the tail. Its global stream sends a connected event and heartbeats. A [direct HTTPS diagnostic](idle-stream-diagnostic.txt) received global and replay response headers immediately, while a durable subscription beyond existing events received no headers within five seconds. The fixture proxy forwards headers only after its upstream supplies them.

The final test therefore proves cleanup of two accepted streams **before** inducing an outage. For idle reconnection it requires a successful global SSE response plus `requestHeadersEnd` for the exact durable route, with both calls still running and not canceled. This establishes the durable subscription request was written; it does not claim an idle durable response or event was received. Tracking is removed on call completion/failure. Identity, draft, unique-response and prompt-count assertions remain intact; no production fix was indicated by this header expectation failure.

## Results

Final instrumentation source is `12cbbbb399a34820c8a0c78c1be3891c97643d15`, test APK SHA-256 `d31a9f369f403eb4327737fe8488c9e26e7ed62837c8c7bd109cffe1bb1b72b1`. The application hash matches the candidate above.

| Check | Result | Evidence |
|---|---|---|
| Core native suite: read/tool/draft/recreation/changes, question reply, interrupt, reading position, recovery | Pass, 5 cases, 188.123 seconds | [Instrumentation](core.txt) |
| Two-host regression: colliding session identifiers preserve isolated drafts | Pass, 1 case | [Instrumentation](twohost.txt) |
| Formatting | Pass | `spotlessCheck` in pinned Nix shell |
| Instrumentation assembly and app lint | Pass; zero lint errors, 15 existing warnings | `:app:assembleDebugAndroidTest :app:lintDebug` in pinned Nix shell |

[Machine-readable checks](checks.json) record both runs and their matching APK hashes. The five core cases ran together on the final test APK; the two-host case ran separately with the same APKs. Preliminary focused passes belong to earlier instrumentation builds; the final suite supersedes them. Existing JVM and physical/storage results are not claimed as rerun here. Final PR CI and exact-range OCR review are separate gates.

[Cleanup verification](cleanup.txt) records installed emulator APK read-back matching the candidate, both disposable hosts stopped, private ready files removed, and only the owned emulator shut down.

Synthetic captures show [before fault](offline-before.png), [offline/reconnecting](offline-unavailable.png) and [recovered](offline-after.png). The offline PNG caught “Connecting to host…” during a retry after the test had asserted the Unavailable message; it shows retained content/draft and disabled Send, not the Unavailable label itself. The passing instrumentation records that earlier assertion. These captures do not establish owner layout approval.

## Reproduce

Start `scripts/android-host-fixture.py` with the verified pinned binary, `TMPDIR=/tmp`, a new private ready path and bounded lifetime (`--help` lists its arguments). Keep ready files in ignored storage; do not substitute a real project or credential. From the pinned Nix shell, the focused case is selected with:

```bash
python3 scripts/run-connected-tests.py \
  --ready .android-local/recovery-fixture.json \
  --output .android-local/recovery-new-run \
  --test offlineBackgroundForegroundKeepsDurableState
```

Use a fresh output directory. Omitting `--test` runs all five core cases; `--second-ready` selects the separate two-host test. The runner validates the owned emulator, fixture origin and disposable path before installation.

## Scope and remaining acceptance

This is controlled client connection-loss and Activity lifecycle evidence, not complete V13 acceptance. It does not establish Wi-Fi/cellular transitions, airplane mode, host sleep/restart (V14), long-duration resource budgets, fresh named-tunnel setup, paid provider execution, TalkBack, minimum API, landscape or owner layout approval. No physical-device setting or installed app was changed in this wave. Earlier [14-case Pixel results](../../M3/accessibility/pixel/REPORT.md) retain their original source/test provenance.
