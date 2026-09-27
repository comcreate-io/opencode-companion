#!/usr/bin/env python3
"""Bounded, credential-free V2 admission/replay spike against one pinned binary.

No model execution: every prompt has resume=false. All host state and credentials
are disposable; only sanitized synthetic evidence is exported.
"""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time

if not __debug__:
    raise SystemExit('This probe requires Python assertions; do not run with -O or PYTHONOPTIMIZE')

BINARY_SHA256 = '513f500a1a5ea1dc7d865547ac87b32a8936334e8d5abd5b3ff585c45a170080'
VERSION = '1.18.32'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    binary = args.binary.resolve()
    if hashlib.sha256(binary.read_bytes()).hexdigest() != BINARY_SHA256:
        parser.error('Binary does not match the pinned official Linux x64 build')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    results = []

    with tempfile.TemporaryDirectory(prefix='opencode-m1-') as directory:
        root = Path(directory)
        for name in ['data', 'cache', 'config', 'state', 'home', 'tmp', 'repo']:
            (root / name).mkdir()
        (root / 'repo' / 'README.md').write_text('# Disposable OpenCode M1 fixture\n')
        subprocess.run(['git', 'init', '--quiet', str(root / 'repo')], check=True)
        password = secrets.token_urlsafe(32)
        auth = 'Basic ' + base64.b64encode(('opencode:' + password).encode()).decode()
        sensitive_strings = (password, auth, auth.split(' ', 1)[1], base64.b64encode(password.encode()).decode())
        env = {
            'PATH': os.environ['PATH'], 'OPENCODE_TEST_HOME': str(root / 'home'),
            'TMPDIR': str(root / 'tmp'), 'OPENCODE_SERVER_PASSWORD': password,
            'OPENCODE_DISABLE_AUTOUPDATE': '1', 'OPENCODE_DISABLE_MODELS_FETCH': '1',
            'OPENCODE_DISABLE_PROJECT_CONFIG': '1', 'OPENCODE_PURE': '1',
            'OPENCODE_CONFIG_CONTENT': '{"enabled_providers":[],"plugin":[]}',
            **{'XDG_' + key + '_HOME': str(root / value) for key, value in
               [('DATA', 'data'), ('CACHE', 'cache'), ('CONFIG', 'config'), ('STATE', 'state')]},
        }
        process = None
        log = open(root / 'host.log', 'w')
        port = None

        def record(name):
            results.append({'case': name, 'status': 'pass'})
            print('PASS', name, flush=True)

        def request(method, path, body=None, credential=auth):
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
            try:
                headers = {'Content-Type': 'application/json'}
                if credential is not None:
                    headers['Authorization'] = credential
                connection.request(method, path, None if body is None else json.dumps(body), headers)
                response = connection.getresponse()
                content = response.read(2_000_001)
                if len(content) > 2_000_000:
                    raise RuntimeError('Probe response exceeded budget')
                mime = response.getheader('Content-Type', '')
                data = json.loads(content) if content and 'application/json' in mime else content.decode()
                return response.status, data, mime
            finally:
                connection.close()

        def expect(method, path, code=200, body=None):
            status, data, _ = request(method, path, body)
            if status != code:
                raise AssertionError(f'{method} {path}: expected {code}, observed {status}')
            return data

        def save(name, data):
            value = json.dumps(data, indent=2).replace(str(root / 'repo'), '/fixture/repo')
            # Only synthetic API payloads; never export request headers or host logs.
            if any(secret in value for secret in sensitive_strings):
                raise RuntimeError('Refusing credential-bearing evidence')
            (output / name).write_text(value + '\n')

        def start():
            nonlocal process, port
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
            process = subprocess.Popen([str(binary), 'serve', '--hostname', '127.0.0.1', '--port', str(port)],
                                       cwd=root / 'repo', env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Isolated host exited')
                try:
                    if request('GET', '/api/health')[0] == 200:
                        return
                except (OSError, http.client.HTTPException):
                    pass
                time.sleep(.1)
            raise RuntimeError('Isolated host readiness timed out')

        def stop():
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        def stream(path):
            c = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
            c.request('GET', path, headers={'Authorization': auth})
            response = c.getresponse()
            assert response.status == 200
            assert 'text/event-stream' in response.getheader('Content-Type', '')
            return c, response

        def frame(response):
            lines = []
            total = 0
            while total < 65536:
                line = response.readline(65537)
                if not line:
                    raise RuntimeError('SSE closed before a complete frame')
                total += len(line)
                if line in (b'\n', b'\r\n'):
                    return b''.join(lines).decode()
                lines.append(line)
            raise RuntimeError('SSE frame exceeded probe budget')

        def event(text):
            return json.loads('\n'.join(line[5:].lstrip(' ') for line in text.splitlines()
                                        if line.startswith('data:')))

        try:
            start()
            health = expect('GET', '/api/health')
            assert health == {'healthy': True}
            assert request('GET', '/api/health', credential=None)[0] == 401
            assert request('GET', '/api/session', credential='Basic aW52YWxpZA==')[0] == 401
            record('authenticated health; absent and wrong credentials rejected')
            spec = expect('GET', '/doc')
            assert '/api/session/{sessionID}/history' in spec['paths']
            # Unknown API GET returns HTML 200 in this combined CLI. Decoder must reject it.
            status, _, mime = request('GET', '/api/m1-nonexistent')
            assert status == 200 and 'text/html' in mime
            record('V2 route schema present; unknown GET is HTML 200, not API success')
            session = expect('POST', '/api/session', body={})
            sid = session['data']['id']
            base = '/api/session/' + sid
            assert expect('GET', base)['data']['id'] == sid
            assert sid in [x['id'] for x in expect('GET', '/api/session')['data']]
            record('create, list and read isolated session')
            files = expect('GET', '/api/fs/list')
            assert 'README.md' in json.dumps(files)
            assert expect('GET', '/api/fs/read/README.md') == '# Disposable OpenCode M1 fixture\n'
            assert isinstance(expect('GET', '/api/agent')['data'], list)
            assert isinstance(expect('GET', '/api/model')['data'], list)
            record('location file list/read and agent/model catalog reads')
            payload = {'id': 'msg_m1admission000000000001', 'prompt': {'text': 'M1 synthetic admission only'},
                       'delivery': 'steer', 'resume': False}
            admitted = expect('POST', base + '/prompt', body=payload)
            assert expect('POST', base + '/prompt', body=payload) == admitted
            expect('POST', base + '/prompt', 409, {**payload, 'prompt': {'text': 'changed'}})
            other = expect('POST', '/api/session', body={})['data']['id']
            expect('POST', '/api/session/' + other + '/prompt', 409, payload)
            history = expect('GET', base + '/history')
            assert len(history['data']) == 1
            seq = history['data'][0]['durable']['seq']
            assert expect('GET', base + '/history?after=' + str(seq))['data'] == []
            record('same-ID deduplication; changed payload/session conflict; exclusive history cursor')
            c, response = stream(base + '/event?after=0')
            try:
                first_frame = frame(response)
                assert event(first_frame) == history['data'][0]
                second = {**payload, 'id': 'msg_m1admission000000000002'}
                expect('POST', base + '/prompt', body=second)
                live_frame = frame(response)
                assert event(live_frame)['data']['messageID'] == second['id']
            finally:
                response.close(); c.close()
            c, response = stream(base + '/event?after=' + str(seq))
            try:
                assert event(frame(response)) == event(live_frame)
            finally:
                response.close(); c.close()
            record('durable SSE replay, live delivery and reconnect at exclusive cursor')
            c, response = stream('/api/event')
            try:
                global_frame = frame(response)
                assert event(global_frame)['type'] == 'server.connected'
                assert not any(line.startswith('id:') for line in global_frame.splitlines())
            finally:
                response.close(); c.close()
            record('global SSE connected frame has no SSE replay ID')
            lost = {**payload, 'id': 'msg_m1lostresponse000000001'}
            wire = json.dumps(lost).encode()
            with socket.create_connection(('127.0.0.1', port), timeout=5) as dropped:
                headers = (f'POST {base}/prompt HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nAuthorization: {auth}\r\n'
                           f'Content-Type: application/json\r\nContent-Length: {len(wire)}\r\nConnection: close\r\n\r\n').encode()
                dropped.sendall(headers + wire)
                # Close without reading a response; reconcile history, never resend.
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                all_history = expect('GET', base + '/history')
                matches = [e for e in all_history['data'] if e['data'].get('messageID') == lost['id']]
                if matches: break
                time.sleep(.05)
            assert len(matches) == 1
            record('dropped prompt response reconciled from durable history without resend')
            assert expect('GET', base + '/history?limit=1')['hasMore'] is True
            expect('GET', base + '/history?limit=101', 400)
            expect('GET', base + '/history?after=-1', 400)
            expect('POST', base + '/interrupt', 204)
            assert expect('GET', base + '/permission')['data'] == []
            assert expect('GET', base + '/question')['data'] == []
            expect('POST', base + '/permission/per_m1missing/reply', 404, {'reply': 'once'})
            expect('POST', base + '/question/que_m1missing/reject', 404)
            record('history limits, idle interrupt, empty request lists and stale replies')
            before = expect('GET', base + '/history')
            stop(); start()
            assert expect('GET', base + '/history') == before
            assert expect('POST', base + '/prompt', body=payload) == admitted
            record('host restart preserves admission/history and deduplication')
            save('health.json', health); save('session.json', session)
            save('sessions.json', expect('GET', '/api/session')); save('admission.json', admitted)
            save('history.json', before)
            (output / 'durable-replay.sse').write_text(first_frame + '\n')
            (output / 'durable-live.sse').write_text(live_frame + '\n')
            (output / 'global-connected.sse').write_text(global_frame + '\n')
            save('results.json', {'runtime': VERSION, 'binarySha256': BINARY_SHA256,
                 'transport': 'loopback HTTP with disposable Basic auth', 'modelExecution': False,
                 'cases': results, 'notRun': ['real model output and tool execution', 'pending permission/question races',
                 'TLS/tunnel', 'per-device revocation', 'Android transport/lifecycle', 'changed-file/diff contract']})
        finally:
            stop(); log.close()


if __name__ == '__main__':
    main()
