# Native OpenCode companion

A native Android app for working with OpenCode on your own computers. Kotlin and Jetpack Compose, with OpenCode V2's UI/UX adapted for a phone.

**Current state:** M0 Android scaffold and a debug-only synthetic visual proof. Local formatting, state tests, Android lint and debug/unsigned release assembly passed in the pinned Nix shell. The debug APK installed and launched on an isolated API 36 emulator; a clean source export also built successfully. Phone-layout acceptance remains pending. Isolated real-host admission, replay, execution, request and basic diff probes pass; the Android app has no live connection, published release or provisioned service. The product name is a working description.

## Read the project

| Document | Purpose |
|---|---|
| [PLAN.md](PLAN.md) | Product scope, requirements, milestones, decisions and next work |
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

The project uses a checked-in Gradle wrapper and the pinned Nix development shell described in [Android toolchain](docs/TOOLCHAIN.md). From the repository root, enter the shell with `nix develop .`. The local M0 check ran with:

```bash
nix develop . --command ./gradlew --no-daemon spotlessCheck :protocol:test :client:test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The JVM suites cover client send-state rules, protocol JSON/SSE boundaries and read-only HTTPS transport, including captured 1.18.32 responses. See the [M1 transport report](docs/evidence/M1/transport/REPORT.md) for current checks and limits. `:app:assembleRelease` produces an unsigned, disconnected shell, not a distributable release. The CI workflow contains the same checks but has not run remotely. See [M0 evidence](docs/evidence/M0/REPORT.md) for observed commands and remaining gates. `./scripts/emulator.sh` booted the isolated API 36 emulator from the dev shell; APK install and cold launch passed.

`references/opencode/` and `references/t3code/` are local research checkouts, not application modules or build dependencies. Preserve their pinned contents. Reference commits and source links are recorded in [UPSTREAM-REUSE.md](UPSTREAM-REUSE.md).

Studying T3 does not make T3 a dependency. Record provenance and retain applicable notices when code or assets are actually copied or substantially translated. An MIT project license is prepared pending confirmation before source publication; third-party notices retain their own terms.
