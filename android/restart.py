#!/usr/bin/env python3
"""Verify saved logins after APK replacement, force-stop and emulator reboot."""

import argparse
import subprocess
import time
from pathlib import Path

from acceptance import immersive_mode_confirmed

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--ssh-host", default="10.0.2.2")
    parser.add_argument("--ssh-port", default="22222")
    parser.add_argument(
        "--apk", default="android/app/build/outputs/apk/debug/app-debug.apk"
    )
    parser.add_argument(
        "--test-apk",
        default="android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    )
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    artifact = ROOT / "artifacts/android" / args.serial / "credential-restart"
    artifact.mkdir(parents=True, exist_ok=True)
    for apk in [args.apk, args.test_apk]:
        subprocess.run(
            [*adb, "install", "--no-incremental", "-r", str(ROOT / apk)], check=True
        )
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

    def stage(name, label):
        command = [
            *adb,
            "shell",
            "am",
            "instrument",
            "-r",
            "-w",
            "-e",
            "stage",
            name,
            "-e",
            "sshHost",
            args.ssh_host,
            "-e",
            "sshPort",
            args.ssh_port,
            "dev.dryssh.test/dev.dryssh.CredentialRestartRunner",
        ]
        with immersive_mode_confirmed(adb):
            result = subprocess.run(
                command, capture_output=True, text=True, timeout=90, check=True
            )
        report = result.stdout + result.stderr
        (artifact / f"{label}.txt").write_text(report)
        assert f"PASS: {name}" in report and "INSTRUMENTATION_CODE: -1" in report, (
            report
        )

    stage("seed", "seed")
    subprocess.run(
        [*adb, "install", "--no-incremental", "-r", str(ROOT / args.apk)], check=True
    )
    stage("verify", "after-reinstall")
    subprocess.run([*adb, "shell", "am", "force-stop", "dev.dryssh"], check=True)
    stage("verify", "after-force-stop")
    subprocess.run([*adb, "reboot"], check=True)
    subprocess.run([*adb, "wait-for-device"], check=True, timeout=120)
    deadline = time.monotonic() + 120
    while (
        subprocess.check_output(
            [*adb, "shell", "getprop", "sys.boot_completed"], text=True
        ).strip()
        != "1"
    ):
        if time.monotonic() >= deadline:
            raise TimeoutError("Emulator did not boot")
        time.sleep(1)
    subprocess.run([*adb, "shell", "input", "keyevent", "224"], check=True)
    subprocess.run([*adb, "shell", "wm", "dismiss-keyguard"], check=True)
    stage("verify", "after-reboot")
    stage("cleanup", "cleanup")
    print(
        f"PASS: password and encrypted-key authentication survive APK replacement, force-stop and reboot; {artifact}"
    )


if __name__ == "__main__":
    main()
