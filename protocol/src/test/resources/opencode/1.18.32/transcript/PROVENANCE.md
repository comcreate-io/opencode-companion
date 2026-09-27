# Durable transcript fixtures

The four JSON files here are direct copies of the official OpenCode 1.18.32
isolated run-8 captures in `docs/evidence/M1/execution/run-8/`. The capture
method, pinned binary hash, disposable loopback provider, and redaction are
documented in `docs/evidence/M1/execution/REPORT.md`.

The tool-failure test derives a clearly marked synthetic mutation from the
captured read-tool success event. Its field shape follows the pinned
`references/opencode/packages/schema/src/session-event.ts` `Tool.Failed`
definition; no runtime tool failure was captured. Test mutations for malformed
or unsupported events are likewise synthetic.
