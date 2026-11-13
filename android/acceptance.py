"""Temporary Android settings for device acceptance."""

import shlex
import subprocess
from contextlib import contextmanager


@contextmanager
def immersive_mode_confirmed(adb):
    setting = "immersive_mode_confirmations"
    previous = subprocess.check_output(
        [*adb, "shell", "settings", "get", "secure", setting], text=True
    ).rstrip("\n")

    def update(*arguments):
        subprocess.run(
            [*adb, "shell", shlex.join(["settings", *arguments])], check=True
        )

    update("put", "secure", setting, "confirmed")
    try:
        yield
    finally:
        if previous == "null":
            update("delete", "secure", setting)
        else:
            update("put", "secure", setting, previous)
