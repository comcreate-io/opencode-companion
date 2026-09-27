# Session transport contract

Observed 2026-09-27. B1 implements finite session calls and independently cancellable streams; it does not wire the Android screens or enable a supported remote deployment. See [next-wave brief](../../../SESSION_TRANSPORT.md).

## Extended real-host probe

[Execution run 3](run-3/results.json) passed ten case groups on the pinned official OpenCode 1.18.32 binary and deterministic loopback model provider. It adds these observations to the previous execution evidence:

- Agent/model catalogs return location-scoped envelopes. Public catalog fixtures drop provider request/API configuration; agent capture uses a display-field allowlist (ID, description, mode, hidden, model and color), excluding request and system-prompt content. The saved run-3 agent capture was projected with that allowlist after review; route assertions were unchanged.
- `location[directory]` deep-object query resolves a nested directory in the disposable repository. Session creation preserves the explicit directory and caller-supplied session ID; reading that ID returns the same session. Workspace-ID selection is still unproven and unavailable in finite creation.
- Complete read-tool history replays identically through durable SSE. A new text turn on the same stream follows with contiguous sequence numbers and equals the final history, without transient deltas being mistaken for durable events.
- Question rejection returns 204, removes the pending request, and a duplicate rejection returns 404. The durable tool fails with `Tool execution interrupted`. At the observation point the session was absent from `/api/session/active` even though there was no settled-step event. A reply acknowledgement cannot be promoted to a fabricated step completion.

Run 1 stopped at a probe-only field mistake: prompt admission uses `data.id`, unlike durable events' `messageID`. Run 2 exposed the unsupported assumption that rejecting a question guarantees a settled-step event. Run 3 records request removal, actual tool failure and authoritative active state separately. These are protocol observations, not assertion suppression or invented completion.

Reproduce using the updated `scripts/probe-execution.py` with a new output directory and the pinned binary. Every host/repository/provider is disposable and loopback-only; no paid provider or user repository is used. Original admission, execution and permission evidence remains linked from [the M1 report](../REPORT.md).

## Kotlin boundary

The new command encoders use exact supported fields and validate caller identity, lengths and directories. Catalog DTOs keep display/selection fields only. Global events are routed by machine/session before strict transient-text decoding; unrelated notifications are normal.

Finite reads validate required fields and exact session correlation. Mutations are single-attempt and distinguish acknowledged, explicit rejection, unknown outcome and proven pre-dispatch failure. Invalid or lost success responses are unknown. Cancellation propagates; recovery belongs to the future coordinator. HTTP redirects, authenticators, connection retries, cookies and caller interceptors are disabled. Every mutation body is one-shot to block OkHttp's separate `503 Retry-After: 0` follow-up, and authorization stays bound to one HTTPS origin.

Durable and global flows own their calls and responses. They preserve complete frames preceding a malformed frame, bound lines/frames and use suspending backpressure. Streams have no whole-call deadline; the provisional policy is 5s connection and 30s read-idle timeout. An idle disconnect requires later coordinator recovery and never proves host execution stopped. No code in this wave advances a durable cursor or reconnects automatically. TLS classification inspects bounded cause/suppressed-exception chains because address fallback can suppress a handshake failure under a later connection error.

## Remaining integration

The coordinator must persist before dispatch, serialize credential rotation against sends, apply/commit replay before publishing state, refresh pending/active state and manage foreground recovery. Real native HTTP/UI, physical-device behavior, protected tunnel setup and selected authentication policy remain separate gates. The tested workspace selector subset is directory-based; arbitrary workspace IDs remain unavailable.

## Verification

The combined formatter, 77 local tests (43 protocol, 34 client), Android lint, instrumentation-APK assembly and app debug/unsigned release assembly passed. All tests ran without failures or skips. Client lint is clean; app lint has zero errors and 13 dependency-update warnings. Python Ruff F/E9 checks passed. [Machine-readable checks and APK hashes](checks.json) record the observed run. Device tests were not repeated for this transport-only change; the prior 16 foundation tests are separate evidence.

Independent OCR delegation reviews covered protocol/HTTP/streaming code and the runtime probe. Fixes include long filesystem paths, one-shot mutation bodies, ambiguous response handling, suppressed TLS failure classification and prompt-field removal from public agent evidence. UI, connected Android and remote setup acceptance remain unclaimed.
