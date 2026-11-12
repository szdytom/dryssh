#!/usr/bin/env python3
"""Compile a pinned alacritty-theme checkout into the offline Android catalogue."""

import argparse
import json
import subprocess
import tomllib
from pathlib import Path

REVISION = "95615789c5eddee1c5407a520cd9959a86fa4716"
ASSETS = Path(__file__).resolve().parent / "app/src/main/assets"
COLORS = ["black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"]
# Defaults from `alacritty/src/config/color.rs` at `aa5d942f46c40184a3f214830ff5f7989f4733a1`.
DEFAULTS = {
    "normal": [
        "181818",
        "ac4242",
        "90a959",
        "f4bf75",
        "6a9fb5",
        "aa759f",
        "75b5aa",
        "d8d8d8",
    ],
    "bright": [
        "6b6b6b",
        "c55555",
        "aac474",
        "feca88",
        "82b8c8",
        "c28cb8",
        "93d3c3",
        "f8f8f8",
    ],
}


def argb(value):
    return 0xFF000000 | int(value.removeprefix("#").removeprefix("0x"), 16)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Local alacritty-theme checkout")
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != REVISION:
        parser.error(f"Check out pinned revision {REVISION}")
    themes = []
    notices = [
        f"Alacritty Theme — https://github.com/alacritty/alacritty-theme/tree/{REVISION}\n",
        "Modified for DrySSH: primary foreground/background and 16 ANSI colors only.\n",
        "Missing ANSI entries use Alacritty defaults; other color tables are omitted.\n",
        "Original theme comments follow, including attribution and source links.\n",
    ]
    for file in sorted((args.source / "themes").glob("*.toml")):
        content = file.read_text()
        colors = tomllib.loads(content)["colors"]
        themes.append(
            {
                "id": file.stem,
                "name": file.stem.replace("_", " ").replace("-", " ").title(),
                "foreground": argb(colors["primary"]["foreground"]),
                "background": argb(colors["primary"]["background"]),
                "ansi": [
                    argb(colors.get(group, {}).get(name, DEFAULTS[group][index]))
                    for group in DEFAULTS
                    for index, name in enumerate(COLORS)
                ],
            }
        )
        notices.append(f"\n{file.name}\n")
        notices.extend(
            line + "\n"
            for line in content.splitlines()
            if line.lstrip().startswith("#")
        )
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "themes.json").write_text(
        "[\n" + ",\n".join(json.dumps(theme) for theme in themes) + "\n]\n"
    )
    (ASSETS / "themes-NOTICE.txt").write_text("".join(notices))
    (ASSETS / "themes-LICENSE.txt").write_bytes((args.source / "LICENSE").read_bytes())
    print(f"Compiled {len(themes)} themes")


if __name__ == "__main__":
    main()
