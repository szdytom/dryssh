#!/usr/bin/env python3
"""Run instrumented emulator acceptance and retain logs and screenshots."""

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--ssh-host", help="OpenSSH fixture host reachable from Android")
    parser.add_argument("--ssh-port", default="22222")
    parser.add_argument("--ssh-dns-host", help="DNS name resolving to the SSH fixture")
    parser.add_argument("--class", dest="test_class")
    parser.add_argument("--gradle-arg", action="append", default=[])
    args = parser.parse_args()
    if not args.skip_build:
        build = [sys.executable, str(ROOT / "android/build.py"), "--abi", "x86_64", "--test"]
        build.extend(f"--gradle-arg={arg}" for arg in args.gradle_arg)
        subprocess.run(build, cwd=ROOT, check=True)
    adb = ["adb", "-s", args.serial]
    apks = [
        "android/app/build/outputs/apk/debug/app-debug.apk",
        "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]
    for apk in apks:
        subprocess.run([*adb, "install", "-r", str(ROOT / apk)], check=True)
    subprocess.run([*adb, "shell", "pm", "grant", "dev.dryssh", "android.permission.POST_NOTIFICATIONS"], check=True)
    command = [*adb, "shell", "am", "instrument", "-r", "-w"]
    if args.ssh_host:
        command.extend(["-e", "sshHost", args.ssh_host, "-e", "sshPort", args.ssh_port])
    if args.ssh_dns_host:
        command.extend(["-e", "sshDnsHost", args.ssh_dns_host])
    if args.test_class:
        command.extend(["-e", "class", args.test_class])
    command.append("dev.dryssh.test/androidx.test.runner.AndroidJUnitRunner")
    artifact = ROOT / "artifacts/android" / args.serial
    artifact.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=240)
        report = result.stdout + result.stderr
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b""
        report = output.decode() if isinstance(output, bytes) else output
        report += "\nInstrumentation timed out\n"
    (artifact / "integration.txt").write_text(report)
    with (artifact / "logcat.txt").open("w") as output:
        subprocess.run([*adb, "logcat", "-d", "-s", "AndroidRuntime", "DrySSH"], stdout=output, check=True)
    with (artifact / "screen.png").open("wb") as output:
        subprocess.run([*adb, "exec-out", "screencap", "-p"], stdout=output, check=True)
    passed = len(re.findall(r"INSTRUMENTATION_STATUS_CODE: 0\s", report))
    skipped = len(re.findall(r"INSTRUMENTATION_STATUS_CODE: -3\s", report))
    print(f"{passed} passed; {skipped} skipped. Report: {artifact / 'integration.txt'}")
    success = re.search(r"OK \(\d+ tests?\)", report) and "FAILURES!!!" not in report
    if not success or (args.ssh_host and skipped):
        print(report)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
