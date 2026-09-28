# Pixel 0.1.1 native verification

Observed 2026-09-27 on Carter's unlocked USB Pixel 8 Pro, Android 16 / API 36. **All 14 native cases passed on the unchanged 0.1.1 application APK.** Its installed hash was read back before and after testing and matches the [candidate handoff](../../../../TESTING.md): `bd2f3c0ef2229d4d15579d2d0bbfc5832003ffb4d45e20a609821343c2be24a9`. Application source remains `686367d65f4da6744f8988ea60b1bebfce5e620b`; version code is 2. No production source, schema, credentials or dependency changed in this wave.

The host is official OpenCode 1.18.32 in disposable repositories behind generated HTTPS, with a deterministic loopback provider. Owned ADB reverse mappings carried phone traffic over USB. Test-only certificate trust retained hostname verification; production uses system trust. Each suite used a private fixture database, separate from ordinary profiles and drafts. Updates used `install -r`, with no uninstall or data clear. The fixture runner kept resumed test windows awake without changing the phone's lock policy.

## Results and provenance

| Suite | Passing cases | Evidence |
|---|---:|---|
| Core: prompt/read tool, draft/recreation/changes, question reply, interrupt, reading anchor | 4 | [core](core.txt) |
| 2× font, light/dark, actual keyboard, preserved long draft, full Answer/Send bounds | 2 | [layout](layout.txt) |
| Colliding question IDs across machines and sessions clear selected/custom answers | 2 | [question isolation](question.txt) |
| Colliding session IDs across two hosts retain separate drafts | 1 | [two hosts](twohost.txt) |
| Separate-process draft and credential recovery | 2 | [process](process.txt) |
| Wrong/correct replacement password preserves draft and verifies readiness | 1 | [credential](credential.txt) |
| Native permission reply plus authenticated host-side empty-pending assertion | 1 | [permission](permission.txt) |
| Seven foreground/surface pairs in both themes meet 4.5:1 | 1 | [contrast](contrast.txt) |

[Machine-readable checks](checks.json) preserve the application and test APK identities for each run. Core ran with the strengthened helper at `e15d30aea20533ff341748cff790c18e7f89f593`, test APK SHA-256 `ff7efd485a8e4278825c2b307da99f8377ab8b619db2318d08adfd3350665987`. The other ten cases had already passed with the unchanged instrumentation from `686367d`, SHA-256 `35a3feee80a09b295271910fba1dd1519ffb4b45abfc148b7d675bfc8511d034`. Only ConnectedHostTest changed, so all four affected core cases were rerun. These are separate invocations, not one fourteen-test instrumentation run. Durations use the phone's decimal-comma locale.

## Initial failure and repair

The first fixture launch was rejected before installation because the Nix shell's temporary directory did not match the runner's strict `/tmp/companion-android-fixture-*` boundary. Restarting the owned hosts with `TMPDIR=/tmp` fixed the launch configuration; validation was not relaxed.

The [initial core run](initial-core-failure.txt) passed three cases and failed the reading-position case during machine setup. The failure capture showed populated credentials, unchecked acknowledgement and disabled Save. Save clears the password synchronously, so its callback had not run. The helper nevertheless continued because an older selected machine was already Ready. The exact lost-click timing is not proven by that capture; asynchronous keyboard movement was a plausible trigger.

The test helper now waits for the actual IME to be hidden with zero bottom inset, asserts acknowledgement off → one click → on, requires Save enabled before its single click, and awaits a newly created matching machine identity, selected and authenticated Ready. It uses no click retries, direct coordinator mutation, sleeps or weakened reading assertions. The final core run passed all four cases, including the reading anchor within its original 4-pixel tolerance. No production fix was needed.

The test-only change passed independent OCR-assisted review. Formatting, instrumentation assembly and app lint passed in the pinned shell; the application APK hash remained unchanged. The prior [109 JVM/emulator/build evidence](../REPORT.md) remains scoped to its recorded source. Final PR CI and review are separate recorded gates, not inferred from this device run. Storage code was unchanged; the older 25 platform cases were not rerun here.

## Screenshots and cleanup

Synthetic screenshots: [light question and keyboard](light-question-ime.png), [dark question and keyboard](dark-question-ime.png), reading [before](reading-before.png) and [after recreation](reading-after.png). They demonstrate the bounded assertions above, not Carter's visual approval. Test-local theme overrides do not update Activity/system bars; these captures do not establish production system-bar appearance or full phone-layout acceptance.

[Cleanup verification](cleanup.txt) records the installed APK hash, removal of test reverse mappings, unchanged accessibility configuration/global font scale, and ordinary app launch without clearing data. Both owned hosts were stopped and their private ready files removed. TalkBack was not enabled or changed.

## Still open

Actual HTTPS host/disposable-project selection, shared-password acknowledgement, named tunnel, real provider execution, Wi-Fi/cellular recovery, manual TalkBack, broader Android versions/orientations, and Carter's phone-layout approval remain open. USB fixture success does not establish any of those. Activity-recreation reading restoration is distinct from process-death selected-session restoration; the separate process test proves durable draft and credential recovery only.
