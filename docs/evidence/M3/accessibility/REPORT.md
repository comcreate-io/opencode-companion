# Reading, request isolation and accessibility regression evidence

Observed 2026-09-27 on the isolated `OpenCode_M0_API36` emulator, Android API 36. Source: `686367d65f4da6744f8988ea60b1bebfce5e620b`. Candidate: **0.1.1-dev-candidate**, version code 2. [Checks and artifact identities](checks.json); [installation handoff](../../../TESTING.md).

All 14 native cases and 109 JVM tests passed. This pass found and repaired reproducible defects; it does not establish real-network, physical-device or full accessibility acceptance. Only historical 0.1.0 has [Pixel evidence](../connected/pixel/REPORT.md).

## Defects reproduced before their fixes

| Observation before fix | Correction | Final evidence |
|---|---|---|
| After reading older output, Activity recreation removed the “New output” state and jumped to the end | Save the following state per selected session and remove the unconditional recreation reset | Core suite restores the old reading anchor within 4 pixels and retains “New output” |
| Reusing a question ID across machine/session identities retained a selected answer | Key selected/custom answers by the complete session key and request ID | Two focused composition tests clear both maps and disable Answer after either identity change |
| Save machine lacked Button semantics | Add action roles; expose model/agent selection and tool disclosure state | Layout checks, selected-model checks and tool Expanded/Collapsed assertions |
| Light input hint contrast was 3.5278766:1 | Adapt four V2 foreground tokens for native text | Fourteen foreground/surface combinations meet 4.5:1 across both themes |
| Answer was vertically clipped with 2× text and the real keyboard open | Bound composer height and allocate a scrollable request viewport from remaining space | Light/dark tests assert complete action bounds above the keyboard and preserve the full draft |

Red runs remain in ignored `.android-local/accessibility-red-reading`, `accessibility-red-layout`, `accessibility-red-contrast.txt`, `accessibility-question-red` and `accessibility-layout-3`. They respectively failed 1, 2, 1, 2 and 2 cases at the assertions described above. These are earlier mixed development builds, not failures of the final APK. Separate intermediate layout/core failures came from test helpers querying an off-screen machine row; the helpers now scroll the owning lazy list first. Existing behavior assertions were preserved. No failing case was skipped or weakened to obtain this result.

## Final native matrix

Every row uses the same application APK SHA-256 `bd2f3c0ef2229d4d15579d2d0bbfc5832003ffb4d45e20a609821343c2be24a9` and instrumentation APK SHA-256 `35a3feee80a09b295271910fba1dd1519ffb4b45abfc148b7d675bfc8511d034`.

| Suite | Cases | Observed result |
|---|---:|---|
| [Core](core.txt) | 4 | Real fixture session, prompt/read tool, draft/recreation/changes; question reply; interrupt; reading-position recreation |
| [Large text](layout.txt) | 2 | Light/dark at 2× font, real IME, long draft retained, 48dp action bounds, full Answer/Send bounds above keyboard |
| [Question isolation](question.txt) | 2 | Colliding request IDs across machine and session identities cannot retain selected/custom answers |
| [Two hosts](twohost.txt) | 1 | Same session ID on distinct hosts keeps drafts separate |
| [Process recovery](process.txt) | 2 | Separate instrumentation processes prepare/restore durable draft and credential reuse |
| [Credential replacement](credential.txt) | 1 | Wrong/correct replacement password preserves draft and requires authenticated readiness |
| [Permission](permission.txt) | 1 | Native reply and authenticated host-side empty-pending assertion |
| [Contrast](contrast.txt) | 1 | Seven actual foreground/surface pairs in each theme meet 4.5:1 |

The host is the pinned official OpenCode 1.18.32 binary in disposable repositories, with a deterministic loopback provider. Instrumentation trusts only the generated fixture certificate and retains hostname verification. The production application still uses system TLS trust. No paid provider, user project, cloud service or remote tunnel was used.

Observed screenshots: [light question/keyboard](light-question-ime.png), [dark question/keyboard](dark-question-ime.png), reading [before](reading-before.png) and [after recreation](reading-after.png). These contain synthetic content only and are evidence for review, not approved phone layouts. At 2× font with an open request and keyboard, older transcript content can yield all its height to the request/composer; request content remains scrollable. This does not prove every orientation or window size.

## Build and artifact verification

In the pinned Nix shell, the final combined command passed:

```bash
./gradlew --no-daemon spotlessApply :protocol:test :client:test :client:lintDebug :app:clean :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Subsequent `spotlessCheck` and `python3 -m py_compile scripts/run-connected-tests.py` passed. Protocol XML reports 46 tests and client `testDebugUnitTest` XML reports 63, with zero failures/errors/skips. Old output directories are not included in this count. Client lint has no issues; app lint reports zero errors and 15 dependency-update warnings. Debug, unsigned release and instrumentation assembly passed.

A fresh `git archive` of the source commit, built with `:app:assembleDebug`, produced the same APK hash using the same pinned tools and local debug signing key. This is observed reproducibility in this environment, not a cross-machine signing guarantee. Package remains `dev.local.opencodecompanion.debug`, minimum API 28, schema 2; the certificate SHA-256 remains `580fa46f785a93d6df37ead6796ae4c58cc92ca020f2419110b4e05f1285d886`. The new APK is packaged beside 0.1.0 locally; neither was publicly released.

Run native checks with `scripts/run-connected-tests.py --ready PRIVATE_READY_FILE --output NEW_OUTPUT_DIRECTORY` in the Nix shell. The default suite now contains four tests; additional flags are `--accessibility-layout`, `--question-isolation`, `--second-ready PRIVATE_SECOND_READY_FILE`, `--process-recovery`, `--credential-replacement` and `--permission-reply`, one mode per invocation. Keep ready files/generated credentials private. Run `ThemeContrastTest` through `FixtureTestRunner` separately; it needs no host arguments. Process recovery consists of two separate one-test invocations. Permission mode validates authoritative host state after instrumentation completes.

## Acceptance still open

- This 0.1.1 artifact has not been tested or installed on the Pixel. Its screen was locked during this wave; the previous installation, data, lock policy and accessibility service configuration were preserved.
- Actual HTTPS host/disposable project selection and explicit shared-password acknowledgement remain required. No actual-host credential was inferred or read.
- Wi-Fi/cellular switching, named tunnel, real provider, host sleep/restart over that route, manual TalkBack traversal and Carter's phone-layout approval remain unverified.
- Automated semantics/contrast checks do not prove spoken labels, focus order or full accessibility. Large-text layout tests require API 30+ and ran on API 36; minimum-API and landscape coverage remain open.
- Reading restoration proves Activity recreation with a retained ViewModel, not selected-session restoration after process death. Expanded tool disclosure state is not saved across recreation. The separate process tests cover durable draft/credential recovery only.
- No storage code changed in this wave. The earlier 25 platform tests are historical boundary evidence and were not rerun on this artifact.

Independent OCR delegation reviewed all nine code/UX/runner paths at the implementation commit with no skipped files and no confirmed blocker. Final evidence/documentation review and CI are recorded on the associated PR; that status is separate from the runtime observations above.
