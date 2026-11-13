#!/usr/bin/env python3
"""Create a local signing identity or build a signed release archive."""

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

import build

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SIGNING_DIR = Path.home() / ".local/share/dryssh/signing"


def initialize(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    config = {"alias": "dryssh", "password": secrets.token_urlsafe(48)}
    config_file = directory / "signing.json"
    with open(config_file, "x", opener=lambda p, f: os.open(p, f, 0o600)) as output:
        json.dump(config, output)
    subprocess.run(
        [
            "keytool",
            "-genkeypair",
            "-noprompt",
            "-storetype",
            "PKCS12",
            "-keystore",
            str(directory / "release.p12"),
            "-storepass:env",
            "DRYSSH_SIGNING_PASSWORD",
            "-alias",
            config["alias"],
            "-keyalg",
            "RSA",
            "-keysize",
            "4096",
            "-validity",
            "10000",
            "-dname",
            "CN=DrySSH Release",
        ],
        env={**os.environ, "DRYSSH_SIGNING_PASSWORD": config["password"]},
        check=True,
    )
    (directory / "release.p12").chmod(0o600)
    print(
        f"Signing identity created in {directory}. Back up this entire directory securely."
    )


def sign(source, destination, directory, apksigner):
    config = json.loads((directory / "signing.json").read_text())
    Path(f"{destination}.idsig").unlink(missing_ok=True)
    subprocess.run(
        [
            str(apksigner),
            "sign",
            "--ks",
            str(directory / "release.p12"),
            "--ks-key-alias",
            config["alias"],
            "--ks-pass",
            "env:DRYSSH_SIGNING_PASSWORD",
            "--v4-signing-enabled",
            "false",
            "--out",
            str(destination),
            str(source),
        ],
        env={**os.environ, "DRYSSH_SIGNING_PASSWORD": config["password"]},
        check=True,
    )
    return subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(destination)],
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def package(directory, apks, certificate, version):
    notices = [
        "LICENSE",
        "NOTICE.md",
        f"changelog.d/{version}.md",
        "docs/progress.md",
        "docs/publication-checks.md",
        "android/terminal/LICENSE",
        "android/terminal/UPSTREAM.md",
        "android/terminal/src/main/cpp/libvterm/LICENSE",
        "android/THEMES.md",
        "android/app/src/main/assets/themes-LICENSE.txt",
        "android/app/src/main/assets/themes-NOTICE.txt",
    ]
    (directory / "SIGNING-CERTIFICATE.txt").write_text(certificate)
    archive = directory / f"dryssh-{version}.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for apk in apks:
            output.write(apk, apk.name)
        output.write(directory / "SIGNING-CERTIFICATE.txt", "SIGNING-CERTIFICATE.txt")
        for name in notices:
            output.write(ROOT / name, name)
    files = [*apks, archive, directory / "SIGNING-CERTIFICATE.txt"]
    checksums = []
    for path in files:
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        checksums.append(f"{digest}  {path.name}\n")
    (directory / "SHA256SUMS").write_text("".join(checksums))
    print(f"Release files: {directory}")


def release_version(tag=None):
    version = tomllib.loads((ROOT / "Cargo.toml").read_text())["workspace"]["package"][
        "version"
    ]
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Release version must use X.Y.Z")
    if tag is not None and tag != f"v{version}":
        raise ValueError("Release tag must be vX.Y.Z and match the workspace version")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()
    if not re.search(
        rf'^\s*versionName = "{re.escape(version)}"\s*$', gradle, re.MULTILINE
    ):
        raise ValueError("Rust and Android versions must match")
    notes = ROOT / f"changelog.d/{version}.md"
    if not notes.is_file() or not notes.read_text().strip():
        raise ValueError(f"Missing release notes: changelog.d/{version}.md")
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--init",
        action="store_true",
        help="Create a new signing identity; never overwrite one",
    )
    parser.add_argument(
        "--check-tag", help="Validate a release tag and print its version"
    )
    parser.add_argument("--signing-dir", type=Path, default=DEFAULT_SIGNING_DIR)
    parser.add_argument(
        "--test",
        action="store_true",
        help="Also sign the instrumentation APK with the release key",
    )
    parser.add_argument("--gradle-arg", action="append", default=[])
    args = parser.parse_args()
    if args.check_tag is not None:
        try:
            print(release_version(args.check_tag))
        except ValueError as error:
            parser.error(str(error))
        return
    directory = args.signing_dir.expanduser().resolve()
    if directory.is_relative_to(ROOT):
        parser.error("Keep the signing directory outside the repository")
    if args.init:
        initialize(directory)
        return
    if (
        not (directory / "release.p12").is_file()
        or not (directory / "signing.json").is_file()
    ):
        parser.error("Signing identity missing; run with --init first")
    try:
        version = release_version()
    except ValueError as error:
        parser.error(str(error))
    command = [sys.executable, str(ROOT / "android/build.py"), "--release"]
    if args.test:
        command.append("--test")
    command.extend(f"--gradle-arg={arg}" for arg in args.gradle_arg)
    subprocess.run(command, cwd=ROOT, check=True)
    sdk = Path(os.environ.get("ANDROID_HOME", ROOT / ".tools/android-sdk"))
    apksigner = sdk / "build-tools/36.0.0/apksigner"
    output = ROOT / "target/android/release" / version
    output.mkdir(parents=True, exist_ok=True)
    # The old universal APK name must not ship as a stale file.
    (output / f"dryssh-{version}.apk").unlink(missing_ok=True)
    apks = []
    for abi in build.ABIS:
        certificate = sign(
            ROOT / f"target/android/dryssh-{abi}-release-unsigned.apk",
            output / f"dryssh-{version}-{abi}.apk",
            directory,
            apksigner,
        )
        apks.append(output / f"dryssh-{version}-{abi}.apk")
    if args.test:
        sign(
            ROOT
            / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
            output / "dryssh-test.apk",
            directory,
            apksigner,
        )
    package(output, apks, certificate, version)


if __name__ == "__main__":
    main()
