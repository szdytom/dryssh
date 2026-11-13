#!/usr/bin/env python3
"""Verify original SSH connection survival for at least 30 minutes on an emulator."""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path

from acceptance import immersive_mode_confirmed

ROOT = Path(__file__).resolve().parent.parent


def connection_snapshot(container, pid):
    code = (
        "import json; from pathlib import Path; "
        f"values=Path('/proc/{int(pid)}/environ').read_bytes().split(b'\\0'); "
        "connection=next(v.split(b'=',1)[1].decode().split() for v in values if v.startswith(b'SSH_CONNECTION=')); "
        f"print(json.dumps({{'shell_pid': '{int(pid)}', 'connection': connection}}))"
    )
    return json.loads(
        subprocess.check_output(
            ["podman", "exec", "--user", "dryssh", container, "python3", "-c", code],
            text=True,
        )
    )


def verify(artifact, mode, minutes, container):
    report = (artifact / "integration.txt").read_text()
    elapsed = re.search(rf"PASS: {mode}, ([0-9]+) ms", report)
    assert (
        elapsed
        and int(elapsed.group(1)) >= minutes * 60_000
        and "INSTRUMENTATION_CODE: -1" in report
    ), report
    record = re.search(
        r"Server record: (/tmp/dryssh-soak-(?:background|lock)-[0-9]+)", report
    ).group(1)
    server = subprocess.check_output(
        ["podman", "exec", container, "cat", record], text=True
    )
    (artifact / "server-record.txt").write_text(server)
    rows = [line.split() for line in server.splitlines()]
    assert (
        len(rows) == 2
        and rows[0][0] == "before"
        and rows[1][0] == "after"
        and rows[0][1:] == rows[1][1:]
    ), server
    snapshot = json.loads((artifact / "connection.json").read_text())
    assert snapshot["shell_pid"] == rows[0][1], snapshot
    source = snapshot["connection"]
    logs = subprocess.check_output(
        ["podman", "logs", container], stderr=subprocess.STDOUT, text=True
    )
    matching = "\n".join(
        line
        for line in logs.splitlines()
        if f"from {source[0]} port {source[1]} " in line
    )
    (artifact / "sshd.txt").write_text(matching)
    assert matching.count("Accepted password") == 1, matching
    print(f"PASS: {mode}, same shell/sshd processes and one authentication; {artifact}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["background", "lock"], required=True)
    parser.add_argument("--minutes", type=int, default=30)
    parser.add_argument("--ssh-host", default="10.0.2.2")
    parser.add_argument("--ssh-port", default="22222")
    parser.add_argument("--fixture-container", default="dryssh-fixture")
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Recheck an existing completed acceptance report",
    )
    args = parser.parse_args()
    if args.minutes < 30:
        parser.error("Acceptance requires at least 30 minutes")
    artifact = ROOT / "artifacts/android" / args.serial / f"soak-{args.mode}"
    if args.verify_only:
        verify(artifact, args.mode, args.minutes, args.fixture_container)
        return
    adb = ["adb", "-s", args.serial]
    for apk in [
        "android/app/build/outputs/apk/debug/app-debug.apk",
        "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]:
        subprocess.run([*adb, "install", "-r", str(ROOT / apk)], check=True)
    subprocess.run(
        [
            *adb,
            "shell",
            "pm",
            "grant",
            "dev.dryssh",
            "android.permission.POST_NOTIFICATIONS",
        ],
        check=True,
    )
    artifact.mkdir(parents=True, exist_ok=True)
    command = [
        *adb,
        "shell",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "sshHost",
        args.ssh_host,
        "-e",
        "sshPort",
        args.ssh_port,
        "-e",
        "mode",
        args.mode,
        "-e",
        "minutes",
        str(args.minutes),
        "dev.dryssh.test/dev.dryssh.SoakAcceptanceRunner",
    ]
    with (
        immersive_mode_confirmed(adb),
        (artifact / "integration.txt").open("w") as output,
    ):
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + args.minutes * 60 + 180
        snapshot = False
        while process.poll() is None:
            if not snapshot:
                match = re.search(
                    r"original shell ([0-9]+)",
                    (artifact / "integration.txt").read_text(),
                )
                if match:
                    (artifact / "connection.json").write_text(
                        json.dumps(
                            connection_snapshot(args.fixture_container, match.group(1))
                        )
                    )
                    snapshot = True
            if time.monotonic() > deadline:
                process.terminate()
                process.wait(timeout=10)
                raise TimeoutError("Long acceptance timed out")
            time.sleep(1)
        assert process.returncode == 0, (artifact / "integration.txt").read_text()
    with (artifact / "logcat.txt").open("w") as output:
        subprocess.run(
            [*adb, "logcat", "-d", "-s", "AndroidRuntime", "DrySSH"],
            stdout=output,
            check=True,
        )
    verify(artifact, args.mode, args.minutes, args.fixture_container)


if __name__ == "__main__":
    main()
