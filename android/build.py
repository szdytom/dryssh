#!/usr/bin/env python3
"""Build the Rust bridge and Android APK with pinned dependencies."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
ABIS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=[*ABIS, "all"], default="all")
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--test", action="store_true", help="Also build the instrumentation APK")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Extra Gradle option (use --gradle-arg=OPTION)")
    args = parser.parse_args()
    env = os.environ.copy()
    sdk = Path(env.get("ANDROID_HOME", ROOT / ".tools/android-sdk")).resolve()
    ndk = Path(env.get("ANDROID_NDK_HOME", ROOT / ".tools/android-ndk")).resolve()
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_NDK_HOME"] = str(ndk)
    host = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    profile = "release" if args.release else "debug"
    for abi, target in ABIS.items():
        if args.abi not in (abi, "all"):
            continue
        linker = tools / f"{target}30-clang"
        env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(linker)
        command = ["cargo", "build", "--locked", "-p", "dryssh-android-bridge", "--target", target]
        if args.release:
            command.append("--release")
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        destination = ROOT / "target/android/jniLibs" / abi
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "target" / target / profile / "libdryssh.so", destination)
    abis = ",".join(ABIS) if args.abi == "all" else args.abi
    tasks = [f"-Pdryssh.abis={abis}", *args.gradle_arg, ":app:assembleRelease" if args.release else ":app:assembleDebug"]
    if args.test:
        tasks.append(":app:assembleDebugAndroidTest")
    subprocess.run([str(ROOT / "android/gradlew"), *tasks], cwd=ROOT / "android", env=env, check=True)
    artifact = ROOT / "target/android" / f"dryssh-{profile}.apk"
    source = ROOT / "android/app/build/outputs/apk" / profile / f"app-{profile}.apk"
    if source.exists():
        shutil.copy2(source, artifact)
        print(artifact)


if __name__ == "__main__":
    main()
