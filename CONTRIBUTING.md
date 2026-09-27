# Contributing

This is an early native Android OpenCode companion. Read [PLAN.md](PLAN.md), [AGENTS.md](AGENTS.md) and the relevant architecture and verification sections before changing behavior. The app is currently a disconnected visual proof; protocol probes and JVM tests do not establish a usable remote client.

Use focused branches and pull requests. Include the problem, resulting behavior, observed verification, and remaining limitations. Follow the [OpenCodeReview workflow](docs/CODE_REVIEW.md) for each code PR and resolve substantive findings before merge.

Use the pinned Nix shell and Gradle wrapper:

```sh
nix develop . --command ./gradlew --no-daemon spotlessCheck :protocol:test :client:test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Keep tests deterministic and use disposable hosts/repositories for integration probes. Never include real credentials, private source captures, signing keys or generated build output. Preserve third-party provenance and notices. New phone layouts need concrete review artifacts before production implementation.
