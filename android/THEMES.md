# Built-in terminal themes

`app/src/main/assets/themes.json` contains all 179 themes from
[alacritty/alacritty-theme](https://github.com/alacritty/alacritty-theme/tree/95615789c5eddee1c5407a520cd9959a86fa4716),
pinned at `95615789c5eddee1c5407a520cd9959a86fa4716`. Everforest Dark Medium is
included upstream. The original DrySSH palette is added at runtime from Rust's
configuration defaults.

The catalogue uses primary foreground/background and normal/bright ANSI colors.
Cursor, selection, search, dim and extended indexed overrides are not imported.
Citylights omits normal yellow and Doom One omits bright colors; these use the
Alacritty defaults from local `../alacritty` revision
`aa5d942f46c40184a3f214830ff5f7989f4733a1`, rather than inheriting DrySSH colors.
The upstream Apache 2.0 license and original theme comments/attributions are
bundled alongside the catalogue as `themes-LICENSE.txt` and `themes-NOTICE.txt`.

To reproduce the assets using Python 3.11+:

```sh
git clone https://github.com/alacritty/alacritty-theme /tmp/dryssh-themes
git -C /tmp/dryssh-themes checkout 95615789c5eddee1c5407a520cd9959a86fa4716
python3 android/import-themes.py /tmp/dryssh-themes
```

No download or runtime TOML parser is needed by the app. `settings.theme` stores
the chosen catalogue ID; the existing color fields remain authoritative for
backward compatibility with saved custom palettes. Canceling settings leaves both
the saved palette and terminal untouched. Unknown or stale theme references are
resolved by palette equality, or displayed as “Saved colors”.
