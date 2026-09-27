# M1 execution, requests and text reconciliation

Observed 2026-09-27, America/Denver. This extends the [admission/replay spike](../REPORT.md); it does not make the Android app connected or enable remote capabilities.

## Runtime evidence

[Execution run 8](run-8/results.json) passed seven case groups against the same official 1.18.32 Linux x64 binary (SHA-256 `513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080`). A deterministic loopback OpenAI-compatible HTTP fixture supplied model responses. OpenCode itself performed scheduling, text streaming, tool execution, question handling and interruption. This validates the host protocol, not model intelligence or a paid-provider integration.

- The resolved fixture model and loopback URL were checked before dispatch.
- The real session engine streamed `Fixture complete.` and stored the full text durably.
- Global live deltas concatenate to the same final value; finite replay omits transient deltas. Re-reading history returns the same durable result.
- The `read` tool read `M1_READ_MARKER` from the disposable repository and returned it to the fixture provider.
- A real `question` tool created a pending request. Wrong-session reply returned 404; correct reply returned 204; repeating it returned 404.
- Interrupt was sent only after the live `Waiting fixture` delta and active-session membership were observed. The host then became idle and stored `Provider turn interrupted` in the durable failed-step event.
- The combined server's read-only `/vcs/diff?mode=git` returned an empty list, then the expected README modification after a local fixture edit. This route sits outside `/api`; it is the same family used by the reference desktop V2 review flow, not an invented V2 diff endpoint.

[Permission run 2](../permissions/run-2/results.json) independently passed five groups: exact-session request ownership, wrong-session access rejection, once/reject cleanup, stale replies and simultaneous replies. The two synchronized clients received one 204 and one 404. This is one controlled race, not an exhaustive concurrency guarantee. Rejecting one request also clears other pending requests in that session; clients must refresh the whole pending list.

## Reproduce

```sh
nix develop . --command python scripts/probe-execution.py \
  --binary .android-local/m1/bin/opencode \
  --output docs/evidence/M1/execution/new-run
nix develop . --command python scripts/probe-permissions.py \
  --binary .android-local/m1/bin/opencode \
  --output docs/evidence/M1/permissions/new-run
```

Output directories must be new. Both probes verify the binary hash, bind only loopback, allowlist their host environment, use isolated XDG paths and disposable repositories, and terminate their own child host. Provider credentials are unnecessary. The permission probe creates synthetic requests; it never executes a shell command or creates a saved permission. The execution provider emits only predetermined text, a read of its own fixture file, and a question. Its host configuration denies other tools. The diff baseline commit is in the disposable test repository, with hooks and signing disabled; no project Git history is changed.

Python comes from the pinned Nix dev shell. Formatting/static Python checks used scoped Ruff 0.16.1 from the same nixpkgs revision; Ruff is not a global install or claimed project-shell executable.

## Compatibility discoveries

The combined 1.18.32 CLI validates its configuration through the legacy loader at startup, then V2 migrates the same config file. A file containing native V2 plural `permissions` is rejected by this CLI. The fixture uses singular `provider` / `permission` configuration in its isolated XDG file. `OPENCODE_CONFIG_CONTENT` alone is insufficient for V2 core configuration. Configuration is validated by runtime catalog read-back, not by a successful process launch alone.

`POST /api/session/:id/wait` is advertised in the schema but returned 503 `Session wait is not available yet`. The probe uses bounded active-state and durable-event observations instead. A future client must not assume every published route is implemented.

Execution runs 1–3 failed during startup configuration investigation; run 4 reached the selected model but found the unavailable wait route. Run 5 passed basic execution, run 6 added live/durable comparison, run 7 added the read-only diff, and run 8 strengthened active-interrupt assertions. Earlier successful captures remain source fixtures where recorded by their provenance. No unsupported feature is marked passing because its route exists.

## Kotlin changes and limits

Strict permission/question codecs retain exact machine/session/request identity and reject malformed required fields. Reply payloads are explicit, with no automatic permanent approval. The text codec separates transient deltas from durable final values. The pure immutable projection is keyed by machine, session, assistant message and text fragment; final text replaces provisional content, replay is idempotent, and late deltas cannot append to finalized text. Memory, identity and parser depth budgets fail explicitly.

At this checkpoint this was a text-only projection. The later [foundation wave](../../M3/foundations/REPORT.md) adds a separate durable text/tool/step reducer and Room journal; the transient text projection described here remains separate. It neither advances a replay cursor nor implements a complete transcript, tool renderer, transport or database. Unknown non-text events are not permission to skip durable state changes. Captured synthetic payloads and mutation tests are separate from live Android behavior.

Remaining gates: native HTTPS/auth storage and rotation, tunnel behavior, Android persistence/lifecycle, full transcript integration, no-Git/binary/large diff handling, physical-device and phone-layout acceptance. Real paid-provider behavior and exhaustive pending-request restart/race scenarios were not tested. Supported app releases and enabled remote capabilities remain empty.

## Local verification

The following command passed after the review fixes:

```sh
nix develop . --command ./gradlew --no-daemon spotlessApply spotlessCheck :protocol:test :client:test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Observed 34 tests (30 protocol, 4 client), zero failures/errors/skips; Android lint had zero errors and eight existing dependency-update warnings. Both debug and unsigned release APKs assembled. [Machine-readable results](kotlin-checks.json) and [APK hashes](apk-sha256.txt) record this check. Python syntax compilation and scoped Ruff `check --select F,E9` passed for both new probe scripts. No device/UI behavior changed or was newly verified in this step.

Review corrections bind reply commands to their original machine even when hosts have colliding upstream IDs, reject deeply nested request JSON before parsing, validate question answer row counts, and cap retained delta IDs globally at 4,096. Regressions cover those cases, including empty deltas, idempotence at the limit and budget release on finalization.
