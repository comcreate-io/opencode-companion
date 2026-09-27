#!/usr/bin/env python3
"""Real OpenCode execution against a deterministic loopback model, never a paid provider."""

import argparse, base64, hashlib, http.client, json, os, secrets, socket, subprocess
import tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlencode

if not __debug__:
    raise SystemExit("This probe requires Python assertions; do not run with -O or PYTHONOPTIMIZE")

BINARY_SHA256 = "513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    binary = args.binary.resolve()
    if hashlib.sha256(binary.read_bytes()).hexdigest() != BINARY_SHA256:
        parser.error("Binary does not match the pinned official build")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix="opencode-execution-") as directory:
        root = Path(directory)
        for name in ["data", "cache", "config", "state", "home", "tmp", "repo"]:
            (root / name).mkdir()
        (root / "repo" / "README.md").write_text("M1_READ_MARKER\n")
        subprocess.run(["git", "init", "--quiet", str(root / "repo")], check=True)
        subprocess.run(
            ["git", "-C", str(root / "repo"), "add", "README.md"], check=True
        )
        subprocess.run(
            [
                "git",
                "-C",
                str(root / "repo"),
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "--quiet",
                "-m",
                "test: disposable fixture baseline",
            ],
            check=True,
        )
        requests = []
        release = threading.Event()
        observed = []
        observer_errors = []
        observer_stopping = threading.Event()
        observer = None

        class Model(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                if length > 2_000_000:
                    self.send_error(413)
                    return
                if self.path != "/v1/chat/completions" or len(requests) >= 16:
                    self.send_error(400)
                    return
                body = json.loads(self.rfile.read(length))
                requests.append(body)
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()

                def chunk(delta, finish=None):
                    data = {
                        "id": "chatcmpl-fixture",
                        "object": "chat.completion.chunk",
                        "created": 1,
                        "model": "fixture-model",
                        "choices": [
                            {"index": 0, "delta": delta, "finish_reason": finish}
                        ],
                    }
                    self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
                    self.wfile.flush()

                try:
                    user = " ".join(
                        str(m.get("content", ""))
                        for m in body.get("messages", [])
                        if m["role"] == "user"
                    )
                    tools = [m for m in body.get("messages", []) if m["role"] == "tool"]
                    chunk({"role": "assistant"})
                    if "INTERRUPT_CASE" in user:
                        chunk({"content": "Waiting fixture"})
                        release.wait(20)
                    elif "READ_CASE" in user and not tools:
                        chunk(
                            {
                                "tool_calls": [
                                    {
                                        "index": 0,
                                        "id": "call_read_fixture",
                                        "type": "function",
                                        "function": {
                                            "name": "read",
                                            "arguments": json.dumps(
                                                {
                                                    "path": str(
                                                        root / "repo" / "README.md"
                                                    )
                                                }
                                            ),
                                        },
                                    }
                                ]
                            },
                            "tool_calls",
                        )
                    elif "QUESTION_CASE" in user and not tools:
                        chunk(
                            {
                                "tool_calls": [
                                    {
                                        "index": 0,
                                        "id": "call_question_fixture",
                                        "type": "function",
                                        "function": {
                                            "name": "question",
                                            "arguments": json.dumps(
                                                {
                                                    "questions": [
                                                        {
                                                            "question": "Continue fixture?",
                                                            "header": "Fixture",
                                                            "options": [
                                                                {
                                                                    "label": "Yes",
                                                                    "description": "Continue test",
                                                                }
                                                            ],
                                                        }
                                                    ]
                                                }
                                            ),
                                        },
                                    }
                                ]
                            },
                            "tool_calls",
                        )
                    else:
                        chunk({"content": "Fixture "})
                        chunk({"content": "complete."})
                        chunk({}, "stop")
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        model = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        thread = threading.Thread(target=model.serve_forever, daemon=True)
        thread.start()
        config = {
            "model": "fixture/fixture-model",
            "provider": {
                "fixture": {
                    "npm": "@ai-sdk/openai-compatible",
                    "options": {"baseURL": f"http://127.0.0.1:{model.server_port}/v1"},
                    "models": {
                        "fixture-model": {
                            "name": "Fixture",
                            "tool_call": True,
                            "limit": {"context": 8192, "output": 2048},
                        }
                    },
                }
            },
            "snapshot": False,
            "permission": {"*": "deny", "read": "allow", "question": "allow"},
        }
        (root / "config" / "opencode").mkdir()
        (root / "config" / "opencode" / "opencode.json").write_text(json.dumps(config))
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
            "OPENCODE_PURE": "1",
            **{
                "XDG_" + k + "_HOME": str(root / v)
                for k, v in [
                    ("DATA", "data"),
                    ("CACHE", "cache"),
                    ("CONFIG", "config"),
                    ("STATE", "state"),
                ]
            },
        }
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        log = open(root / "host.log", "w")
        process = subprocess.Popen(
            [str(binary), "serve", "--hostname", "127.0.0.1", "--port", str(port)],
            cwd=root / "repo",
            env=env,
            stdout=log,
            stderr=log,
        )
        results = []

        def call(method, path, body=None, expected=200):
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            try:
                c.request(
                    method,
                    path,
                    None if body is None else json.dumps(body),
                    {"Authorization": auth, "Content-Type": "application/json"},
                )
                response = c.getresponse()
                raw = response.read(2_000_001)
                assert response.status == expected, (path, response.status, raw[:200])
                return json.loads(raw) if raw else None
            finally:
                c.close()

        def save(name, data):
            text = json.dumps(data, indent=2).replace(
                str(root / "repo"), "/fixture/repo"
            )
            if any(secret in text for secret in sensitive_strings):
                raise RuntimeError("Refusing credential-bearing evidence")
            (output / name).write_text(text + "\n")

        def record(name):
            results.append({"case": name, "status": "pass"})
            print("PASS", name, flush=True)

        def session(marker):
            sid = call(
                "POST",
                "/api/session",
                {
                    "agent": "build",
                    "model": {"providerID": "fixture", "id": "fixture-model"},
                },
            )["data"]["id"]
            call(
                "POST",
                f"/api/session/{sid}/prompt",
                {
                    "id": "msg_" + secrets.token_hex(12),
                    "prompt": {"text": marker},
                    "resume": True,
                },
            )
            return sid

        def history(sid):
            return call("GET", f"/api/session/{sid}/history?limit=100")

        def wait_done(sid):
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                events = history(sid)
                ended = any(
                    e["type"] in ["session.next.step.ended", "session.next.step.failed"]
                    for e in events["data"]
                )
                if ended and sid not in call("GET", "/api/session/active")["data"]:
                    return
                time.sleep(0.1)
            save("failure-history.json", history(sid))
            raise RuntimeError("Execution did not settle in bounded wait")

        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    diagnostic = (root / "host.log").read_text()[-2500:]
                    for secret in sensitive_strings:
                        diagnostic = diagnostic.replace(secret, "[redacted]")
                    raise RuntimeError("Host exited: " + diagnostic)
                try:
                    call("GET", "/api/health")
                    break
                except (OSError, http.client.HTTPException):
                    time.sleep(0.1)
            else:
                raise RuntimeError("Host readiness timeout")
            model_envelope = call("GET", "/api/model")
            models = model_envelope["data"]
            selected = [m for m in models if m["providerID"] == "fixture"]
            save("model.json", selected)
            assert len(selected) == 1, "Fixture model missing; no prompt sent"
            assert (
                selected[0]["api"]["url"]
                == config["provider"]["fixture"]["options"]["baseURL"]
            )
            record("explicit loopback provider verified before execution")
            agents = call("GET", "/api/agent")
            assert any(a["id"] == "build" for a in agents["data"])
            save("agents.json", {"location": agents["location"], "data": [
                {k: v for k, v in a.items() if k in ("id", "description", "mode", "hidden", "model", "color")}
                for a in agents["data"]
            ]})
            # Persist only display/selection fields: real catalog API config may contain secrets.
            save("model-catalog.json", {"location": model_envelope["location"], "data": [
                {k: v for k, v in m.items() if k not in ("api", "request")}
                for m in selected
            ]})
            location = call("GET", "/api/location")
            assert location["directory"] == str(root / "repo")
            nested = root / "repo" / "nested"
            nested.mkdir()
            resolved = call("GET", "/api/location?" + urlencode({"location[directory]": str(nested)}))
            assert resolved["directory"] == str(nested)
            assert resolved["project"]["id"] == location["project"]["id"]
            save("location.json", resolved)
            requested_sid = "ses_" + secrets.token_hex(13)
            created = call("POST", "/api/session", {
                "id": requested_sid, "location": {"directory": str(nested)},
                "agent": "build", "model": {"providerID": "fixture", "id": "fixture-model"},
            })["data"]
            assert created["id"] == requested_sid
            assert created["location"]["directory"] == str(nested)
            assert call("GET", f"/api/session/{requested_sid}")["data"] == created
            save("explicit-location-session.json", created)
            record("agent catalog, deep-object location and explicit session identity")

            stream_connection = http.client.HTTPConnection(
                "127.0.0.1", port, timeout=20
            )
            stream_connection.request(
                "GET", "/api/event", headers={"Authorization": auth}
            )
            stream_response = stream_connection.getresponse()
            assert stream_response.status == 200
            assert "text/event-stream" in stream_response.getheader("Content-Type", "")

            def collect():
                lines = []
                total = 0
                try:
                    while not observer_stopping.is_set():
                        line = stream_response.readline(65537)
                        if not line:
                            return
                        total += len(line)
                        if len(line) > 65536 or total > 2_000_000:
                            raise RuntimeError("Observer exceeded budget")
                        if line in (b"\n", b"\r\n"):
                            payload = "\n".join(
                                x[5:].lstrip(" ")
                                for x in lines
                                if x.startswith("data:")
                            )
                            if payload:
                                observed.append(json.loads(payload))
                            lines = []
                        else:
                            lines.append(line.decode().rstrip("\r\n"))
                except (OSError, ValueError, RuntimeError) as error:
                    if not observer_stopping.is_set():
                        observer_errors.append(type(error).__name__)

            observer = threading.Thread(target=collect, daemon=True)
            observer.start()
            sid = session("STREAM_CASE")
            wait_done(sid)
            events = history(sid)
            save("text-history.json", events)
            assert any(
                e["type"] == "session.next.text.ended"
                and e["data"]["text"] == "Fixture complete."
                for e in events["data"]
            ), events
            record("real agent execution with deterministic streamed model output")
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                live = [
                    e for e in observed if e.get("data", {}).get("sessionID") == sid
                ]
                if any(e["type"] == "session.next.text.ended" for e in live):
                    break
                time.sleep(0.05)
            deltas = [e for e in live if e["type"] == "session.next.text.delta"]
            assert deltas and all("durable" not in e for e in deltas)
            assert "".join(e["data"]["delta"] for e in deltas) == "Fixture complete."
            assert not any(
                e["type"] == "session.next.text.delta" for e in events["data"]
            )
            assert history(sid) == events
            save("text-live-events.json", live)
            record(
                "transient deltas match durable final text; repeated history excludes deltas"
            )
            sid = session("READ_CASE")
            wait_done(sid)
            events = history(sid)
            save("read-history.json", events)
            assert any(
                e["type"] == "session.next.tool.success" for e in events["data"]
            ), events
            assert any(
                "M1_READ_MARKER" in json.dumps(m)
                for request in requests
                for m in request.get("messages", [])
                if m["role"] == "tool"
            )
            record("read tool executes only against disposable fixture file")
            # Replay the captured tool run, then observe a new turn on the same live stream.
            durable_connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            durable_connection.request("GET", f"/api/session/{sid}/event?after=0", headers={"Authorization": auth})
            durable_response = durable_connection.getresponse()
            assert durable_response.status == 200
            assert "text/event-stream" in durable_response.getheader("Content-Type", "")

            def durable_frame():
                lines, total = [], 0
                while total <= 1_048_576:
                    line = durable_response.readline(1_048_577)
                    if not line:
                        raise RuntimeError("Durable stream closed before frame")
                    total += len(line)
                    if line in (b"\n", b"\r\n"):
                        payload = "\n".join(x[5:].lstrip(" ") for x in lines if x.startswith("data:"))
                        if payload:
                            return json.loads(payload)
                        lines = []
                    else:
                        lines.append(line.decode().rstrip("\r\n"))
                raise RuntimeError("Durable frame exceeded probe budget")

            try:
                replayed = [durable_frame() for _ in events["data"]]
                assert replayed == events["data"]
                after = replayed[-1]["durable"]["seq"]
                admission = call("POST", f"/api/session/{sid}/prompt", {
                    "id": "msg_" + secrets.token_hex(12),
                    "prompt": {"text": "STREAM_CASE"}, "resume": True,
                })
                live_durable = []
                for _ in range(30):
                    event = durable_frame()
                    live_durable.append(event)
                    assert event["durable"]["seq"] == after + len(live_durable)
                    if event["type"] in ("session.next.step.ended", "session.next.step.failed"):
                        break
                else:
                    raise RuntimeError("Live durable turn exceeded bounded events")
                wait_done(sid)
                assert history(sid)["data"] == replayed + live_durable
                assert live_durable[0]["data"]["messageID"] == admission["data"]["id"]
                save("read-followup-history.json", history(sid))
                record("complete tool replay crosses into live durable text without gaps")
            finally:
                durable_response.close()
                durable_connection.close()

            sid = session("QUESTION_CASE")
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                pending = call("GET", f"/api/session/{sid}/question")["data"]
                if pending:
                    break
                time.sleep(0.1)
            assert pending, "No question request observed"
            save("question.json", pending)
            qid = pending[0]["id"]
            other = call("POST", "/api/session", {})["data"]["id"]
            call(
                "POST",
                f"/api/session/{other}/question/{qid}/reply",
                {"answers": [["Yes"]]},
                404,
            )
            call(
                "POST",
                f"/api/session/{sid}/question/{qid}/reply",
                {"answers": [["Yes"]]},
                204,
            )
            call(
                "POST",
                f"/api/session/{sid}/question/{qid}/reply",
                {"answers": [["Yes"]]},
                404,
            )
            wait_done(sid)
            save("question-history.json", history(sid))
            record("question exact-session reply and stale duplicate rejection")
            sid = session("QUESTION_CASE")
            deadline = time.monotonic() + 15
            pending = []
            while time.monotonic() < deadline:
                pending = call("GET", f"/api/session/{sid}/question")["data"]
                if pending:
                    break
                time.sleep(0.1)
            assert pending, "No rejectable question observed"
            qid = pending[0]["id"]
            call("POST", f"/api/session/{sid}/question/{qid}/reject", expected=204)
            call("POST", f"/api/session/{sid}/question/{qid}/reject", expected=404)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                rejected_events = history(sid)
                if any(e["type"] == "session.next.tool.failed" for e in rejected_events["data"]):
                    break
                time.sleep(0.05)
            assert any(e["type"] == "session.next.tool.failed" for e in rejected_events["data"])
            assert call("GET", f"/api/session/{sid}/question")["data"] == []
            save("question-rejected-history.json", rejected_events)
            # Reply acknowledgement is not a settled-step guarantee. Record actual active state.
            save("question-rejection-state.json", {
                "active": sid in call("GET", "/api/session/active")["data"],
                "stepSettled": any(e["type"] in ("session.next.step.ended", "session.next.step.failed") for e in rejected_events["data"]),
            })
            record("question reject is acknowledged, removed and stale duplicate denied")

            sid = session("INTERRUPT_CASE")
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if (
                    any(
                        e["type"] == "session.next.text.delta"
                        and e.get("data", {}).get("sessionID") == sid
                        and e["data"]["delta"] == "Waiting fixture"
                        for e in observed
                    )
                    and sid in call("GET", "/api/session/active")["data"]
                ):
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError("Interrupt fixture did not start")
            call("POST", f"/api/session/{sid}/interrupt", expected=204)
            wait_done(sid)
            assert sid not in call("GET", "/api/session/active")["data"]
            interrupted = history(sid)
            save("interrupt-history.json", interrupted)
            assert any(
                e["type"] == "session.next.step.failed"
                and e["data"].get("error", {}).get("message")
                == "Provider turn interrupted"
                for e in interrupted["data"]
            )
            record("interrupt active model stream and observe idle host state")
            assert call("GET", "/vcs/diff?mode=git") == []
            (root / "repo" / "README.md").write_text("M1_READ_MARKER\nM1_DIFF_MARKER\n")
            diffs = call("GET", "/vcs/diff?mode=git")
            save("working-diff.json", diffs)
            assert len(diffs) == 1 and diffs[0]["file"] == "README.md"
            assert "M1_DIFF_MARKER" in json.dumps(diffs)
            record("combined server read-only VCS diff: empty and modified fixture")
            assert not observer_errors, observer_errors
            save(
                "results.json",
                {
                    "binarySha256": BINARY_SHA256,
                    "cases": results,
                    "provider": "deterministic loopback HTTP fixture",
                    "paidModelCalls": False,
                    "notRun": [
                        "TLS",
                        "Android transport",
                        "binary/large/no-Git diff cases",
                        "physical device",
                    ],
                },
            )
        finally:
            observer_stopping.set()
            release.set()
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            if observer is not None:
                observer.join(timeout=3)
                stream_response.close()
                stream_connection.close()
            model.shutdown()
            model.server_close()
            log.close()


if __name__ == "__main__":
    main()
