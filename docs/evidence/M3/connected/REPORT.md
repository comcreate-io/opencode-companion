# Connected Android candidate evidence

Observed 2026-09-27. This report distinguishes an implemented connected client from physical-device and production-host acceptance. The tested host is the official OpenCode 1.18.32 Linux binary in a disposable Git repository, with a deterministic loopback model provider and generated TLS certificate. No paid provider or real working repository is used.

## Observed checks

- Final combined build: formatting, 46 protocol tests and 63 client JVM tests passed, with zero failures or skipped tests. Debug/unsigned release and both instrumentation APK assemblies passed. Client lint has no issues; app lint has zero errors and 15 dependency-update warnings. A scroll-state composition warning was fixed with snapshotFlow, then app checks were rerun.
- Actual API 36 Android storage/Keystore instrumentation: 25 tests passed on both the isolated emulator and Carter's USB Pixel 8 Pro (Android 16), including schema 1 to 2 migration, same-ID origin-change rejection, and credential-replacement crash windows. [Emulator output](platform-instrumentation.txt); [Pixel output](pixel-platform.txt).
- Native HTTPS flow: all three tests passed in the same instrumentation suite: create a real session, prompt/read tool output, durable draft through Activity recreation, changes view; answer a real pending question; interrupt and remain connected. [Runner output](native-instrumentation.txt).
- Independent OCR-assisted review found and closed draft-write/send races, stale machine updates, duplicate mutation taps, request revalidation and explicit unknown-action recovery. Credential service, coordinator integration and native harness also passed independent review. Final exact-commit review is still pending.

Screenshots are observed emulator output, not approved phone layouts: [read and draft](read-draft.png), [question](question.png), [changes](changes.png).

## Boundaries

The instrumentation application trusts only the generated disposable certificate and retains hostname verification. Production uses system TLS trust and contains no fixture application, model, certificate or fallback transcript. Host password acknowledgement is explicit for each configured machine. The exact host release gate rejects anything other than 1.18.32.

The app persists intent before dispatch, keeps unknown sends unresolved, and never automatically resends them. Admission proof and revision-qualified draft cleanup are separate. Stop/request replies remain pending until authoritative host state clears them; explicit Recheck performs reads only and permits a separate deliberate retry. It does not prove the first action failed.

Additional emulator checks passed: [two-host colliding-session draft isolation](two-host.txt), [separate-process draft and credential recovery](process-recovery.txt), [wrong/correct replacement password preserving a draft](credential-replacement.txt), and [native permission reply](permission-reply.txt) with an authenticated host-side empty-pending assertion. Final Pixel UI runs are in progress. The initial Pixel UI run found the transitive Espresso 3.5.0 reflection incompatibility; test-only 3.7.0 fixes it. The next run passed interruption but the phone locked before remaining scenarios; this is not counted as a full pass. Full physical UI acceptance, Wi-Fi/cellular transitions, a named Cloudflare Tunnel, long-history performance, TalkBack and Carter's layout acceptance remain unverified. File attachments, slash commands, arbitrary file browsing, terminal, cloud provisioning and iOS are outside this candidate's implemented surface.
