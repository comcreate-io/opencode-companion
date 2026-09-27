# Native OpenCode companion

A native Android app for working with OpenCode on your own computers. Kotlin and Jetpack Compose, with OpenCode V2's UI/UX adapted for a phone.

**Current state:** candidate **0.1.1-dev-candidate** (version code 2). All 14 native fixture checks passed on Pixel 8 Pro (Android 16/API 36) over USB-forwarded HTTPS, using the unchanged 0.1.1 app APK from source `686367d65f4da6744f8988ea60b1bebfce5e620b`; installed APK hash read-back matched. The four core checks used test-helper commit `e15d30aea20533ff341748cff790c18e7f89f593` after a setup synchronization fix; the other ten used the original test APK. No application code changed. See [Pixel evidence](docs/evidence/M3/accessibility/pixel/REPORT.md) for exact artifacts, the initial core setup failures and successful rerun, and [Testing handoff](docs/TESTING.md) for installation. Earlier build/JVM/emulator results are recorded in [accessibility evidence](docs/evidence/M3/accessibility/REPORT.md); the 25 Pixel storage/Keystore checks belong to the historical 0.1.0 baseline and were not rerun in this validation. Actual-host/tunnel, paid-provider, Wi-Fi/cellular, manual TalkBack, user layout approval, minimum-API and landscape acceptance remain open. Exactly OpenCode 1.18.32 and per-machine shared-password acknowledgement remain required; no service was provisioned.

## Read the project

| Document | Purpose |
|---|---|
| [PLAN.md](PLAN.md) | Product scope, requirements, milestones, decisions and next work |
| [Remaining build map](docs/BUILD_MAP.md) | Parallel workstreams, dependencies and the first usable beta gate |
| [Architecture](docs/ARCHITECTURE.md) | Module boundaries, state, transport, persistence and trust model |
| [UX specification](docs/UX.md) | Screen map, source fidelity, interactions and visual acceptance |
| [Testing handoff](docs/TESTING.md) | Candidate artifact, installation, checks and recovery limits |
| [Host setup](docs/HOST_SETUP.md) | Candidate host configuration, security limits and pending remote acceptance |
| [Verification](docs/VERIFICATION.md) | Failure scenarios, quality checks and release evidence |
| [AGENTS.md](AGENTS.md) | Rules for writing, reviewing and maintaining this codebase |
| [Contributing](CONTRIBUTING.md) | Build checks and pull request expectations |
| [Code review](docs/CODE_REVIEW.md) | Pinned Alibaba OpenCodeReview workflow |
| [Upstream reuse](UPSTREAM-REUSE.md) | Commit-pinned OpenCode/T3 findings and reuse candidates |
| [Initial direction](DIRECTION.md) | Earlier proposal retained for context; detailed plan now takes precedence |

## Product commitments

- Android/Kotlin first. Swift/iOS only if coworkers want it later.
- OpenCode V2 supplies the visual language and execution protocol.
- Multiple hosts, each retaining its own repositories, credentials and sessions.
- Recover correctly from disconnects and app restarts; never silently duplicate an uncertain command.
- A small, understandable codebase with explicit state and tests for important failure paths.
- Optional cloud development later; the initial app works with existing machines.

## Development status

The project uses a checked-in Gradle wrapper and the pinned Nix development shell described in [Android toolchain](docs/TOOLCHAIN.md). From the repository root, enter the shell with `nix develop .`. Run the current combined checks with:

```bash
nix develop . --command ./gradlew --no-daemon spotlessCheck :protocol:test :client:test :client:lintDebug :client:assembleDebugAndroidTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The JVM suites cover protocol boundaries, persistence-independent send/recovery rules and HTTPS transport. Historical boundary checks remain in the [session transport report](docs/evidence/M1/session-transport/REPORT.md) and [foundation report](docs/evidence/M3/foundations/REPORT.md); the [connected report](docs/evidence/M3/connected/REPORT.md) records the historical 0.1.0 baseline, and the [accessibility report](docs/evidence/M3/accessibility/REPORT.md) records current 0.1.1 checks. Release source now uses the connected app, but unsigned release assembly is not a signed or accepted distributable. The combined command above is the verification contract, not a claim that every final check has completed on the current revision. The same-origin “Update password” flow preserves local history/drafts and blocks replacement while sends are unresolved; local rotation platform and native checks passed; actual-host rotation acceptance remains open. See [host setup](docs/HOST_SETUP.md).

`references/opencode/` and `references/t3code/` are local research checkouts, not application modules or build dependencies. Preserve their pinned contents. Reference commits and source links are recorded in [UPSTREAM-REUSE.md](UPSTREAM-REUSE.md).

Studying T3 does not make T3 a dependency. Record provenance and retain applicable notices when code or assets are actually copied or substantially translated. Original project code is copyright (c) 2026 Carter McCann and licensed under the [GNU General Public License v3.0 only](LICENSE) (`GPL-3.0-only`). Third-party components retain their own terms and notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
