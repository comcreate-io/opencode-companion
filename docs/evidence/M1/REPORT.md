# M1 admission and replay spike

Observed 2026-09-26, America/Denver. This is a verified subset of M1, not a completed integration milestone or supported mobile connection.

## Runtime identity and isolation

The official [OpenCode v1.18.32 release](https://github.com/anomalyco/opencode/releases/tag/v1.18.32) Linux x64 archive was fetched and matched the SHA-256 published in GitHub release asset metadata:

- Archive: `opencode-linux-x64.tar.gz`
- Archive SHA-256: `3046e0404fdc60fb80307e7a47824ba07477364178a4d09baa8548496dd6d43b`
- Extracted binary SHA-256: `513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080`
- Binary `--version`: `1.18.32`

The binary's `serve` command exposes both legacy routes and `/api/*` V2 routes. The separate `packages/cli` preview is not required for this tested subset. The reference checkout remains pinned to `b471c2b4495747353af768fbf2e0790c9d820ce2`; its development source is research evidence, not assumed identical to the release. Captured runtime responses govern the decoder.

The probe starts a fresh loopback-only host, a disposable Git directory, isolated XDG data/config/cache/state, test home and temporary paths. Its environment is an allowlist without provider keys or inherited authentication. It generates a disposable Basic password in memory and never exports it. Every prompt uses `resume:false`, admitting input without scheduling agent execution. The process is terminated and temporary state removed when the probe exits. No real projects, host credentials, cloud resources or model calls are involved.

## Reproduction

Download and checksum the official archive above, extract it into the ignored `.android-local/m1/bin/`, then run from the project root:

```sh
nix develop . --command python scripts/probe-opencode.py \
  --binary .android-local/m1/bin/opencode \
  --output docs/evidence/M1/new-run
```

The output directory must not already exist. The script rejects a binary whose hash differs. It uses Python standard library and the existing Git executable; it installs nothing. It is a developer protocol probe, not a host setup script or application transport. Python 3.14.6 is supplied by the pinned project Nix shell for this probe.

## Runtime results

[Run 3 results](run-3/results.json) records ten passing case groups using the pinned Nix Python. Run 2 first added file/catalog reads; run 1 retains the preceding nine-case probe.

| Capability | Observation | Boundary |
|---|---|---|
| Basic auth | Missing/wrong credentials rejected with 401 | Loopback HTTP only; no TLS or per-device revocation claim |
| Health/compatibility | `/api/health` returns only `healthy:true`; `/doc` identifies the V2 route surface | Health does not contain a build/version identity |
| Unknown route | Unknown `/api/*` GET returns HTML with HTTP 200 | Status alone is not API success; decode strictly |
| Session discovery | Create, list and read pass | Disposable repository only |
| Files/catalogs | File list/read and agent/model catalogs respond | Does not prove model execution or diff APIs |
| Prompt admission | Identical message ID/body/session yields the same admission | `resume:false` only; no automatic app retry enabled |
| Conflict | Changed text or session with the same message ID yields 409 | Conflict is not an acknowledgement |
| History | Exclusive sequence cursor, page hasMore, invalid limits/cursors rejected | Admission events only |
| Durable SSE | Replay then live delivery; reconnect after cursor reproduces the next event | No transcript text/tool stream tested |
| Global SSE | `server.connected` frame has no SSE ID | No durable replay assumed |
| Lost response | Socket closed after sending; history contains one matching admission without resend | One controlled injection, not a general delivery guarantee |
| Interrupt/requests | Idle interrupt returns 204, pending lists empty, stale replies rejected | Active interruption and pending request races untested |
| Restart | History and deduplication survive host restart | Does not prove interrupted execution resumes |

## Kotlin boundary

The protocol module now has strict, bounded JSON decoding for the captured health, session summary/list, admission, conflict and durable admission-history subset. Missing required fields, wrong types, mismatched session identity and invalid sequence order fail explicitly. Unknown durable types/versions return an unsupported result with no applicable page; callers cannot treat it as permission to advance a cursor. The codec does not itself persist or advance any cursor.

A bounded incremental SSE decoder covers split UTF-8, line endings, multiline data, heartbeat/comment blocks, EOF and typed failures. Its `id` is an explicit per-frame field, not a persisted replay cursor. It intentionally rejects malformed UTF-8 instead of browser-style replacement decoding. Durable JSON sequence numbers remain the replay authority.

`kotlinx.serialization-json` 1.9.0 was added for JSON parsing; its [official release](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0) requires Kotlin 2.2 and is compatible with this project's 2.2.10 compiler. No serialization compiler plugin, HTTP library or orchestration runtime was introduced. Upstream is Apache-2.0 licensed. This is an original implementation against the wire contract; no T3 code was copied.

## Remaining M1 gates at the admission spike

Real model output, transient/durable text reconciliation, tool execution, active interrupt, pending permissions/questions and concurrent replies still need an isolated execution fixture. Changed-file/diff support remains unresolved in the tested V2 route surface. Native HTTPS authentication, redirect handling, protected credentials and revocation/rotation are untested. The existing shared Basic credential is not per-device pairing; D04 remains open before remote writes.

The app still displays its debug visual proof. There is no Android HTTP adapter, durable database or connected session UI. `supportedReleases` and `enabledRemoteCapabilities` remain empty; the compatibility manifest records this binary only as a tested spike build. M2 phone-layout acceptance remains separate.

## Final local verification

`nix develop . --command ./gradlew --no-daemon spotlessApply spotlessCheck :protocol:test :client:test :app:lintDebug :app:assembleDebug :app:assembleRelease` passed: 108 tasks, 15 seconds. Sixteen protocol tests plus four client tests passed, with no failures/errors/skips. Lint has zero errors; dependency-update warnings remain visible. [Machine-readable checks](checks.json) and [current APK checksums](apk-sha256.txt) record the result. M0 APK hashes remain historical and are superseded by this build.

Review caught missing admission message-ID correlation; it now requires both expected session and expected message ID. Regression cases reject comma-separated multiple JSON values in one SSE frame and HTML masquerading as a successful response. Two intermediate runs failed in a cursor fixture-mutation test (regex, then trailing-newline handling); the final test uses parsed JSON mutation with all original assertions intact. No baseline, test skip or suppression was added.

No Android UI changed in this slice, so M0 screenshots are still the visual reference; device/UI acceptance was not rerun. Both upstream reference checkouts remain unmodified. Remote CI, external repository creation and publication were not performed.

## Subsequent execution evidence

The [2026-09-27 execution and request report](execution/REPORT.md) supersedes the earlier model/tool, pending request and basic diff unknowns above with bounded runtime evidence. Remote/native integration gates remain open.
