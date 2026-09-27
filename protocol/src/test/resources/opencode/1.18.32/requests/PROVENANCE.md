# Request fixtures

- `permission-pending.json` is the raw single pending permission object copied from `docs/evidence/M1/permissions/run-1/pending.json`. It was created through the synthetic permission API in a disposable session, not by an agent tool.
- `question-pending.json` is the raw pending question array copied from `docs/evidence/M1/execution/run-5/question.json`. It came from a real agent tool call using a deterministic loopback model and disposable repository.

Both were captured from the official OpenCode 1.18.32 binary during isolated local runtime probes. Tests wrap these raw shapes in the endpoint's `{ "data": ... }` response envelope.
