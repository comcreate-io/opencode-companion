# Read-only HTTPS transport evidence

Observed 2026-09-27. `:client` now has a Kotlin HTTP boundary for health, session discovery and finite history reads. The Android UI remains disconnected, and supported remote capabilities remain empty.

## Behavior

`ReadDestination` captures machine, HTTPS origin, credential generation and a call-scoped authorization value. Credentials are excluded from its diagnostic string. URL credentials, paths, fragments, query strings and non-HTTPS origins are rejected. History reads validate machine identity and reject unsafe session path segments before dispatch.

The transport disables redirects, connection retries, inherited interceptors/authenticators, cookies and caching. It uses explicit timeouts and a 1 MiB response limit; malformed UTF-8 and wire responses fail explicitly. Coroutine cancellation cancels the underlying OkHttp call. Responses close on success, failure and cancellation. Health success establishes reachability only, never protocol compatibility. Unknown durable history semantics remain `Unsupported`, with no cursor persisted.

## Observed verification

```sh
nix develop . --command ./gradlew --no-daemon spotlessCheck :protocol:test :client:test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Final pin: OkHttp 5.3.2, coroutines 1.10.2. **45 tests passed**: 30 protocol, 4 outgoing-state and 11 HTTPS transport tests; zero failures/errors/skips. Formatting, Android lint and debug/unsigned release assembly passed. Lint reports zero errors and 12 dependency-update warnings. [Check totals](checks.json) and [APK hashes](apk-sha256.txt) identify the observed artifacts.

The transport tests serve captured synthetic OpenCode payloads from disposable local HTTPS servers. They cover authentication failure, rejected redirects without credential forwarding, inherited interceptor removal, invalid HTML responses, oversized bodies, untrusted TLS, colliding IDs across machines, invalid origins/headers/path segments, and cancellation during headers and body streaming. Cancellation tests observe the underlying running-call count return to zero.

All three real-host probes were rerun after review hardening: 10 admission/replay groups, 7 deterministic execution groups and 5 permission groups passed. [Regression results](probe-regression.json) retain the outcomes and limitations. Python optimization is explicitly rejected before work begins; credential export guards no longer rely on assertions. Both `-O` and `PYTHONOPTIMIZE=1` rejection paths were checked, along with syntax and scoped Ruff F/E9 checks.

## Dependencies and review

[OkHttp 5.3.2](https://github.com/square/okhttp/blob/parent-5.3.2/CHANGELOG.md) includes the timeout fix absent from 5.3.0/5.3.1. Coroutines 1.10.2 retains compatibility with the current Kotlin compiler. Both publish Apache-2.0 license metadata: [OkHttp POM](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/5.3.2/okhttp-5.3.2.pom), [coroutines POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.10.2/kotlinx-coroutines-core-1.10.2.pom). MockWebServer and test TLS support are test dependencies only.

Alibaba OpenCodeReview v1.12.9 supplied file selection and language rules for independent review. The new transport source, tests and dependency files were reviewed after fixes, with content hashes recorded locally. The review found no remaining material issue in that bounded slice; it does not establish device or cloud behavior.

## Limits

No live Android connection, Keystore adapter, database, network SSE stream, sending, cloud tunnel, credential rotation or full transcript is implemented here. HTTPS fixture tests do not prove a real remote OpenCode deployment. The current history codec supports admission events only; real execution histories can correctly return `Unsupported`. The app still has no INTERNET permission and exposes no connection UI.

Next: durable drafts/outgoing intent storage and full durable event projection, followed by authenticated host integration and device lifecycle checks. Phone layout acceptance remains open.
