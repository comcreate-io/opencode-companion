# Initial foundation review

Observed 2026-09-27 using Alibaba OpenCodeReview v1.12.9 in delegation mode. The checksummed official launcher provided workspace file selection and language-specific rules; independent review examined the selected code and fixtures. This was a completed review, not merely a preview invocation.

The preview selected **112 files**, all reviewed, with **zero selected files skipped** (100% selected-file coverage). Files were tracked by path, status and SHA-256; final formatter changes were rechecked. The final count includes the narrow `.gitattributes` rule preserving required SSE blank-line delimiters, reviewed separately after the initial 111-file snapshot. The preview also excluded documentation, tests and binary evidence by default. Exclusions were audited separately: protocol/client tests and fixture provenance were reviewed, Markdown links were checked, fixture copies were compared byte-for-byte, and the Gradle wrapper checksum passed. The six existing synthetic screenshots were retained as historical M0 evidence; no new visual or device review is claimed.

The protocol batch reviewed 17 selected files plus nine excluded test/provenance/SSE files. The application/tooling batch reviewed 27 selected files. The transport batch reviewed source, tests and both dependency files. The remaining selected files were checked for state invariants, synthetic capture consistency, recorded outcomes, provenance and sensitive data. Overlapping dependency files were counted once in the final selected-file total. Raw rule output and working review artifacts remain local.

## Findings and resolution

- Probe evidence assertions could be disabled by optimized Python. All three probes now reject `-O` and `PYTHONOPTIMIZE=1` before parsing arguments, creating output or starting a host. Both paths were tested.
- The execution probe's credential export guard relied on an assertion. It now raises unconditionally; export guards and diagnostic redaction cover raw and encoded credential forms in all probes.
- Transport review prompted explicit header validation, immutable host scoping, safe session path validation, inherited interceptor removal and cancellation regressions. The final bounded transport review has no remaining material finding.

After fixes, all 22 real-host probe groups and all 45 Kotlin tests passed. Formatting, Android lint and both APK assemblies passed. Exact counts, hashes, dependencies and limitations are in the [transport report](REPORT.md).

This review does not grant merge approval or establish a connected Android client. A PR must record its exact base/head and recheck any subsequent changes using [the review procedure](../../../CODE_REVIEW.md). The public repository is bootstrapped; source publication and the initial PR await the project license decision.
