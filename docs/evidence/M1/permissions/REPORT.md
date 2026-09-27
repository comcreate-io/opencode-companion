# M1 synthetic permission lifecycle probe

Observed 2026-09-27 against the isolated official OpenCode Linux x64 1.18.32 binary with SHA-256 `513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080`. The host bound only to `127.0.0.1`, used a disposable Git repository, fresh XDG directories and a disposable Basic credential. The script neither sent a prompt nor ran a model/tool; it created permission requests through the V2 API using a synthetic action and resource. It created no saved permissions. The host was terminated and all host state discarded after the run.

Run from the repository root in the project shell, choosing a **new** output directory:

```sh
nix develop . --command python scripts/probe-permissions.py --binary .android-local/m1/bin/opencode --output docs/evidence/M1/permissions/run-3
```

The [latest captured results](run-2/results.json) passed five cases. A pending request was listed and fetched only under its owning session; a different session's get and reply returned 404. A `once` reply removed the request, and a second reply returned 404. A `reject` reply cleared both that request and another pending request in the same session; subsequent replies to either returned 404. Two simultaneous `once` replies from separate HTTP clients returned one 204 and one 404; the pending list was then empty. This is one bounded race trial, not a proof of every possible interleaving. [Captured pending shape](run-2/pending.json) contains only synthetic values.

The combined 1.18.32 binary rejects a V2 `permissions` key in its startup configuration. The isolated probe therefore uses a V1 `agent` / `permission` rule in its temporary XDG config file; the V2 config service migrates that rule. `OPENCODE_CONFIG_CONTENT` alone does not configure the V2 agent. This is a release-specific setup observation, not a claim about future V2-only binaries.

This proves the API-created request lifecycle on this host, not a real tool's permission path. Pending-request survival across host restart, `always`/saved permission behavior, native Android transport, and remote authentication remain untested.
