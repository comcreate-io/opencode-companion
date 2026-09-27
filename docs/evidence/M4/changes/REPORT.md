# Bounded working-tree diff observations

2026-09-27: `scripts/probe-diff.py` passed against the disposable HTTPS host from `scripts/android-host-fixture.py`, using official OpenCode 1.18.32 SHA-256 `513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080`.

The strict TLS client trusted only the generated fixture certificate. Both model and host listeners were loopback-only. Git fixtures were created below the harness's disposable temporary directory; no real repository or paid provider was used. The ready file contains synthetic credentials and is private test input, not published evidence.

Observed `GET /vcs/diff?mode=git&directory=<absolute directory>` behavior:

- Clean Git tree: empty array.
- Explicit directory selected a second Git repository; the host's original repository stayed empty. Changes did not leak across directory queries.
- Binary modification: explicit `Binary files ... differ` patch and zero textual line counts.
- Rename: separate added destination and deleted source entries, not a rename status.
- Deletion: deleted status and deletion patch.
- Added file with 5,000 lines: 124,018 patch characters and final line present. Only a measured summary is retained here instead of 5,000 repetitive evidence lines.
- Non-Git directory: empty array. This endpoint alone cannot distinguish non-Git from a clean Git tree.

The source lead is `references/opencode/packages/opencode/src/server/routes/instance/httpapi/middleware/workspace-routing.ts` (`directory` query), verified here against the pinned runtime. This probe tests the host route and HTTPS harness; it does not establish Android presentation or remote tunnel behavior.
