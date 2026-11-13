# Third-party notices

DrySSH's own code is licensed under [MIT](LICENSE). Third-party components retain
their own licenses and copyright notices.

| Component | Source and attribution | License |
| --- | --- | --- |
| ConnectBot termlib | [Pinned source and local changes](android/terminal/UPSTREAM.md) | [Apache-2.0](android/terminal/LICENSE) |
| libvterm | Included in `android/terminal/src/main/cpp/libvterm` | [MIT](android/terminal/src/main/cpp/libvterm/LICENSE) |
| Alacritty themes | [Pinned source](android/THEMES.md), [original notices](android/app/src/main/assets/themes-NOTICE.txt) | [Apache-2.0](android/app/src/main/assets/themes-LICENSE.txt) |
| GNU Unifont 18.0.01 | [Source and font metadata](android/app/src/androidTest/assets/Unifont-SOURCE.txt); test APK only | [SIL OFL 1.1](android/app/src/androidTest/assets/Unifont-LICENSE.txt) |

Rust and Android library versions are recorded in `Cargo.lock` and the Gradle
lockfiles. Their upstream license terms also apply. Release archives retain the
vendored notices above; Unifont is excluded from the production APK.
The production APK carries DrySSH, termlib and libvterm license texts under
`assets/licenses/`, and theme notices in its assets.
