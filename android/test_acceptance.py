"""Verify device settings are restored after successful and failed acceptance."""

import shlex
import unittest
from unittest.mock import patch

from acceptance import immersive_mode_confirmed


class AcceptanceEnvironmentTest(unittest.TestCase):
    def test_confirmation_is_temporary_for_existing_and_missing_values(self):
        for previous in ("null", "", "confirmed", "test value; echo unexpected"):
            with (
                self.subTest(previous=previous),
                patch(
                    "acceptance.subprocess.check_output", return_value=f"{previous}\n"
                ),
                patch("acceptance.subprocess.run") as run,
            ):
                with immersive_mode_confirmed(["adb", "-s", "device-123"]):
                    self.assertEqual(run.call_count, 1)
                commands = [
                    shlex.split(call.args[0][-1]) for call in run.call_args_list
                ]
                self.assertEqual(
                    commands[0],
                    [
                        "settings",
                        "put",
                        "secure",
                        "immersive_mode_confirmations",
                        "confirmed",
                    ],
                )
                expected = (
                    ["settings", "delete", "secure", "immersive_mode_confirmations"]
                    if previous == "null"
                    else [
                        "settings",
                        "put",
                        "secure",
                        "immersive_mode_confirmations",
                        previous,
                    ]
                )
                self.assertEqual(commands[1], expected)
                for call in run.call_args_list:
                    self.assertEqual(
                        call.args[0][:-1], ["adb", "-s", "device-123", "shell"]
                    )

    def test_confirmation_is_restored_when_acceptance_raises(self):
        with (
            patch("acceptance.subprocess.check_output", return_value="null\n"),
            patch("acceptance.subprocess.run") as run,
        ):
            with (
                self.assertRaisesRegex(RuntimeError, "acceptance failed"),
                immersive_mode_confirmed(["adb"]),
            ):
                raise RuntimeError("acceptance failed")
            self.assertEqual(
                shlex.split(run.call_args.args[0][-1]),
                ["settings", "delete", "secure", "immersive_mode_confirmations"],
            )


if __name__ == "__main__":
    unittest.main()
