# Android toolchain

M0 local build checks, clean source-export assembly and emulator APK install/launch passed in the pinned shell. See the [M0 evidence report](evidence/M0/REPORT.md) for observed results. The CI workflow is configured but has not run remotely.

| Input | Pin |
|---|---|
| Nixpkgs | `f13ff45afd1bb73e640eaa08a7066dbed07e3238` plus `flake.lock` |
| Java | Nix `jdk17`; local runtime reported `17.0.20+8` |
| Gradle | `9.3.1`, wrapper and distribution checksums verified against Gradle publications |
| Android Gradle plugin | `9.1.1`, built-in Android Kotlin support |
| Kotlin JVM / Compose compiler | `2.2.10` |
| Compile / target SDK | `36` |
| Minimum Android API | `28` provisional until physical device is selected |
| Build tools | `36.0.0` |
| Compose BOM | `2025.08.00` |
| Activity Compose | `1.10.1` |
| Test runtime | JUnit `4.13.2` |
| Formatter | Spotless `7.2.1` with ktfmt `0.58` |
| Android Debug Bridge | Local SDK reported `37.0.1` |

`flake.nix` explicitly enables acceptance of the Android SDK license for the selected packages. Android SDK tooling is unfree; source and package definitions remain pinned. No system configuration is changed and no global tool is installed. The initial full shell includes an emulator and API 36 Google APIs x86_64 image, so its first realization downloads substantial data.

The `.envrc` uses `use flake`; the project has its own Git root. Run commands from that root. The shell supplies the SDK, JDK and Nix-compatible AAPT2 override; do not write a machine-specific absolute SDK path into source.

The wrapper JAR checksum is stored in `gradle/wrapper/gradle-wrapper.jar.sha256`; the distribution checksum is in `gradle-wrapper.properties`. Dependencies/plugins are pinned in `gradle/libs.versions.toml`. No Android SDK download is delegated to an imperative global installer.

`:protocol` and `:client` are JVM modules in M0 because their current types/rules need no Android APIs. The client will become an Android library when platform storage/credential adapters arrive; do not add that dependency before it is used.

Development package ID is `dev.local.opencodecompanion.debug`; it is temporary and separate from a future distribution identity. Debug sources contain explicit synthetic UI fixtures. Release sources open a disconnected shell, without sample conversations or an active transport. No release signing key is configured.

Run the verified local build checks from the repository root with `nix develop . --command ./gradlew --no-daemon spotlessCheck :protocol:test :client:test :app:lintDebug :app:assembleDebug :app:assembleRelease`. The original M0 check had four client tests and no protocol test sources; [M1 execution evidence](evidence/M1/execution/REPORT.md) records the expanded 34-test baseline. The emulator booted through `nix develop . --command ./scripts/emulator.sh`; installation and cold app launch passed on emulator-5558. The Android UI remains disconnected; no OpenCode release is yet enabled for app connections.

## Sources checked during setup

- [AGP 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [Built-in Kotlin in AGP 9](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose compiler setup](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
- [Gradle checksum publications](https://gradle.org/release-checksums/)

Backup exclusions cover all documented storage domains for cloud and device transfer; see [Android backup rules](https://developer.android.com/identity/data/autobackup). Runtime backup/restore acceptance belongs to the persistence milestone.

M1 adds Nix `python3` (observed 3.14.6) for the standard-library protocol probe and kotlinx.serialization-json 1.9.0 for strict Kotlin wire parsing. Runtime evidence: [M1 report](evidence/M1/REPORT.md).

M1 read-only HTTP uses OkHttp 5.3.2 and kotlinx.coroutines 1.10.2; matching MockWebServer/TLS fixtures are test-only. [Transport evidence](evidence/M1/transport/REPORT.md) records the final 45-test build and remaining device gates.
