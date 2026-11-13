"""Check build isolation and per-ABI release artifacts without a toolchain."""

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build


class BuildTest(unittest.TestCase):
    def prepare(self, root, profile):
        """Create fake native libraries and Gradle outputs under `root`."""
        for target in build.ABIS.values():
            native = root / f"target/{target}/{profile}/libdryssh.so"
            native.parent.mkdir(parents=True)
            native.write_bytes(target.encode())
        outputs = root / "android/app/build/outputs/apk" / profile
        outputs.mkdir(parents=True)

        def assemble(command, **_):
            if "gradlew" not in str(command[0]):
                return
            abis = next(
                argument.split("=")[1]
                for argument in command
                if argument.startswith("-Pdryssh.abis=")
            )
            if command[-1] == ":app:assembleRelease":
                (outputs / "app-release-unsigned.apk").write_bytes(abis.encode())
            elif command[-1] == ":app:assembleDebug":
                (outputs / "app-debug.apk").write_bytes(f"universal {abis}".encode())

        return assemble

    def run_build(self, root, argv, environment, assemble):
        with (
            patch.object(build, "ROOT", root),
            patch("sys.argv", argv),
            patch.dict(os.environ, environment),
            patch.object(build.subprocess, "run", side_effect=assemble) as run,
        ):
            build.main()
        return [
            call.args[0]
            for call in run.call_args_list
            if "gradlew" in str(call.args[0][0])
        ], run

    def test_release_builds_one_apk_per_abi_and_ignores_stale_signed_apks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            assemble = self.prepare(root, "release")
            (root / "android/app/build/outputs/apk/release/app-release.apk").write_bytes(
                b"stale signed apk"
            )
            gradle, run = self.run_build(
                root,
                ["build.py", "--release"],
                {"ANDROID_HOME": str(root / "sdk"), "ANDROID_SDK_ROOT": "/other/sdk"},
                assemble,
            )
            for abi in build.ABIS:
                artifact = root / f"target/android/dryssh-{abi}-release-unsigned.apk"
                self.assertEqual(artifact.read_bytes(), abi.encode())
            self.assertFalse(
                (root / "target/android/dryssh-release-unsigned.apk").exists()
            )
            requested = [
                next(
                    argument.split("=")[1]
                    for argument in command
                    if argument.startswith("-Pdryssh.abis=")
                )
                for command in gradle
            ]
            self.assertEqual(requested, list(build.ABIS))
            for command in gradle:
                self.assertIn(":app:assembleRelease", command)
            for call in run.call_args_list:
                self.assertEqual(call.kwargs["env"]["ANDROID_HOME"], str(root / "sdk"))
                self.assertNotIn("ANDROID_SDK_ROOT", call.kwargs["env"])

    def test_debug_build_packages_all_abis_in_one_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            gradle, _ = self.run_build(
                root, ["build.py"], {}, self.prepare(root, "debug")
            )
            self.assertEqual(
                (root / "target/android/dryssh-debug.apk").read_bytes(),
                f"universal {','.join(build.ABIS)}".encode(),
            )
            self.assertEqual(len(gradle), 1)
            self.assertIn(f"-Pdryssh.abis={','.join(build.ABIS)}", gradle[0])
            self.assertIn(":app:assembleDebug", gradle[0])


if __name__ == "__main__":
    unittest.main()
