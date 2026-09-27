# M0 foundation evidence

Observed 2026-09-26, America/Denver. Local work only; no Git commit/remote, publication, host credential or cloud service was created.

## Result

M0 local foundation checks passed. Kotlin/Compose Android app, two small JVM modules, a pinned Nix shell, checksummed Gradle wrapper, formatter, lint, state tests, CI skeleton and compatibility manifest are present. Tooling, native presentation and state rules received independent review before the build/device checks.

M1 is not implemented. No OpenCode runtime is supported yet. M2 has a thin synthetic native proof, not accepted production screens. Release opens a disconnected shell; debug fixture values live only in the debug source set. Neither variant requests INTERNET permission.

## Observed commands and results

```sh
nix develop . --command ./gradlew --no-daemon spotlessApply clean spotlessCheck :client:test :protocol:test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Clean rebuild: success, 109 tasks, 22 seconds. A final rerun after extending backup exclusions passed in 10 seconds. Four client tests, zero failures/errors/skips. `:protocol:test` is NO-SOURCE, not additional test coverage. Formatting passed. Lint has zero errors and seven dependency-update warnings; versions are intentionally pinned to the verified combination. No lint baseline/suppression was added. Nonblocking build warnings: Nix AAPT2 override is experimental; the graphics-path native library was packaged without stripping.

A fresh source export to `/tmp/opencode-m0-clean-7t6ll8lx`, assembled from Git's tracked/unignored file list and containing no project build outputs, passed `:app:assembleDebug` in the same shell: 40 tasks executed, 9 seconds. This verifies source isolation while reusing Nix/Gradle dependency caches. It is not a cold-cache build or remote CI run.

Tool observations: JDK 17.0.20+8, Gradle 9.3.1, adb 37.0.1, Android SDK/build tools 36/36.0.0. Full pins and setup are in [Toolchain](../../TOOLCHAIN.md). Wrapper JAR checksum matched the official publication. Both reference repositories retained their pinned commits and no tracked edits.

```sh
nix develop . --command ./scripts/emulator.sh
nix develop . --command adb -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
nix develop . --command adb -s emulator-5558 shell am start -W -n dev.local.opencodecompanion.debug/dev.local.opencodecompanion.MainActivity
```

API 36 Google APIs x86_64 emulator booted. Final APK install returned Success; final cold launch returned Status: ok, TotalTime 740 ms. This is one smoke observation, not a startup benchmark. App process remained live during navigation. No application AndroidRuntime fatal exception was observed. The pre-existing emulator-5556 was left untouched. Agent command cleanup killed the first detached emulator after its shell exited; subsequent verification held its owning shell open.

## Visual evidence

- [Conversation, light](conversation-light.png)
- [Conversation, dark](conversation-dark.png)
- [Composer with keyboard, dark](keyboard-dark.png)
- [Changes, light](changes-light.png)
- [Machines, dark](machines-dark.png)
- [Studio sessions, dark](studio-sessions-dark.png)

Screenshots use synthetic `.example.test` machines and projects. Manual emulator observations: conversation and changes navigation render; native keyboard leaves the composer visible; switching from laptop to studio updates context to studio/notes and lists only First session. UI hierarchy read-back confirms the filtered list. Independent review found and prompted the machine-scoping fix before final verification.

These images are review artifacts. Font is temporarily system sans; complete V2 typography, question flow, large-text/long-response/TalkBack/reduced-motion checks and Carter's phone-layout sign-off remain M2 work. Draft and approval fixtures are in-memory only. No screenshot proves real session isolation, durable sending or replay.

## Boundaries and next work

- Send intent rules are pure code with tests; persistence-before-dispatch is not wired to a database or transport yet.
- Backup XML excludes all storage domains, but no runtime restore test was performed.
- Debug APK is locally test-signed; release APK is unsigned. [APK checksums](apk-sha256.txt) identify the final local artifacts; neither was published.
- CI is configured and pinned but unexecuted remotely. Minimum API 28, product name/package identity and a physical test device remain provisional.
- Next: M1 isolated real OpenCode V2 contract spike and the remaining M2 visual acceptance cases. Cloud hosting, iOS and push remain deferred.

Final documentation check: all local links across 11 Markdown files resolve. The project emulator was shut down after verification.
