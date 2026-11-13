#!/usr/bin/env python3
"""Build the Rust bridge and Android APK with pinned dependencies."""

import argparse
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ABIS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}


def run_gradle(env, abis, tasks, gradle_args):
    subprocess.run(
        [
            str(ROOT / "android/gradlew"),
            f"-Pdryssh.abis={','.join(abis)}",
            *gradle_args,
            *tasks,
        ],
        cwd=ROOT / "android",
        env=env,
        check=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=[*ABIS, "all"], default="all")
    parser.add_argument("--release", action="store_true")
    parser.add_argument(
        "--test", action="store_true", help="Also build the instrumentation APK"
    )
    parser.add_argument(
        "--gradle-arg",
        action="append",
        default=[],
        help="Extra Gradle option (use --gradle-arg=OPTION)",
    )
    args = parser.parse_args()
    env = os.environ.copy()
    sdk = Path(env.get("ANDROID_HOME", ROOT / ".tools/android-sdk")).resolve()
    ndk = Path(env.get("ANDROID_NDK_HOME", ROOT / ".tools/android-ndk")).resolve()
    env["ANDROID_HOME"] = str(sdk)
    env.pop("ANDROID_SDK_ROOT", None)
    env["ANDROID_NDK_HOME"] = str(ndk)
    host = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    profile = "release" if args.release else "debug"
    abis = [abi for abi in ABIS if args.abi in (abi, "all")]
    for abi in abis:
        target = ABIS[abi]
        linker = tools / f"{target}30-clang"
        env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(linker)
        env[f"CC_{target.replace('-', '_')}"] = str(linker)
        env[f"AR_{target.replace('-', '_')}"] = str(tools / "llvm-ar")
        command = [
            "cargo",
            "build",
            "--locked",
            "-p",
            "dryssh-android-bridge",
            "--target",
            target,
        ]
        if args.release:
            command.append("--release")
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        destination = ROOT / "target/android/jniLibs" / abi
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "target" / target / profile / "libdryssh.so", destination)
    outputs = ROOT / "android/app/build/outputs/apk" / profile
    artifacts = []
    if args.release:
        # Each release APK carries only its own `libdryssh.so`.
        for abi in abis:
            run_gradle(env, [abi], [":app:assembleRelease"], args.gradle_arg)
            artifact = ROOT / "target/android" / f"dryssh-{abi}-release-unsigned.apk"
            shutil.copy2(outputs / "app-release-unsigned.apk", artifact)
            artifacts.append(artifact)
    else:
        run_gradle(env, abis, [":app:assembleDebug"], args.gradle_arg)
        artifact = ROOT / "target/android/dryssh-debug.apk"
        shutil.copy2(outputs / "app-debug.apk", artifact)
        artifacts.append(artifact)
    if args.test:
        run_gradle(env, abis, [":app:assembleDebugAndroidTest"], args.gradle_arg)
    for artifact in artifacts:
        print(artifact)


if __name__ == "__main__":
    main()
