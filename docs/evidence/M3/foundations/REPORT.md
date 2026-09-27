# Connected-client foundations

Observed 2026-09-27. This implements the first parallel wave in the [build map](../../../BUILD_MAP.md): Room persistence, Android Keystore credentials and the observed durable transcript subset. The app remains disconnected. No remote capability, cloud service, UI direction or distribution release is enabled by this work.

## Implementation and invariants

- `:client` is now an Android library. Room 2.8.5/KSP 2.3.12 export schema version 1; no destructive migration fallback is configured. There is no previous shipped schema to migrate yet.
- Profiles retain canonical HTTPS origin, credential reference and monotonic generation. Outgoing intent captures its origin and generation; dispatch revalidates them transactionally. No storage method sends a network request. Admission/rejection transitions require the caller to establish exact correlated host evidence.
- Draft cleanup compares revisions and preserves an empty tombstone, preventing old cleanup from deleting a newly recreated draft. An interrupted dispatch reopens as unknown and cannot dispatch again. Acknowledged bookkeeping is separate from delivery.
- Supported durable events and cursor commit in the same SQLite transaction. The entire journal is validated through the transcript reducer. Unknown events, gaps, identity conflicts and unsupported transitions abort the batch. Per-session bounds are 4,096 events and 4,194,304 UTF-16 code units, not unlimited history or a measured memory budget. Paging/compaction remains future work.
- AES-256-GCM envelopes live in Android `noBackupFilesDir`, with machine/origin/generation authenticated. The key stays in Android Keystore. Rotation uses AtomicFile, explicit sync, committed-envelope readback and a process-wide lock; missing keys with existing envelopes fail closed. This is single-process ownership, not a multi-process storage contract. Hardware-backed key protection is not claimed.
- The transcript decoder handles the captured text, read, question and interrupted-step histories, plus explicitly synthetic failure/invalid-event cases. Transient deltas remain a separate overlay. This is a bounded observed subset, not support for every upstream event.

Independent review corrected database acquisition on cancellation, semantic JSON replay equality and AtomicFile false-success handling. Regression tests cover all three. Earlier integration review also corrected draft revision reuse, duplicate close ownership and stale-generation dispatch.

The Room profile pointer and Keystore envelope are separate durable stores. The future coordinator must reconcile interrupted rotation and revalidate scope before dispatch; this wave does not claim an atomic cross-store operation or live credential revocation.

## Verification

Final verification passed: 51 local tests (36 protocol, 15 client), 16 Android instrumentation tests, formatting, debug/unsigned release assembly and Android lint. Client lint reported no issues; app lint reported zero errors and 13 dependency-update warnings.

The combined local command is:

```sh
nix develop . --command ./gradlew --no-daemon spotlessCheck :protocol:test :client:test :client:lintDebug :client:assembleDebugAndroidTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Android tests are run explicitly on the isolated API 36 x86_64 project emulator, leaving unrelated emulator-5556 untouched:

```sh
nix develop . --command adb -s emulator-5558 install -r client/build/outputs/apk/androidTest/debug/client-debug-androidTest.apk
nix develop . --command adb -s emulator-5558 shell am instrument -w -r dev.local.opencodecompanion.client.test/androidx.test.runner.AndroidJUnitRunner
```

The Android library's test APK targets its own `dev.local.opencodecompanion.client.test` package. Key-loss injection checks that exact package before deleting its synthetic test-UID alias; it never targets the application UID or real credentials. Platform tests exercise actual SQLite and Android Keystore, not mocked replacements. Reopen tests are explicit close/reopen checks, not evidence of every process-kill boundary.

A fresh isolated OpenCode 1.18.32 execution probe also passed all seven groups (text/durable replay, read/question tools, interrupt and basic diff) using the deterministic loopback provider; [results](runtime-results.json) record the exact binary hash and limits. Observed local results and APK hashes are recorded in [checks.json](checks.json). CI additionally compiles the instrumentation APK; local device execution is separate evidence and is not falsely reported as a GitHub-hosted emulator run.

## Remaining gates

Session mutation/SSE transport, a foreground recovery coordinator, ViewModels and connected UI are still required. Actual host authentication/rotation, Cloudflare Tunnel setup, physical device and phone layout acceptance remain open. Large histories, database migration from future shipped versions, backup/restore on devices, real paid-provider execution and full multi-host lifecycle acceptance are not verified by these foundation tests. See [Verification](../../../VERIFICATION.md) for the full acceptance matrix.
