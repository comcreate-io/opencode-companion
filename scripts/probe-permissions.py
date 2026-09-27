#!/usr/bin/env python3
"""Probe V2 pending-permission lifecycle on a disposable loopback host.

Only synthetic permission requests are created. No model runs, tool calls, or
saved permissions are involved. Credentials and host logs stay in a temp dir.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time

if not __debug__:
    raise SystemExit("This probe requires Python assertions; do not run with -O or PYTHONOPTIMIZE")


BINARY_SHA256 = "513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080"
VERSION = "1.18.32"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    binary = args.binary.resolve()
    if hashlib.sha256(binary.read_bytes()).hexdigest() != BINARY_SHA256:
        parser.error("Binary does not match the pinned official Linux x64 build")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cases = []

    with tempfile.TemporaryDirectory(prefix="opencode-m1-permission-") as directory:
        root = Path(directory)
        for name in ("data", "cache", "config", "state", "home", "tmp", "repo"):
            (root / name).mkdir()
        (root / "repo" / "README.md").write_text("# Disposable permission fixture\n")
        subprocess.run(["git", "init", "--quiet", str(root / "repo")], check=True)
        password = secrets.token_urlsafe(32)
        auth = "Basic " + base64.b64encode(("opencode:" + password).encode()).decode()
        sensitive_strings = (password, auth, auth.split(" ", 1)[1], base64.b64encode(password.encode()).decode())
        env = {
            "PATH": os.environ["PATH"],
            "OPENCODE_TEST_HOME": str(root / "home"),
            "TMPDIR": str(root / "tmp"),
            "OPENCODE_SERVER_PASSWORD": password,
            "OPENCODE_DISABLE_AUTOUPDATE": "1",
            "OPENCODE_DISABLE_MODELS_FETCH": "1",
            "OPENCODE_DISABLE_PROJECT_CONFIG": "1",
            "OPENCODE_PURE": "1",
            "OPENCODE_CONFIG_CONTENT": '{"enabled_providers":[],"plugin":[]}',
            **{
                "XDG_" + key + "_HOME": str(root / name)
                for key, name in (
                    ("DATA", "data"),
                    ("CACHE", "cache"),
                    ("CONFIG", "config"),
                    ("STATE", "state"),
                )
            },
        }
        # The combined 1.18.32 server validates V1 config first; V2 migrates it.
        # V2 reads the XDG document, not OPENCODE_CONFIG_CONTENT.
        v2_config = root / "config" / "opencode"
        v2_config.mkdir()
        (v2_config / "opencode.json").write_text(
            json.dumps(
                {
                    "agent": {
                        "m1-permission-probe": {
                            "mode": "primary",
                            "permission": {
                                "m1.synthetic.permission": {
                                    "m1://permission-fixture": "ask"
                                }
                            },
                        }
                    },
                }
            )
        )
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        log = open(root / "host.log", "w")
        process = None

        def request(method, path, body=None):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            try:
                headers = {"Authorization": auth, "Content-Type": "application/json"}
                connection.request(
                    method, path, None if body is None else json.dumps(body), headers
                )
                response = connection.getresponse()
                content = response.read(1_000_001)
                if len(content) > 1_000_000:
                    raise RuntimeError("Probe response exceeded size budget")
                mime = response.getheader("Content-Type", "")
                data = (
                    json.loads(content)
                    if content and "application/json" in mime
                    else content.decode()
                )
                return response.status, data
            finally:
                connection.close()

        def expect(method, path, code=200, body=None):
            status, data = request(method, path, body)
            if status != code:
                raise AssertionError(
                    f"{method} {path}: expected {code}, observed {status}"
                )
            return data

        def record(name, **observed):
            cases.append({"case": name, "status": "pass", **observed})
            print("PASS", name, flush=True)

        def save(name, data):
            value = json.dumps(data, indent=2, sort_keys=True)
            if any(secret in value for secret in sensitive_strings) or str(root) in value:
                raise RuntimeError(
                    "Refusing credential- or local-path-bearing evidence"
                )
            (output / name).write_text(value + "\n")

        try:
            process = subprocess.Popen(
                [str(binary), "serve", "--hostname", "127.0.0.1", "--port", str(port)],
                cwd=root / "repo",
                env=env,
                stdout=log,
                stderr=log,
            )
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    log.flush()
                    diagnostic = (root / "host.log").read_text(errors="replace")[-2000:]
                    for secret in sensitive_strings:
                        diagnostic = diagnostic.replace(secret, "[redacted]")
                    diagnostic = diagnostic.replace(str(root), "[tmp]")
                    raise RuntimeError("Isolated host exited: " + diagnostic)
                try:
                    if request("GET", "/api/health") == (200, {"healthy": True}):
                        break
                except (OSError, http.client.HTTPException):
                    pass
                time.sleep(0.1)
            else:
                raise RuntimeError("Isolated host readiness timed out")

            catalog = expect("GET", "/api/agent")["data"]
            if not any(agent["id"] == "m1-permission-probe" for agent in catalog):
                raise AssertionError("Isolated synthetic agent was not loaded")
            first = expect(
                "POST", "/api/session", body={"agent": "m1-permission-probe"}
            )["data"]["id"]
            second = expect(
                "POST", "/api/session", body={"agent": "m1-permission-probe"}
            )["data"]["id"]
            assert first != second
            base = "/api/session/" + first + "/permission"
            other = "/api/session/" + second + "/permission"
            assert expect("GET", base)["data"] == []
            assert expect("GET", other)["data"] == []
            record("two disposable sessions begin with no pending requests")

            action = "m1.synthetic.permission"
            resource = "m1://permission-fixture"

            def create(suffix):
                payload = {
                    "id": "per_m1synthetic" + suffix,
                    "action": action,
                    "resources": [resource],
                    "agent": "m1-permission-probe",
                }
                result = expect("POST", base, body=payload)["data"]
                if result != {"id": payload["id"], "effect": "ask"}:
                    raise AssertionError(
                        f"Synthetic action did not produce an ask: {result['effect']}"
                    )
                return payload["id"]

            once_id = create("once")
            pending = expect("GET", base)["data"]
            assert pending == [
                {
                    "id": once_id,
                    "sessionID": first,
                    "action": action,
                    "resources": [resource],
                }
            ]
            assert expect("GET", base + "/" + once_id)["data"] == pending[0]
            assert expect("GET", other)["data"] == []
            expect("GET", other + "/" + once_id, 404)
            expect("POST", other + "/" + once_id + "/reply", 404, {"reply": "once"})
            assert expect("GET", base)["data"] == pending
            record(
                "pending ask is session-bound; wrong-session get and reply return 404"
            )

            expect("POST", base + "/" + once_id + "/reply", 204, {"reply": "once"})
            assert expect("GET", base)["data"] == []
            expect("GET", base + "/" + once_id, 404)
            expect("POST", base + "/" + once_id + "/reply", 404, {"reply": "once"})
            record("once removes pending request; repeated response is stale")

            reject_id = create("reject")
            cascade_id = create("cascade")
            assert {item["id"] for item in expect("GET", base)["data"]} == {
                reject_id,
                cascade_id,
            }
            expect("POST", base + "/" + reject_id + "/reply", 204, {"reply": "reject"})
            assert expect("GET", base)["data"] == []
            for stale in (reject_id, cascade_id):
                expect("POST", base + "/" + stale + "/reply", 404, {"reply": "once"})
            record(
                "reject clears same-session pending requests; double and cascaded replies are stale"
            )

            race_id = create("race")
            barrier = threading.Barrier(2, timeout=3)

            def simultaneous_reply():
                barrier.wait()
                return request(
                    "POST", base + "/" + race_id + "/reply", {"reply": "once"}
                )[0]

            with ThreadPoolExecutor(max_workers=2) as clients:
                first_reply = clients.submit(simultaneous_reply)
                second_reply = clients.submit(simultaneous_reply)
                race_statuses = sorted(
                    [first_reply.result(timeout=8), second_reply.result(timeout=8)]
                )
            remaining = expect("GET", base)["data"]
            race_ok = race_statuses == [204, 404] and remaining == []
            race_case = {
                "case": "simultaneous once replies accept only one client",
                "status": "pass" if race_ok else "fail",
                "httpStatuses": race_statuses,
                "pendingAfter": len(remaining),
            }
            cases.append(race_case)
            print(
                race_case["status"].upper(),
                race_case["case"],
                race_statuses,
                flush=True,
            )

            assert expect("GET", "/api/permission/saved")["data"] == []
            save("pending.json", pending[0])
            save(
                "results.json",
                {
                    "runtime": VERSION,
                    "binarySha256": BINARY_SHA256,
                    "transport": "loopback HTTP with disposable Basic auth",
                    "modelExecution": False,
                    "syntheticAction": action,
                    "cases": cases,
                    "limitations": [
                        "Synthetic API-created request only; no agent tool permission was exercised",
                        "No process-restart pending-request durability was tested",
                        "No always reply or saved permission was created",
                    ],
                },
            )
            if not race_ok:
                raise AssertionError(
                    f"Concurrent replies did not have one winner: {race_statuses}"
                )
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            log.close()


if __name__ == "__main__":
    main()
