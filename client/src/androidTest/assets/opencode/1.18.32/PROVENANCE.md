`read-history.json` is a byte-for-byte copy of
`protocol/src/test/resources/opencode/1.18.32/transcript/read-history.json`,
which records the captured OpenCode 1.18.32 read execution documented in
`protocol/src/test/resources/opencode/1.18.32/transcript/PROVENANCE.md`.
The Android instrumentation test uses it only against a disposable local Room
database. It does not contact a host or use credentials.
