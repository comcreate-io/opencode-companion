# Pixel native fixture verification

Observed 2026-09-27 on Carter's USB Pixel 8 Pro, Android 16 / API 36. All eight native checks passed: three core tests, one two-host test, two separate-process recovery stages, one credential-replacement test and one permission-reply test. Each target JSON records the same application APK SHA-256 `92aeb5ba58b5fdd355b4131e07a1cc7af3996786d03a5881aa7e16d42f166c31` and instrumentation APK SHA-256 `d5a52f019d7b4e0406aaaf4c18e826bce77572449a92140456f09dcf57d4e3b5`. Application source is `10901c0`; instrumentation helper source is included in merged PR #4 (`1f6f752`). No app code changed for this run.

The official OpenCode 1.18.32 binary ran in two disposable repositories with generated passwords/certificates and a deterministic local provider. The phone reached them through owned ADB reverse mappings to loopback HTTPS. The test application trusted only the fixture certificates and retained hostname validation; production trust is unchanged. Unique test databases separated every suite from ordinary app profiles/drafts. Updates used `install -r`; no app data was cleared, no lock settings changed and no real host credentials were used. The test-only keep-screen-awake flag applied during resumed test activities.

## Evidence

| Suite | Passing tests | Log | Target identity |
|---|---:|---|---|
| Prompt/read output, draft/activity recreation, changes view, question reply, interrupt | 3 | [core](core.txt) | [core target](core-target.json) |
| Colliding session IDs across two hosts preserve distinct drafts | 1 | [two-host](twohost.txt) | [two-host target](twohost-target.json) |
| Draft and credential survive separate instrumentation processes | 2 | [process recovery](process.txt) | [process target](process-target.json) |
| Wrong then correct password replacement preserves draft | 1 | [credential replacement](credential.txt) | [credential target](credential-target.json) |
| Allow once clears real pending permission and remains ready | 1 | [permission](permission.txt) | [permission target](permission-target.json) |

The runner also authenticated to the host after the permission test and asserted its pending-permission list was empty. Instrumentation durations use the device locale's decimal comma. Synthetic screenshots: [read and draft](read-draft.png), [pending question](question.png), [changes](changes.png). These captures establish observed rendering, not Carter's layout acceptance or TalkBack coverage.

The existing `scripts/run-connected-tests.py` runner was invoked in the project Nix shell with an explicitly selected USB serial and two private ready files produced by `scripts/android-host-fixture.py`. Five runs used default core mode, `--second-ready`, `--process-recovery`, `--credential-replacement`, and `--permission-reply`, each with its own unused output directory. Use `--help` for arguments. Ready files and generated credentials remain outside committed evidence.

[Post-test cleanup](cleanup.txt) confirms the installed APK hash, absence of reverse mappings and ordinary app launch without clearing data. Both owned fixture processes exited cleanly and removed their temporary inputs.

This closes the previously blocked physical fixture UI run. It does not establish Wi-Fi/cellular recovery, a public tunnel, paid-provider behavior, long-history performance, accessibility or broader Android-version support.
