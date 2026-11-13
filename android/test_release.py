"""Exercise release identities, APK signatures and public archive contents."""

import hashlib
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

import release


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
