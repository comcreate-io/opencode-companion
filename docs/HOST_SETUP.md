# Candidate host setup

Candidate walkthrough, 2026-09-27. Manual HTTPS setup and per-machine shared-password acknowledgement are implemented; unknown/different host builds fail closed. The clean candidate passed native and platform checks on both emulator and Pixel; real-network and layout acceptance remain open; see the [connected report](evidence/M3/connected/REPORT.md). This is for a trusted owner testing against a disposable Linux/NixOS host workspace. It does not provision a tunnel or establish production acceptance. The [testing handoff](TESTING.md) identifies the candidate APK, checksum and signing identity; do not substitute an older foundation APK.

## Before connecting

- Use exactly **OpenCode 1.18.32**. Unknown or different builds must fail closed in this candidate; “healthy” alone does not establish compatible session APIs.
- Use an HTTPS root origin such as `https://opencode.example.com`, without a path prefix, URL credentials, query or fragment. The phone must trust its certificate normally; no trust-all TLS or production test certificate override.
- Candidate authentication is the host's shared Basic password, with username `opencode` unless you deliberately configured another. Accept the app's shared-credential limitation explicitly. This is owner access to the authenticated OpenCode API, not a restricted or independently revocable device credential.
- Provider credentials stay on the host. The phone needs only host access credentials, stored through Android Keystore-backed encryption. Never place passwords in QR codes, URLs, source files, logs, screenshots or support exports.

The [architecture](ARCHITECTURE.md#connection-and-authentication-decision-gate) remains the authority for D04 and the remote-write gate. The candidate records this choice per machine during setup; no acceptance for Carter's own hosts has been recorded in this development session.

## 1. Prepare an isolated host

Obtain the pinned binary from the [official 1.18.32 release](https://github.com/anomalyco/opencode/releases/tag/v1.18.32), checking the asset digest for your architecture. The [M1 report](evidence/M1/REPORT.md) records the tested Linux x64 artifact. On NixOS, manage a persistent installation/service through the host's flake; do not use global npm installs or `curl | sh`. Do not assume the Android development shell installs the host runtime.

Run as an unprivileged test account without access to client repositories or production secrets. A disposable repository alone is **not a sandbox**: the server and agent retain the account's filesystem and execution privileges. Configure any necessary model access on that host account with bounded test credentials; review tool permission settings before a prompt.

In Bash, with the verified `opencode` binary on PATH:

```bash
opencode --version
# Stop unless the output is exactly 1.18.32.
companion_test_repo=$(mktemp -d -t opencode-companion-test.XXXXXXXX)
git -C "$companion_test_repo" init
cd "$companion_test_repo"
read -r -s -p 'Host access password: ' OPENCODE_SERVER_PASSWORD
printf '\n'
test -n "$OPENCODE_SERVER_PASSWORD" || exit 1
export OPENCODE_SERVER_PASSWORD
export OPENCODE_SERVER_USERNAME=opencode
opencode serve --hostname 127.0.0.1 --port 4096
```

Choose a strong, unique password in your password manager and enter it at the prompt. This keeps it out of command history; it is still passed to the server through its environment. Do not enable shell tracing or collect process environments. Stop if port 4096 belongs to another service; never reuse an unidentified listener. After stopping the server, `unset OPENCODE_SERVER_PASSWORD` in that shell.

The serving flags and password/username variables are documented by [OpenCode](https://opencode.ai/docs/server/). Its general API documentation also describes legacy routes; this app's session contract comes from the pinned runtime evidence, not interchangeable legacy examples.

In a second terminal on the host, verify denial and authenticated version discovery. `curl --user opencode` prompts for the password instead of putting it in the command arguments:

```bash
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  http://127.0.0.1:4096/global/health
# Required: 401.
curl --fail --silent --show-error --user opencode \
  http://127.0.0.1:4096/global/health
# Required JSON values: healthy=true and version="1.18.32".
```

These HTTP checks stay on loopback. The phone uses HTTPS. A version response identifies a reported build, not cryptographic host identity; verify your hostname and certificate separately.

## 2. Supply an authenticated HTTPS route

Use an existing operator-configured HTTPS reverse proxy, or the preferred optional **named Cloudflare Tunnel**. No Cloudflare account, domain, credentials or tunnel has been provisioned for this candidate. Creating or changing those resources requires a separate authorized setup.

For a named tunnel, confirm the intended account, domain and unused hostname before publishing. Follow [Cloudflare's setup instructions](https://developers.cloudflare.com/tunnel/get-started/); on NixOS configure `cloudflared` declaratively instead of running an imperative service installer. Keep its token in the host's secret store, outside Git and Nix store expressions. Route the dedicated hostname to `http://127.0.0.1:4096` on the **same host**. Leave OpenCode's password enabled. Do not expose port 4096 publicly. Cloudflare terminates public TLS, so this is not end-to-end encryption from phone to OpenCode.

A generic proxy must forward the original Authorization header and preserve API paths, methods and SSE response headers. Disable caching and response buffering for API/event traffic; ensure long-lived streams survive the proxy's idle policy. Cloudflare specifically requires `Content-Type: text/event-stream` for unbuffered streaming. Verify incremental events through your actual route. [Cloudflare streaming troubleshooting](https://developers.cloudflare.com/tunnel/troubleshooting/).

Do not use a Quick Tunnel: Cloudflare documents that it does not support SSE. Do not publish an unauthenticated tunnel as a connectivity shortcut. [Quick Tunnel limitations](https://developers.cloudflare.com/tunnel/get-started/).

Cloudflare Access is a separate authentication layer. Browser login/challenge flows are not supported by this candidate. Service-token headers also require an explicitly implemented and tested native integration; do not assume the app supplies them, or replace its Basic Authorization header with an Access token. See [Access service-token authentication](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/).

Before adding the host to the app, repeat both health checks above against `https://YOUR_HOSTNAME/global/health`, without `-k`, `--location`, or credentials in the URL. Require unauthenticated denial and authenticated JSON with the exact version. Reject login HTML, redirects, wrong certificates and unexpected versions. A working health check still does not prove streaming or session execution.

## 3. Connect and retain evidence

Once the candidate handoff identifies the APK and its verification results, install that artifact. Add the HTTPS root origin, a recognizable machine name and host username/password; review the shared-password acknowledgement. Confirm the displayed destination before choosing the disposable project or sending anything.

Use synthetic content for the first session. Verify incremental output, disconnect/reconnect and foreground recovery through this exact remote route. A timeout after a prompt may mean the host accepted it: retain the unknown result and reconcile; do not send it again merely because the response was lost. Record the app build, Android device/API, host version, route mode and observed result using [Verification](VERIFICATION.md#evidence-format). Keep real project content and credentials out of evidence.

The host and its server process must remain running. Phone backgrounding does not stop host execution. Host sleep, server restart and network loss are different events, but connection loss alone cannot tell the phone which happened. Expect unreachable/cause unknown until authoritative state is available; do not assume interrupted work continued across restart.

## Password rotation and recovery limits

This mode has no per-device revocation. If one phone loses trust, revoke the shared credential for **all** clients: stop the old server so active streams close, replace its password in the secure host configuration, then restart. Verify the old password fails and the new one works over the HTTPS route before reconnecting clients. Do not rotate during an active test run unless interruption is the intended test.

Re-enter the credential on the **existing machine** using **Update password**. This same-origin flow preserves machine identity, drafts and the durable journal; do not delete/recreate the profile or clear app data. A pending profile/vault record supports recovery across interruption. If the vault state is ambiguous, access remains blocked until restarting the app rechecks it. Saving the replacement locally does not prove it is correct: require authenticated reconnection to exactly 1.18.32 before Ready.

Resolve unknown/outstanding sends before a planned rotation: the app blocks password replacement while an outgoing intent is unresolved. If the host credential was already revoked and reconciliation is impossible, leave the intent blocked and inspect its outcome directly on the host. This candidate requires manual recovery for that case; it has no automatic override, discard or resend. Do not restore a revoked credential to bypass the block.

Final unit, platform and native acceptance of this new replacement flow remains pending. The feature does not add per-device revocation: every client sharing the old host password loses access when it is revoked. Removing local host access must never delete remote sessions.

## What is and is not established

Pinned runtime probes and local HTTPS fixture checks are recorded in [M1 runtime evidence](evidence/M1/REPORT.md) and [HTTPS transport evidence](evidence/M1/transport/REPORT.md). Those reports describe their own historical scope; the candidate handoff must identify later integrated checks and the exact APK.

This guide does not claim a named tunnel, fresh-host installation, password rotation/re-pair or physical Android remote acceptance passed. These remain separate observed gates. Run the candidate acceptance checklist before using important repositories; a successful local HTTPS test is not evidence of the untested tunnel or phone path.
