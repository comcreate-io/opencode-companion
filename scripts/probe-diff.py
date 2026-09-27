#!/usr/bin/env python3
"""Exercise read-only diff contracts on the private Android fixture only."""
import argparse
import base64
import json
from pathlib import Path
import ssl
import subprocess
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ready", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    fixture = json.loads(args.ready.read_text())
    root = Path(fixture["directory"]).parent.resolve()
    if not root.name.startswith("companion-android-fixture-") or str(root.parent) != "/tmp":
        parser.error("Expected disposable Android fixture under /tmp")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    tls = ssl.create_default_context(cadata=fixture["certificate"])
    auth = "Basic " + base64.b64encode(("opencode:" + fixture["password"]).encode()).decode()

    def diff(directory):
        query = urlencode({"mode": "git", "directory": str(directory)})
        request = Request(fixture["origin"].replace("10.0.2.2", "127.0.0.1") + "vcs/diff?" + query,
                          headers={"Authorization": auth})
        with urlopen(request, context=tls, timeout=15) as response:
            return json.load(response)

    def save(name, value):
        raw = json.dumps(value, indent=2).replace(str(root), "/fixture")
        if fixture["password"] in raw or auth in raw:
            raise RuntimeError("Credential-bearing evidence rejected")
        (output / name).write_text(raw + "\n")

    repo = root / "diff-repo"
    repo.mkdir()
    def git(*command):
        subprocess.run(["git", "-C", str(repo), "-c", "core.hooksPath=/dev/null",
                        "-c", "commit.gpgsign=false", *command], check=True, capture_output=True)
    git("init", "--quiet")
    (repo / "before.txt").write_text("unchanged rename content\n")
    (repo / "delete.txt").write_text("delete fixture\n")
    (repo / "binary.dat").write_bytes(b"\x00\x01old")
    git("add", ".")
    git("commit", "--quiet", "-m", "test: diff fixture")
    if diff(repo) != []:
        raise AssertionError("Expected clean fixture")
    (repo / "before.txt").rename(repo / "after.txt")
    git("add", "-A")
    (repo / "delete.txt").unlink()
    (repo / "binary.dat").write_bytes(b"\x00\x02new")
    (repo / "large.txt").write_text("".join(f"large fixture line {i}\n" for i in range(5000)))
    changed = diff(repo)
    save("changed.json", changed)
    files = {item["file"]: item for item in changed}
    if not {"binary.dat", "delete.txt", "large.txt"}.issubset(files):
        raise AssertionError("Missing fixture diff entries")
    if "after.txt" not in files:
        raise AssertionError("Missing rename destination")
    if files["delete.txt"].get("status") != "deleted":
        raise AssertionError("Deletion status not preserved")
    if "Binary files" not in files["binary.dat"].get("patch", ""):
        raise AssertionError("Binary marker not preserved")
    if "large fixture line 4999" not in files["large.txt"].get("patch", ""):
        raise AssertionError("5000-line fixture unexpectedly incomplete")
    if diff(root / "repo") != []:
        raise AssertionError("Directory query leaked another project's changes")
    plain = root / "no-git"
    plain.mkdir()
    (plain / "plain.txt").write_text("not git\n")
    no_git = diff(plain)
    save("no-git.json", no_git)
    if no_git != []:
        raise AssertionError("Unexpected non-Git response")
    save("results.json", {"cases": ["clean", "explicit-directory isolation", "binary", "rename", "deleted", "5000 lines", "non-Git empty response"],
                          "passed": True, "limitations": ["Empty response cannot distinguish clean Git from non-Git", "No mutation endpoints used", "UI not exercised by this probe"]})
    print("PASS seven bounded diff observations on disposable HTTPS host")


if __name__ == "__main__":
    main()
