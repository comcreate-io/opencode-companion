# Catalog fixtures

`model-catalog.json` copies the sanitized `/api/model` response from
`docs/evidence/M1/session-transport/run-3/model-catalog.json`. The runtime
capture contains the actual location envelope and selection fields; the probe
omitted provider `api` and `request` objects because they can hold secrets.

`agents-summary.json` is a field projection of the sanitized `/api/agent`
response in `docs/evidence/M1/session-transport/run-3/agents.json`. It keeps
the observed `location` and each agent's `id`, `description`, `mode`, and
`hidden`; it omits system prompts and permission rules irrelevant to this
codec. The source capture itself omits provider request configuration.

The host was the official OpenCode 1.18.32 binary with a disposable repository
and deterministic loopback provider. See the run-3 evidence report for runtime
method and limitations. Tests adding secret markers or corrupting fields are
synthetic mutations, not captured responses.
