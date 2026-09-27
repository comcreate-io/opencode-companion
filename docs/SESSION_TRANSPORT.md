# Next implementation wave: session transport

B1 in the [build map](BUILD_MAP.md). This is an implementation brief, not enabled capability. It follows the pinned source and OpenCode 1.18.32 runtime evidence; unsupported cases remain explicit.

## Ownership and boundaries

Two independent workers can implement protocol request/catalog/global-envelope codecs and client HTTP/SSE transport with deterministic HTTPS fixtures. The integration owner agrees the interfaces first and handles runtime gap probes. The existing admission, request and transcript codecs are reused. A later coordinator owns persistence, retry/recovery and credential/profile reconciliation; transport does none of those automatically.

Every command captures machine, canonical origin, credential generation, session, message/request identity and payload. Returned results retain that scope. Never resolve a mutable selected-machine value after awaiting work. Encode identifiers with path-segment APIs and validate response identity against the request.

## Routes and proof boundaries

| Operation | Route | Current evidence / required work |
|---|---|---|
| Create/read session | `POST /api/session`, `GET /api/session/:id` | Default and agent/model creation observed; validate returned session ID; explicit location/client-supplied ID need probes |
| Active status | `GET /api/session/active` | Observed; absence means inactive in this host process, not uninterrupted execution history |
| Prompt | `POST /api/session/:id/prompt` | Text execution observed; exact message/session/payload admission correlation required |
| Interrupt | `POST /api/session/:id/interrupt` | 204 acknowledges request; coordinator subsequently observes completion |
| Permissions | `GET /api/session/:id/permission`, `POST /api/session/:id/permission/:request/reply` | Once/reject, wrong-session, stale/concurrent replies observed; refresh whole pending list |
| Questions | `GET /api/session/:id/question`, `POST .../:request/reply`, `POST .../:request/reject` | Reply observed; reject still needs a disposable runtime probe |
| History / durable stream | `GET /api/session/:id/history?after=N&limit=100`, `GET /api/session/:id/event?after=N` | Replay observed; broaden complete execution replay-to-live coverage; use durable transcript decoder |
| Global transient stream | `GET /api/event` | Text deltas observed; route envelopes by session before strict scoped decoding |
| Selection catalogs | `GET /api/agent`, `GET /api/model` | Endpoints observed; add minimal DTOs and agent envelope capture |
| Location | `GET /api/location` | Source-backed; prove location selection/query encoding before project setup depends on it |

Source groups are `packages/protocol/src/groups/{session,permission,question,model,agent,location,event}.ts` in the pinned OpenCode reference. Do not use `/api/session/:id/wait`: the tested runtime returns 503.

Catalogs retain only display/selection fields. Discard provider request headers, bodies and API configuration returned by model catalogs; real values may contain secrets. No provider-key editor or logging enters this client.

## Results and streaming

Mutation outcomes are `Acknowledged` (validated exact correlation), route-specific `Rejected`, `OutcomeUnknown` (lost/ambiguous response or invalid success payload), or `NotDispatched` only when that is established before dispatch. An IOException is not evidence that the host received nothing. A stale 404 does not prove this client answered. Cancellation propagates; durable intent remains unresolved for recovery. Never automatically resend.

Reuse system TLS trust, exact-origin authorization, disabled redirects/retries/authenticators/cookies, strict UTF-8, bounded bodies and sanitized errors. JSON 200/204 handling is route-specific; HTML 200 is not compatibility evidence.

Finite calls retain provisional 5s connect, 10s read and 20s total limits. SSE needs a separate policy: bounded establishment, no 20s whole-stream deadline, cancellable cold flows, finally-closed responses and bounded backpressure without silent durable-frame drops. Verify durable-stream idle behavior; global heartbeat behavior does not establish it. Preserve completed frames reported with framing failures. SSE `id` never independently supplies a durable cursor. Select an explicit line/frame budget: the existing decoder's 64 KiB line default is smaller than its 1 MiB frame budget and upstream emits single-line event JSON.

## Definition of done

HTTPS fixtures prove exact routes/methods/bodies, identity collisions, correlated admissions, 204 and error statuses, HTML/malformed success, size limits, lost response after acceptance, one mutation request only, cancellation and redirect isolation. Stream tests cover split UTF-8, heartbeat periods, healthy streams beyond finite-call timeout, unrelated sessions, valid frames before malformed input, bounded backpressure and zero remaining active calls after cancellation.

Runtime probes close the location, question-reject, catalog and complete replay/live gaps using disposable repositories and the deterministic loopback provider. Unknown behavior remains disabled. Run combined build checks and independent OpenCodeReview. Remote authentication approval, phone layout acceptance and actual device flows remain separate gates; B1 is not a connected-app beta.
