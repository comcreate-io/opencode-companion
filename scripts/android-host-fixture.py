#!/usr/bin/env python3
"""Hold a disposable pinned OpenCode host behind HTTPS for Android instrumentation.

Only loopback listeners and generated test credentials are used. The ready file is
private test input, never evidence. The CA is trusted explicitly by the test APK;
no device trust settings or release networking configuration are changed.
"""

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BINARY_SHA256 = "513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--ready", required=True, type=Path)
    parser.add_argument("--lifetime", type=int, default=1800)
    args = parser.parse_args()
    binary = args.binary.resolve()
    if hashlib.sha256(binary.read_bytes()).hexdigest() != BINARY_SHA256:
        parser.error("Binary does not match pinned official OpenCode 1.18.32")
    if args.ready.exists() or not 1 <= args.lifetime <= 3600:
        parser.error("Use a new ready file and a lifetime between 1 and 3600 seconds")
    stop = threading.Event()
    def shutdown_signal(signum, _frame):
        print("Fixture stopping after signal", signal.Signals(signum).name, flush=True)
        stop.set()
    signal.signal(signal.SIGTERM, shutdown_signal)
    signal.signal(signal.SIGINT, shutdown_signal)
    with tempfile.TemporaryDirectory(prefix="companion-android-fixture-") as temporary:
        root = Path(temporary)
        for directory in ("home", "tmp", "data", "config", "cache", "state", "repo"):
            (root / directory).mkdir()
        repo = root / "repo"
        (repo / "README.md").write_text("ANDROID_READ_MARKER\n")
        subprocess.run(["git", "init", "--quiet", str(repo)], check=True)
        subprocess.run(["git", "-C", str(repo), "add", "README.md"], check=True)
        subprocess.run([
            "git", "-C", str(repo), "-c", "core.hooksPath=/dev/null", "-c",
            "commit.gpgsign=false", "commit", "--quiet", "-m", "test: Android fixture",
        ], check=True)
        model_requests = []

        class Model(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                if self.path != "/v1/chat/completions" or not 0 < length <= 2_000_000 or len(model_requests) >= 100:
                    self.send_error(400)
                    return
                body = json.loads(self.rfile.read(length))
                model_requests.append(body)
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()

                def chunk(delta, finish=None):
                    data = {"id": "chatcmpl-android-fixture", "object": "chat.completion.chunk",
                            "created": 1, "model": "fixture-model",
                            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
                    self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
                    self.wfile.flush()

                try:
                    messages = body.get("messages", [])
                    user = " ".join(str(m.get("content", "")) for m in messages if m["role"] == "user")
                    tools = [m for m in messages if m["role"] == "tool"]
                    chunk({"role": "assistant"})
                    if "INTERRUPT_CASE" in user:
                        chunk({"content": "Waiting fixture"})
                        stop.wait(30)
                    elif not tools and ("READ_CASE" in user or "QUESTION_CASE" in user):
                        name = "read" if "READ_CASE" in user else "question"
                        arguments = ({"path": str(repo / "README.md")} if name == "read" else {
                            "questions": [{"question": "Continue Android fixture?", "header": "Fixture",
                                           "options": [{"label": "Yes", "description": "Continue test"}]}]})
                        chunk({"tool_calls": [{"index": 0, "id": "call_android_fixture", "type": "function",
                                               "function": {"name": name, "arguments": json.dumps(arguments)}}]}, "tool_calls")
                    else:
                        chunk({"content": "Fixture "})
                        time.sleep(0.15)
                        chunk({"content": "complete."})
                        chunk({}, "stop")
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        model = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        config = {"model": "fixture/fixture-model", "snapshot": False,
                  "permission": {"*": "deny", "read": "allow", "question": "allow",
                                 "android.fixture.permission": {"fixture://android": "ask"}},
                  "agent": {"android-permission-fixture": {"mode": "primary", "permission": {
                      "android.fixture.permission": {"fixture://android": "ask"}}}},
                  "provider": {"fixture": {"npm": "@ai-sdk/openai-compatible",
                    "options": {"baseURL": f"http://127.0.0.1:{model.server_port}/v1"},
                    "models": {"fixture-model": {"name": "Fixture", "tool_call": True,
                                                "limit": {"context": 8192, "output": 2048}}}}}}
        (root / "config" / "opencode").mkdir()
        (root / "config" / "opencode" / "opencode.json").write_text(json.dumps(config))
        password = secrets.token_urlsafe(32)
        auth = "Basic " + base64.b64encode(("opencode:" + password).encode()).decode()
        env = {"PATH": os.environ["PATH"], "OPENCODE_TEST_HOME": str(root / "home"),
               "TMPDIR": str(root / "tmp"), "OPENCODE_SERVER_PASSWORD": password,
               "OPENCODE_DISABLE_AUTOUPDATE": "1", "OPENCODE_DISABLE_MODELS_FETCH": "1", "OPENCODE_PURE": "1",
               **{"XDG_" + key + "_HOME": str(root / value) for key, value in
                  (("DATA", "data"), ("CACHE", "cache"), ("CONFIG", "config"), ("STATE", "state"))}}
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            host_port = reservation.getsockname()[1]
        log = (root / "host.log").open("w")
        process = subprocess.Popen([str(binary), "serve", "--hostname", "127.0.0.1", "--port", str(host_port)],
                                   cwd=repo, env=env, stdout=log, stderr=log)
        proxy = None
        ready_created = False
        try:
            for _ in range(150):
                if process.poll() is not None:
                    raise RuntimeError("Disposable host exited before readiness")
                connection = http.client.HTTPConnection("127.0.0.1", host_port, timeout=1)
                try:
                    connection.request("GET", "/api/health", headers={"Authorization": auth})
                    response = connection.getresponse()
                    if response.status == 200 and json.loads(response.read()).get("healthy") is True:
                        break
                except (OSError, http.client.HTTPException):
                    time.sleep(0.1)
                finally:
                    connection.close()
            else:
                raise RuntimeError("Disposable host readiness timed out")
            cert, key = root / "fixture.pem", root / "fixture.key"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=Disposable Android fixture", "-addext", "subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost",
                            "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)

            class Proxy(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def relay(self):
                    length = int(self.headers.get("Content-Length", "0"))
                    if length < 0 or length > 2_000_000 or self.headers.get("Transfer-Encoding"):
                        self.send_error(413)
                        return
                    upstream = http.client.HTTPConnection("127.0.0.1", host_port, timeout=35)
                    try:
                        headers = {name: self.headers[name] for name in ("Authorization", "Content-Type", "Accept") if name in self.headers}
                        upstream.request(self.command, self.path, self.rfile.read(length) if length else None, headers)
                        response = upstream.getresponse()
                        self.send_response(response.status)
                        self.send_header("Content-Type", response.getheader("Content-Type", "application/octet-stream"))
                        self.send_header("Connection", "close")
                        self.end_headers()
                        while not stop.is_set():
                            data = response.read1(65536)
                            if not data:
                                break
                            self.wfile.write(data)
                            self.wfile.flush()
                    except (OSError, http.client.HTTPException):
                        pass  # Cancellation closes both fixture connections; no retry.
                    finally:
                        upstream.close()

                do_GET = relay
                do_POST = relay

            proxy = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(cert, key)
            proxy.socket = tls.wrap_socket(proxy.socket, server_side=True)
            threading.Thread(target=proxy.serve_forever, daemon=True).start()
            ready = {"origin": f"https://10.0.2.2:{proxy.server_port}/", "password": password,
                     "certificate": cert.read_text(), "directory": str(repo), "pid": os.getpid()}
            args.ready.parent.mkdir(parents=True, exist_ok=True)
            with open(args.ready, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as file:
                json.dump(ready, file)
                ready_created = True
            print("Disposable HTTPS Android fixture ready; no paid model calls.", flush=True)
            stop.wait(args.lifetime)
        finally:
            stop.set()
            if ready_created:
                args.ready.unlink(missing_ok=True)
            if proxy is not None:
                proxy.shutdown()
                proxy.server_close()
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            model.shutdown()
            model.server_close()
            log.close()


if __name__ == "__main__":
    main()
