#!/usr/bin/env python3
"""Run native HTTPS fixture tests on the owned emulator or an explicitly selected USB Pixel 8 / 8 Pro."""
import argparse
import base64
from contextlib import contextmanager
import http.client
import hashlib
import json
from pathlib import Path
import re
import shlex
import ssl
import subprocess
from urllib.parse import urlsplit
import uuid

SESSION = "ses_000000000000000000000001"
INSTALL_TIMEOUT_SECONDS = 90


def installed_apk_sha256(serial, package):
    """Return a hash only for a readable, single-APK install of the requested package."""
    try:
        paths = subprocess.run(["adb", "-s", serial, "shell", "pm", "path", package],
                               capture_output=True, text=True, timeout=15)
        if paths.returncode != 0:
            return None
        entries = paths.stdout.strip().splitlines()
        if len(entries) != 1 or not entries[0].startswith("package:/data/app/"):
            return None
        path = entries[0][len("package:"):]
        if not path.endswith("/base.apk") or any(character.isspace() for character in path):
            return None
        result = subprocess.run(["adb", "-s", serial, "shell", "sha256sum", shlex.quote(path)],
                                capture_output=True, text=True, timeout=15)
        if result.returncode != 0:
            return None
        fields = result.stdout.strip().split()
        if len(fields) != 2 or fields[1] != path or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
            return None
        return fields[0].lower()
    except (OSError, subprocess.SubprocessError):
        return None


def ensure_installed_apk(serial, apk, package, expected_hash, label):
    if installed_apk_sha256(serial, package) == expected_hash:
        return
    try:
        result = subprocess.run(["adb", "-s", serial, "install", "-r", str(apk)],
                                capture_output=True, timeout=INSTALL_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        raise SystemExit(f"{label} APK install timed out after {INSTALL_TIMEOUT_SECONDS} seconds") from None
    except OSError:
        raise SystemExit(f"{label} APK install could not start") from None
    if result.returncode != 0:
        raise SystemExit(f"{label} APK install failed")
    if installed_apk_sha256(serial, package) != expected_hash:
        raise SystemExit(f"{label} installed APK hash could not be verified")


def load_fixture(path):
    fixture = json.loads(path.read_text())
    url = urlsplit(fixture["origin"])
    directory = Path(fixture["directory"]).resolve()
    if (url.scheme != "https" or url.hostname != "10.0.2.2" or url.path != "/"
            or url.query or url.fragment or url.username or not url.port
            or directory.parent.parent != Path("/tmp")
            or not directory.parent.name.startswith("companion-android-fixture-") or not directory.is_dir()):
        raise SystemExit("Expected a running disposable loopback Android fixture")
    return fixture


def seed_session(fixture, session=SESSION, agent=None):
    """Same real session identity on distinct disposable hosts; no production destination accepted."""
    port = urlsplit(fixture["origin"]).port
    context = ssl.create_default_context(cadata=fixture["certificate"])
    headers = {"Authorization": "Basic " + base64.b64encode(("opencode:" + fixture["password"]).encode()).decode(),
               "Content-Type": "application/json"}
    connection = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=15)
    try:
        connection.request("GET", "/api/session/" + session, headers=headers)
        response = connection.getresponse()
        status = response.status
        response.read(2_000_001)
        if status == 200:
            return
        if status != 404:
            raise SystemExit("Fixture session lookup failed")
    finally:
        connection.close()
    connection = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=15)
    try:
        connection.request("POST", "/api/session", json.dumps({"id": session, **({"agent": agent} if agent else {})}), headers)
        response = connection.getresponse()
        if response.status != 200 or json.loads(response.read(2_000_001))["data"]["id"] != session:
            raise SystemExit("Fixture session creation failed")
    finally:
        connection.close()


def permission_request(fixture, session, create):
    context = ssl.create_default_context(cadata=fixture["certificate"])
    headers = {"Authorization": "Basic " + base64.b64encode(("opencode:" + fixture["password"]).encode()).decode(),
               "Content-Type": "application/json"}
    connection = http.client.HTTPSConnection("127.0.0.1", urlsplit(fixture["origin"]).port, context=context, timeout=15)
    try:
        path = "/api/session/" + session + "/permission"
        body = {"id": "per_android" + uuid.uuid4().hex, "action": "android.fixture.permission",
                "resources": ["fixture://android"], "agent": "android-permission-fixture"}
        connection.request("POST" if create else "GET", path, json.dumps(body) if create else None, headers)
        response = connection.getresponse()
        result = json.loads(response.read(2_000_001))
        expected = {"id": body["id"], "effect": "ask"} if create else []
        if response.status != 200 or result.get("data") != expected:
            raise SystemExit("Disposable host permission lifecycle assertion failed")
    finally:
        connection.close()


def target_metadata(serial):
    """Read-only validation precedes installation, reverse mappings, or app operations."""
    def read(*arguments):
        result = subprocess.run(["adb", "-s", serial, *arguments], capture_output=True,
                                text=True, timeout=15)
        if result.returncode != 0:
            raise SystemExit("Selected Android target is unavailable or unauthorized")
        return result.stdout.strip()

    if read("get-state") != "device":
        raise SystemExit("Selected Android target must be authorized and online")
    physical = not serial.startswith("emulator-")
    if physical:
        if not read("get-devpath").startswith("usb:"):
            raise SystemExit("Physical testing requires a directly attached USB device")
        if read("shell", "getprop", "ro.product.model") not in ("Pixel 8", "Pixel 8 Pro"):
            raise SystemExit("Only an explicitly selected USB Pixel 8 or Pixel 8 Pro is supported")
    else:
        if serial != "emulator-5558" or read("emu", "avd", "name").splitlines()[0] != "OpenCode_M0_API36":
            raise SystemExit("Emulator belongs to another project; leave it untouched")
    model = read("shell", "getprop", "ro.product.model")
    api = read("shell", "getprop", "ro.build.version.sdk")
    if not api.isdigit():
        raise SystemExit("Android target did not report a valid API level")
    return {"kind": "usb" if physical else "emulator", "model": model, "api": int(api)}


def reverse_mappings(serial):
    result = subprocess.run(["adb", "-s", serial, "reverse", "--list"],
                            capture_output=True, text=True, timeout=15)
    if result.returncode != 0:
        raise SystemExit("Could not inspect Android reverse mappings")
    mappings = {}
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) != 3 or fields[1] in mappings:
            raise SystemExit("Unexpected Android reverse mapping output")
        mappings[fields[1]] = fields[2]
    return mappings


@contextmanager
def fixture_access(serial, physical, fixtures):
    """Never replace existing reverse mappings; remove only mappings this run created."""
    created = []
    try:
        if physical:
            existing = reverse_mappings(serial)
            ports = ["tcp:" + str(urlsplit(item["origin"]).port) for item in fixtures]
            if len(set(ports)) != len(ports) or any(port in existing for port in ports):
                raise SystemExit("Fixture reverse port already belongs to another operation")
            for port in ports:
                # --no-rebind also rejects a mapping created since the inspection above.
                result = subprocess.run(["adb", "-s", serial, "reverse", "--no-rebind", port, port],
                                        capture_output=True)
                if result.returncode != 0:
                    raise SystemExit("Could not create owned fixture reverse mapping")
                created.append(port)
            for item in fixtures:
                item["origin"] = "https://127.0.0.1:" + str(urlsplit(item["origin"]).port) + "/"
        yield
    finally:
        cleanup_failed = False
        for port in reversed(created):
            try:
                # Avoid removing a mapping replaced by another operation during this run.
                current = reverse_mappings(serial)
                if current.get(port) != port:
                    cleanup_failed = cleanup_failed or port in current
                    continue
                result = subprocess.run(["adb", "-s", serial, "reverse", "--remove", port],
                                        capture_output=True, timeout=15)
                if result.returncode != 0 or port in reverse_mappings(serial):
                    cleanup_failed = True
            except (OSError, subprocess.SubprocessError, SystemExit):
                cleanup_failed = True
        if cleanup_failed:
            raise SystemExit("Owned fixture reverse mapping cleanup could not be verified") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5558")
    parser.add_argument("--ready", required=True, type=Path)
    parser.add_argument("--second-ready", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--test", choices=("readToolDraftRecreationAndChanges", "questionReplyUsesRealPendingRequest", "interruptRemainsSeparateFromDisconnect", "readingPositionSurvivesRecreation", "offlineBackgroundForegroundKeepsDurableState", "catalogScrollSurfaceSpansRightSideOfPhone"))
    parser.add_argument("--process-recovery", action="store_true")
    parser.add_argument("--credential-replacement", action="store_true")
    parser.add_argument("--permission-reply", action="store_true")
    parser.add_argument("--accessibility-layout", action="store_true")
    parser.add_argument("--question-isolation", action="store_true")
    args = parser.parse_args()
    if sum(bool(value) for value in (args.second_ready, args.test, args.process_recovery, args.credential_replacement, args.permission_reply, args.accessibility_layout, args.question_isolation)) > 1:
        parser.error("Choose one suite or method")
    serial = args.serial
    metadata = target_metadata(serial)
    fixture = load_fixture(args.ready)
    second = load_fixture(args.second_ready) if args.second_ready else None
    if second:
        if second["origin"] == fixture["origin"]:
            parser.error("Two distinct hosts are required")
        seed_session(fixture)
        seed_session(second)
    if args.process_recovery or args.credential_replacement:
        seed_session(fixture)
    session = "ses_000000000000000000000002" if args.permission_reply else SESSION
    if args.permission_reply:
        seed_session(fixture, session, "android-permission-fixture")
        permission_request(fixture, session, create=True)
    args.output.mkdir(parents=True, exist_ok=False)
    metadata["appApkSha256"] = hashlib.sha256(Path("app/build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest()
    metadata["testApkSha256"] = hashlib.sha256(Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk").read_bytes()).hexdigest()
    (args.output / "target.json").write_text(json.dumps(metadata, indent=2) + "\n")
    with fixture_access(serial, metadata["kind"] == "usb", [item for item in (fixture, second) if item]):
        count = run_native(args, serial, fixture, second, session, metadata)
    print("PASS connected native HTTPS fixture tests:", count)


def run_native(args, serial, fixture, second, session, metadata):
    run_id = uuid.uuid4().hex
    ensure_installed_apk(serial, Path("app/build/outputs/apk/debug/app-debug.apk"),
                         "dev.local.opencodecompanion.debug", metadata["appApkSha256"], "Application")
    ensure_installed_apk(serial, Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
                         "dev.local.opencodecompanion.debug.test", metadata["testApkSha256"], "Test")
    test_class = ("QuestionStateIsolationTest" if args.question_isolation else
                  "AccessibilityLayoutTest" if args.accessibility_layout else
                  "PermissionReplyTest" if args.permission_reply else
                  "CredentialReplacementTest" if args.credential_replacement else
                  "ProcessRecoveryTest" if args.process_recovery else
                  "TwoHostTest" if second else "ConnectedHostTest")
    command = ["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
               "-e", "class", "dev.local.opencodecompanion.connected." + test_class + ("#" + args.test if args.test else ""),
               "-e", "fixtureOrigin", fixture["origin"],
               "-e", "fixturePassword", fixture["password"],
               "-e", "fixtureCertificate", base64.b64encode(fixture["certificate"].encode()).decode(),
               "-e", "fixtureDatabase", "fixture-" + run_id + ".db",
               "-e", "fixtureEvidenceDirectory", "evidence/" + run_id, "-e", "fixtureSession", session]
    if second:
        command += ["-e", "fixtureOrigin2", second["origin"], "-e", "fixturePassword2", second["password"],
                    "-e", "fixtureCertificate2", base64.b64encode(second["certificate"].encode()).decode(),
                    "-e", "fixtureSession", session]
    command += ["dev.local.opencodecompanion.debug.test/dev.local.opencodecompanion.connected.FixtureTestRunner"]
    methods = ("prepareDurableDraft", "restoreDurableDraft") if args.process_recovery else (None,)
    count = 2 if args.accessibility_layout or args.question_isolation else (1 if args.test or second or args.process_recovery or args.credential_replacement or args.permission_reply else 6)
    expected = "OK (1 test)" if count == 1 else f"OK ({count} tests)"
    outputs = []
    failed = False
    for method in methods:
        if method:
            command[command.index("class") + 1] = "dev.local.opencodecompanion.connected.ProcessRecoveryTest#" + method
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=240)
        except subprocess.TimeoutExpired:
            # Never print exception/command repr: input contains generated credentials.
            subprocess.run(["adb", "-s", serial, "shell", "am", "force-stop",
                            "dev.local.opencodecompanion.debug"], capture_output=True)
            outputs.append("FAIL: connected fixture instrumentation timed out\n")
            failed = True
            break
        text = result.stdout + result.stderr
        for item in (fixture, second):
            if item:
                text = text.replace(item["password"], "[fixture credential]")
        outputs.append(text)
        if result.returncode != 0 or "FAILURES!!!" in text or expected not in text:
            failed = True
            break
        if method == "prepareDurableDraft":
            subprocess.run(["adb", "-s", serial, "shell", "am", "force-stop",
                            "dev.local.opencodecompanion.debug"], check=True, capture_output=True)
    text = "\n".join(outputs)
    (args.output / "instrumentation.txt").write_text(text)
    subprocess.run(["adb", "-s", serial, "pull", "/sdcard/Android/data/dev.local.opencodecompanion.debug/files/evidence/" + run_id, str(args.output / "screenshots")], capture_output=True)
    if failed:
        print(text)
        raise SystemExit("Connected native fixture suite failed; see instrumentation output")
    if args.permission_reply:
        permission_request(fixture, session, create=False)
    return count * len(methods)


if __name__ == "__main__":
    main()
