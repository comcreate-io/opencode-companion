# OpenCode V2 native port: source and reuse map

Current delivery sequencing and scope are defined in [PLAN.md](PLAN.md). This document remains the source-research record; its proposed implementation order below is superseded by the detailed milestones.

Reviewed 2026-09-26. Static source inspection only: neither upstream application nor its tests were run. This document specifies proposed native adaptations, not a completed Android implementation or a visual parity result.

## Authority and provenance

OpenCode V2 is the primary UI/UX source, per Carter's direction. T3 Code supplies secondary implementation patterns. Initial delivery is Kotlin/Compose; Swift remains conditional on coworker demand.

Local reference checkouts (keep unmodified; do not vendor the complete repositories into the application):

| Reference | Inspected commit | Source prefix |
|---|---|---|
| OpenCode | `b471c2b4495747353af768fbf2e0790c9d820ce2` | https://github.com/anomalyco/opencode/blob/b471c2b4495747353af768fbf2e0790c9d820ce2/ |
| T3 Code | `c9a0e8a119271765196dec38ef91395713da4213` | https://github.com/pingdotgg/t3code/blob/c9a0e8a119271765196dec38ef91395713da4213/ |

Paths below are relative to these repositories under `references/`. Commits pin research evidence; neither development head is an accepted runtime release.

Both root LICENSE files are MIT. OpenCode's `packages/ui/LICENSE` is also MIT. Preserve the copyright and permission notices when porting substantial code or copying assets. Track each copied/translated file and originating commit in third-party notices. Check fonts and third-party assets individually before bundling them. Use our own app name/icon; no affiliation claim.

## OpenCode: what to port

| Source | Verified implementation | Kotlin/Compose use |
|---|---|---|
| `packages/ui/src/v2/styles/colors.css:4` and `theme.css:261`, `theme.css:383` | Explicit light/dark semantic palette, layered grey surfaces, blue accents and status colors | Preserve semantic token names and resolved values in a custom Compose theme |
| `packages/ui/src/v2/styles/theme.css:143`; `components/button-v2.css:13` | Inter typography; small radii and compact controls | Preserve type character and hierarchy; use Android font scaling and larger interaction areas |
| `packages/ui/src/v2/components/icon.tsx:3` | Inline SVG paths for branch, folder, search and other UI icons | Convert selected non-brand glyphs to native vector assets, retaining notices |
| `packages/session-ui/src/v2/components/prompt-input/machine.ts:3` | Explicit states/events/commands for mentions, slash commands, shell mode and focus | Port the relevant transition logic as pure Kotlin, with native editor/IME handling |
| `packages/session-ui/src/v2/components/prompt-input/machine.test.ts:19` | Cases for slash completion, cursor-local mentions, preserving draft text and context files | Translate behavior fixtures into Kotlin tests; replace desktop keyboard assumptions where needed |
| `packages/app/src/components/prompt-input-v2.tsx:44` | Composer integration of model controls, history, attachments, selected file context and submission | Recreate the composer hierarchy and state-driven controls in Compose |
| `packages/session-ui/src/v2/components/basic-tool-v2.tsx:32` | Compact tool rows with status, optional arguments, diff counts and collapsed details; pending tools cannot expand in this component | Preserve row hierarchy and status treatment; explicitly decide whether Android should permit viewing live tool output |
| `packages/session-ui/src/v2/components/session-review-v2.tsx:1` | Searchable file sidebar and review controls; underlying web diff rendering dependencies | Native changed-file list plus unified diff screen on phones; use wider layouts for tablets |
| `packages/app/src/utils/server-scope.ts:3` and its tests | Server-scoped route/state keys and guarded legacy migration | Structured machine/project/session identities across navigation, drafts and caches |
| `packages/app/src/context/server-session-v2-reducer.ts:12` and its tests | Event reduction for admitted/promoted inputs, streaming content, tools, retries and completion; reports missing message state | Port the supported V2 event subset and equivalent fixture tests, preserving unknown-event diagnostics |
| `packages/app/src/context/server-session.ts:924` | Fetches missing V2 message data after an incomplete event sequence | Authoritative recovery instead of fabricating a transcript from missing deltas |

The SolidJS components, DOM editor, CSS and browser diff workers are not Kotlin dependencies. Tokens and selected vector geometry can be converted directly; rendering and lifecycle code need a native implementation. Port interaction logic selectively rather than translating the entire web app.

### Concrete visual baseline

Resolved from the active explicit light/dark blocks, not the commented OS-preference block:

| Role | Light | Dark |
|---|---|---|
| Base background | `#FFFFFF` | `#161616` |
| Deep background | `#FAFAFA` | `#080808` |
| First surface layer | `#FAFAFA` | `#242424` |
| Second surface layer | `#F2F2F2` | `#2E2E2E` |
| Primary text | `#161616` | `#FAFAFA` |
| Accent background | `#3B5CF6` | `#3B5CF6` |

Keep V2's understated dividers, tool-row treatment, code/diff colors and composer structure. Do not substitute a generic Material color palette. Material/native primitives can supply accessibility and input behavior under the custom styling.

Phone layout proposal: persistent machine/project/branch header; conversation as the main surface; compact session switcher; changes and files as dedicated destinations or sheets; anchored V2-style composer. Adapt desktop tabs and sidebars to available width rather than shrinking the whole desktop. Increase small desktop controls to a proposed 48dp interaction area, respect IME insets and preserve scroll position while streaming or expanding tools. Exact mobile layouts still need a visual proof and review.

## V2 protocol findings that revise the earlier plan

- `packages/app/src/utils/server-protocol.ts:24` probes `/global/health` for V1 and `/api/health` for V2. Its fallback returns V2 when detection fails; our app should instead show an explicit unknown/unreachable state until compatibility is verified.
- `packages/protocol/src/groups/session.ts:205` defines `/api/session/:sessionID/prompt`, optional client message ID and a durable admission response. An optional ID alone does not prove safe automatic resend; inspect and test duplicate-submission handling before enabling it.
- `packages/protocol/src/groups/event.ts:37` defines the global `/api/event` stream. `packages/server/src/handlers/event.ts:10` emits no SSE `id`, uses a bounded live subscription and sends a heartbeat every 15 seconds. Do not infer replay from that stream.
- `packages/protocol/src/groups/session.ts:307` defines paginated durable session history with an exclusive sequence cursor; line 327 defines `/api/session/:sessionID/event?after=...` with replay followed by live durable events. `packages/server/src/handlers/session.ts:333` and line 358 wire these to the session service. Use this contract as the starting point for replay recovery, then validate it on a pinned release.
- Durable session events and transient text deltas must be reconciled deliberately. Persist each cursor atomically with the state it represents; do not double-apply replayed events. Validate the complete transcript against authoritative reads after disconnects and process death.
- `packages/server/src/auth.ts` still supports the server username/password configuration. Evaluate native credential storage and remote exposure separately from visual reuse.

The source supports a better reconnect design than the initial V1-oriented research. It does not prove our recovery implementation, cloud tunnel behavior, or background behavior works. Runtime acceptance remains required.

## T3 Code: useful implementation patterns

T3 already has a React Native/Expo mobile app. `apps/mobile/README.md:3` says it is in development and not yet distributed. Its code/tests are useful evidence of implementation choices, not proof of a reliable released mobile product.

| Source | Verified pattern | Proposed use |
|---|---|---|
| `apps/mobile/src/lib/scopedEntities.ts:3` | Environment-scoped projects, threads and approvals | Machine-qualified keys, including every approval action |
| `apps/mobile/src/state/workspaceModel.ts:80`; `workspaceModel.test.ts:110` | Cached content availability is separate from connection readiness | Show cached transcripts with honest offline/reconnecting status |
| `apps/mobile/src/connection/app-state-wakeups.ts:3`; `packages/client-runtime/src/connection/supervisor.ts:395` | Foreground recovery distinguishes short absences from longer suspended socket leases | Native Android lifecycle recovery; measure thresholds instead of copying its 10-second constant blindly |
| `apps/mobile/src/state/thread-outbox-model.ts:47`; `use-thread-outbox-drain.ts:1260` | Versioned outgoing records; durable storage before dispatch; destination/editor race checks after awaits | Persist send intent and protect against wrong-host sends; enable retries only after OpenCode deduplication is proven |
| `apps/mobile/src/state/use-thread-outbox-drain.test.ts:399` | Acknowledged-send cleanup failure retries cleanup, not delivery | Port this failure scenario to the Kotlin suite |
| `packages/client-runtime/src/connection/compatibility.ts:9` | Explicit protocol incompatibility and directional update guidance | Tell users whether the app or host needs an update |
| `apps/mobile/src/features/threads/PendingApprovalCard.tsx:21` | Request-specific options, warnings, disabled actions while submitting | Native approval component bound to OpenCode's real permission enums |
| `apps/mobile/src/features/threads/ThreadFeed.tsx:2608` and `:2855` | Scroll anchoring, end-follow and keyboard positioning are explicit behaviors | Never pull a reader away from older content as new output arrives |

Additional references: `apps/mobile/src/features/connection/pairing.ts:30` handles QR/manual pairing inputs; `apps/mobile/src/persistence/mobile-secure-storage.ts:22` abstracts secure storage. Do not inherit automatic HTTP selection for IP addresses into public tunnel setup.

Do not import T3's relay/account infrastructure, orchestration protocol, Effect runtime or WebSocket transport wholesale. They solve T3's system. Our transport must follow OpenCode V2, and Android secure storage/lifecycle must be native.

## Implementation order

1. Native visual proof using the pinned V2 tokens: conversation/composer, expanded tool details, changes, machine switcher and permission request. Review at phone sizes with keyboard open and large text.
2. Real V2 protocol spike on a disposable project: health, sessions, prompt admission, global deltas, durable replay, permissions, interrupt and source-of-truth reconciliation. Pin a release after this passes.
3. Kotlin event/state layer and translated source fixtures, then connect the approved screens.
4. Two-host and disconnect/process-death tests. Resolve whether built-in OpenCode access plus tunnel authentication is sufficient before implementing a separate host companion.

This review supersedes the generic original-visual-design direction and the V1-oriented endpoint assumptions in the initial proposal. It does not expand scope to iOS or authorize infrastructure provisioning.
