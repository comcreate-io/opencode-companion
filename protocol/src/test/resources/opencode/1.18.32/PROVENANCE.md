# Fixture provenance

Captured from official OpenCode 1.18.32 Linux x64 binary, SHA-256 `513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080`, 2026-09-26 America/Denver. See [M1 report](../../../../../../docs/evidence/M1/REPORT.md).

JSON fixtures were captured during the initial isolated exploratory probe. Only the disposable absolute repository directory was replaced with `/fixture/repo`; synthetic IDs, timestamps, nulls and cursor encodings were retained. SSE fixtures were captured by the reproducible run-1 probe; their session differs from the initial JSON fixture session. All prompts were synthetic admissions with `resume:false`. No auth headers, passwords, real prompts or host files are included.

Health, session/list, admission, conflict and history are actual API responses. Test mutations of these responses are synthetic fault cases. These fixtures do not certify future releases or successful model execution.
