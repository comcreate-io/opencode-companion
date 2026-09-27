# Pull request reviews

Use [Alibaba OpenCodeReview](https://github.com/alibaba/open-code-review) for every code PR. The project uses its **delegation mode**: OCR selects files and resolves review rules; the reviewing agent examines the diff and surrounding code. No additional model endpoint or provider credential is needed. Running preview alone is not a review.

The Linux x86_64 launcher pins official release **v1.12.9** with SHA-256 `9105c7081b8362a1cb0167f1ddfd1437e147a0e3c9a5af89c03c8859a5b0f0e8`. It downloads into ignored `.android-local/review/bin/` and verifies the binary before every execution. There is no global install, production dependency, or automatic upgrade. Other platforms require a separately verified official release asset; do not bypass this checksum.

## Review procedure

1. Fetch the PR base and head, record their exact commit IDs, and review a clean checkout of that head. Never run untrusted PR scripts with repository secrets.
2. Run `./scripts/ocr.sh delegate preview --from <base-sha> --to <head-sha> --format json`. Keep the returned merge base, file statuses and exclusions.
3. Run `./scripts/ocr.sh delegate rule --format json <paths...>` for every reviewable file, in bounded batches. Read the diff from the returned merge base to the reviewed head and enough surrounding source to verify behavior. Use the rules plus this project's machine, replay, send and credential invariants.
4. Account for every `(path, status)` pair as reviewed or skipped with a concrete reason. Inspect excluded changed files separately when they affect builds, safety or documentation; do not let a tool's default filter hide a required check.
5. Report total/reviewed/skipped file counts, coverage, exact reviewed commits, observed tests, limitations, and actionable findings with file/line, severity and evidence. Verify findings before posting. Preview/rule resolution has no quality verdict.
6. Fix substantive findings and rerun affected tests. New commits require review of the changed scope. Keep a final review record on the PR. Human approval and branch checks remain separate from this tool's output.

Before the initial commit, use workspace mode (`delegate preview --format json`) and read untracked files in full. Local review artifacts belong under `.android-local/review/`; publish only a sanitized summary with relevant findings and coverage. Do not commit provider configuration, logs containing source from unrelated projects, or review session caches.

The [upstream delegation workflow](https://github.com/alibaba/open-code-review/blob/v1.12.9/skills/open-code-review-delegate/SKILL.md) documents the tool contract. If OCR cannot run, report the blocker and complete independent review where possible; never label that fallback as a completed OCR review.
