# Implementation decisions

Product scope and open decisions remain authoritative in [PLAN.md](PLAN.md). This log records implementation choices and their evidence; it does not imply visual acceptance or production deployment.

| Date | Decision | Rationale and alternatives |
|---|---|---|
| 2026-09-27 | Continue through an installable native Android testing candidate, with OCR-reviewed PRs merged after clean checks | Carter explicitly authorized autonomous implementation, PR review and clean merges. Stop-at-each-PR approval is no longer required; cloud provisioning and real-host mutations are not incidental build steps. |
| 2026-09-27 | Use GPT-6 Sol workstreams with independent review and coordinated build ownership | Carter requested Sol orchestration. Protocol, finite transport and streams have separate file ownership; integration owns Gradle and runtime probes. |
| 2026-09-27 | Reuse the current HTTPS destination contract across finite requests and separately owned streams | One captured origin/generation avoids routing ambiguity. Streams need a different lifetime policy from finite calls; a generic networking framework is unnecessary. |
| 2026-09-27 | Testing-candidate readiness means connected emulator and disposable-host evidence plus an installable APK and honest setup limits | Physical phone and production-host acceptance belong to Carter's testing. No claim of production readiness, verified physical hardware or provisioned Cloudflare access follows from local tests. |
