# Execution text fixture provenance

`text-live-events.json` and `text-history.json` are copies of the isolated official OpenCode
1.18.32 loopback execution capture in
[`docs/evidence/M1/execution/run-6`](../../../../../../../docs/evidence/M1/execution/run-6).
The execution report at `docs/evidence/M1/execution/REPORT.md` records the runtime identity,
disposable provider and capture method. The text decoder tests use these captured events;
identity mutations and replay ordering in tests are synthetic fault cases.
