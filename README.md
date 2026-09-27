# Native OpenCode companion

A native Android app for working with OpenCode on your own computers. Kotlin and Jetpack Compose, with OpenCode V2's UI/UX adapted for a phone.

**Current state:** M0 Android scaffold and a debug-only synthetic visual proof. Local formatting, state tests, Android lint and debug/unsigned release assembly passed in the pinned Nix shell. The debug APK installed and launched on an isolated API 36 emulator; a clean source export also built successfully. Phone-layout acceptance remains pending. Isolated real-host admission, replay, execution, request and basic diff probes pass; the Android app has no live connection, published release or provisioned service. Room persistence, Keystore credentials and captured durable transcript replay are implemented; see [foundation evidence](docs/evidence/M3/foundations/REPORT.md). The product name is a working description.

## Read the project

| Document | Purpose |
|---|---|
| [PLAN.md](PLAN.md) | Product scope, requirements, milestones, decisions and next work |
| [Remaining build map](docs/BUILD_MAP.md) | Parallel workstreams, dependencies and the first usable beta gate |
| [Architecture](docs/ARCHITECTURE.md) | Module boundaries, state, transport, persistence and trust model |
| [UX specification](docs/UX.md) | Screen map, source fidelity, interactions and visual acceptance |
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

The JVM suites cover client send-state rules, protocol JSON/SSE boundaries and read-only HTTPS transport, including captured 1.18.32 responses. See the [foundation report](docs/evidence/M3/foundations/REPORT.md) for current platform checks and remaining gates. `:app:assembleRelease` produces an unsigned, disconnected shell, not a distributable release. The prior baseline passed in [remote CI for PR #1](https://github.com/comcreate-io/opencode-companion/actions/runs/36327268835). See [M0 evidence](docs/evidence/M0/REPORT.md) for observed commands and remaining gates. `./scripts/emulator.sh` booted the isolated API 36 emulator from the dev shell; APK install and cold launch passed.

`references/opencode/` and `references/t3code/` are local research checkouts, not application modules or build dependencies. Preserve their pinned contents. Reference commits and source links are recorded in [UPSTREAM-REUSE.md](UPSTREAM-REUSE.md).

Studying T3 does not make T3 a dependency. Record provenance and retain applicable notices when code or assets are actually copied or substantially translated. Original project code is copyright (c) 2026 Carter McCann and licensed under the [GNU General Public License v3.0 only](LICENSE) (`GPL-3.0-only`). Third-party components retain their own terms and notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
