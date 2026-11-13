"""Exercise release identities, APK signatures and public archive contents."""

import hashlib
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import release


class ReleaseVersionTest(unittest.TestCase):
    def test_tag_versions_and_notes_must_agree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "android/app").mkdir(parents=True)
            (root / "changelog.d").mkdir()
            (root / "Cargo.toml").write_text('[workspace.package]\nversion = "1.2.3"\n')
            gradle = root / "android/app/build.gradle.kts"
            gradle.write_text('    versionName = "1.2.3"\n')
            notes = root / "changelog.d/1.2.3.md"
            notes.write_text("# DrySSH 1.2.3\n\n- Fix reconnect.\n")
            with patch.object(release, "ROOT", root):
                self.assertEqual(release.release_version("v1.2.3"), "1.2.3")
                for tag in ("1.2.3", "v1.2.4", "v1.2.3-rc.1", "v1.2.3/../other"):
                    with (
                        self.subTest(tag=tag),
                        self.assertRaisesRegex(ValueError, "tag"),
                    ):
                        release.release_version(tag)
                gradle.write_text('    versionName = "1.2.4"\n')
                with self.assertRaisesRegex(ValueError, "versions must match"):
                    release.release_version("v1.2.3")
                gradle.write_text('    versionName = "1.2.3"\n')
                notes.write_text(" \n")
                with self.assertRaisesRegex(ValueError, "Missing release notes"):
                    release.release_version("v1.2.3")
                notes.unlink()
                with self.assertRaisesRegex(ValueError, "Missing release notes"):
                    release.release_version("v1.2.3")
                for version in ("1.2.3-rc.1", "01.2.3", "1.2"):
                    (root / "Cargo.toml").write_text(
                        f'[workspace.package]\nversion = "{version}"\n'
                    )
                    with (
                        self.subTest(version=version),
                        self.assertRaisesRegex(ValueError, "X.Y.Z"),
                    ):
                        release.release_version()

    def test_tag_validation_needs_no_signing_key(self):
        version = release.release_version()
        result = subprocess.run(
            [
                "python3",
                str(release.ROOT / "android/release.py"),
                "--check-tag",
                f"v{version}",
                "--signing-dir",
                "/nonexistent",
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual(result.stdout.strip(), version)


class ReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="dryssh-release-test-")
        cls.directory = Path(cls.temporary.name)
        cls.signing = cls.directory / "signing"
        release.initialize(cls.signing)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_identity_is_private_and_cannot_be_overwritten(self):
        key = self.signing / "release.p12"
        original = key.read_bytes()
        self.assertEqual(self.signing.stat().st_mode & 0o777, 0o700)
        for name in ("release.p12", "signing.json"):
            self.assertEqual((self.signing / name).stat().st_mode & 0o777, 0o600)
        with self.assertRaises(FileExistsError):
            release.initialize(self.signing)
        self.assertEqual(key.read_bytes(), original)

    def test_repository_signing_directory_is_rejected(self):
        result = subprocess.run(
            [
                "python3",
                str(release.ROOT / "android/release.py"),
                "--init",
                "--signing-dir",
                str(release.ROOT / "target/signing"),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("outside the repository", result.stderr)

    def test_archive_contains_public_files_and_matching_checksums(self):
        output = self.directory / "package"
        output.mkdir()
        apks = [output / f"dryssh-0.1.0-{abi}.apk" for abi in release.build.ABIS]
        for apk in apks:
            apk.write_bytes(f"signed {apk.stem} fixture".encode())
        (output / "dryssh-test.apk").write_bytes(b"not for publication")
        release.package(output, apks, "certificate fixture\n", "0.1.0")
        with zipfile.ZipFile(output / "dryssh-0.1.0.zip") as archive:
            self.assertIsNone(archive.testzip())
            for apk in apks:
                self.assertEqual(archive.read(apk.name), apk.read_bytes())
            self.assertIn("LICENSE", archive.namelist())
            self.assertEqual(
                archive.read("changelog.d/0.1.0.md"),
                (release.ROOT / "changelog.d/0.1.0.md").read_bytes(),
            )
            self.assertIn(
                "android/terminal/src/main/cpp/libvterm/LICENSE", archive.namelist()
            )
            self.assertNotIn("dryssh-test.apk", archive.namelist())
            self.assertFalse(
                any(
                    name.endswith((".p12", "signing.json"))
                    for name in archive.namelist()
                )
            )
        entries = (output / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(len(entries), len(apks) + 2)
        for entry in entries:
            digest, name = entry.split("  ")
            self.assertEqual(
                hashlib.sha256((output / name).read_bytes()).hexdigest(), digest
            )

    @unittest.skipUnless(
        os.environ.get("DRYSSH_TEST_APK"), "Build an APK and set DRYSSH_TEST_APK"
    )
    def test_real_apk_signature_identity_and_tamper_detection(self):
        sdk = Path(os.environ.get("ANDROID_HOME", release.ROOT / ".tools/android-sdk"))
        signer = sdk / "build-tools/36.0.0/apksigner"
        for index, source in enumerate(
            Path(path).resolve()
            for path in os.environ["DRYSSH_TEST_APK"].split(os.pathsep)
        ):
            self.check_signature(source, index, signer)

    def check_signature(self, source, index, signer):
        with zipfile.ZipFile(source) as apk:
            self.assertIn("assets/licenses/LICENSE", apk.namelist())
            self.assertIn("assets/licenses/termlib-LICENSE", apk.namelist())
            self.assertIn("assets/licenses/libvterm-LICENSE", apk.namelist())
        first = self.directory / f"first-{index}.apk"
        second = self.directory / f"second-{index}.apk"
        certificate = release.sign(source, first, self.signing, signer)
        next_certificate = release.sign(source, second, self.signing, signer)
        fingerprint = [
            line
            for line in certificate.splitlines()
            if "certificate SHA-256 digest:" in line
        ]
        self.assertEqual(len(fingerprint), 1)
        self.assertIn(fingerprint[0], next_certificate)
        self.assertRegex(certificate, r"Verified using v[23] scheme[^\n]*: true")
        with zipfile.ZipFile(second, "a") as apk:
            apk.writestr("assets/tampered.txt", "unexpected modification")
        result = subprocess.run(
            [str(signer), "verify", str(second)], capture_output=True, check=False
        )
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
