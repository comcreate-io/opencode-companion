# HTTP adapter fixtures

`health.json`, `sessions.json`, and `history.json` are copied from `protocol/src/test/resources/opencode/1.18.32/`. Those protocol fixtures came from isolated official OpenCode 1.18.32 loopback runtime captures; see `protocol/src/test/resources/opencode/1.18.32/PROVENANCE.md` and `docs/evidence/M1/REPORT.md` for provenance and limits. Client tests serve them from disposable local HTTPS MockWebServer instances with test certificates and synthetic credentials.
