# Publication checks — 2026-11-13

## Source and history

Gitleaks 8.30.1 scanned all 41 commits reachable from local refs and the complete
publishable working tree, including newly added files. Historical filenames were
also checked for environment files, private keys, signing stores and local
configuration. No unexplained secret findings were found.

The one raw history finding is the intentionally public encrypted test key at
`android/app/src/androidTest/assets/encrypted-fixture-key`, introduced in commit
`8a5ad56545e46546ff1706f6a6cc7eed33d1f3e2`. Its public passphrase and purpose are
documented in the fixture instructions. `.gitleaksignore` names that exact history
finding and checkout finding. CI checks the file's SHA-256 before either scan, so
replacing this file cannot silently inherit the exception.

The expected fixture SHA-256 is
`9f9bc4254fb6b60baa2d69f667f2e45be136f61c9cc4b4488dac78df0146edd8`.
No history rewriting was needed. The scan covers reachable local Git history,
not external copies or accounts. Repeat CI after adding a remote and pushing.

## Build and signing

- Rust 1.96.0: all 18 tests, formatting and Clippy with warnings denied pass.
- A separate source directory builds the unsigned release and instrumentation
  APKs with both ARM64 and x86_64, without a `.tools` directory in that source
  tree, `local.properties` or pre-existing build outputs. Installed SDK/NDK and dependency
  caches are reused; Gradle's build cache is disabled for this check. NixOS uses
  the documented AAPT2 override.
- Seven Python checks pass, including actual RSA signing of an APK, detection of
  a modified APK, identity continuity, private key permissions, refusal to
  overwrite an identity, archive/checksum contents, SDK environment isolation and
  restoration of device settings on success and failure.
- Dedicated release credentials are stored outside the repository. Signed APKs
  pass `apksigner verify`; both the application and optional instrumentation APK
  use the same dedicated identity. The production APK also carries DrySSH,
  termlib and libvterm license texts, alongside the existing theme notices.
- Saved profiles and both password/encrypted-key authentication pass after
  same-version `adb install -r`, application force-stop and an emulator reboot.
  This validates replacement without clearing app data, not a future schema
  migration. The earlier debug signing identity is different and cannot be
  replaced in place by the new identity.

Generated logs and raw redacted scan reports are under `target/release-checks/`;
credential-restart reports are under
`artifacts/android/emulator-5554/credential-restart/`. These local artifacts are
excluded from Git. GitHub-hosted CI has not run yet: this checkout has no remote.
The workflow has passed local `actionlint` validation.

## Device regression after harness fixes

The signed release APK passed the complete **41-test suite twice consecutively**
on the API 35 AOSP x86_64 phone emulator, with **zero failures and zero skips**.
The second run reused application data from the first. Both runs included real
OpenSSH authentication, input, tmux/nvim/Codex workflows, clipboard, notifications,
orientation/IME layout and strict WLAN DNS/TCP isolation.

The earlier focus failures were traced to Android's `ImmersiveModeConfirmation`
system window: window-manager diagnostics showed it owning focus while both the
Activity and editor dialog lacked focus. Device harnesses now temporarily confirm
the system tutorial during instrumentation and restore the original secure
setting afterward. Both complete runs began with the confirmation unset and
restored it to unset. Conditional waits also synchronize editor, dialog and IME
checks with actual Android window focus.

A subsequent complete run exposed a separate notification accessibility race.
The notification test now resolves a clickable target while traversing the
current tree, retries if the tree is not ready and restores its accessibility
flags. It still verifies that tapping the notification returns to the original
remote shell PID. No test is skipped and remote-output assertions are retained.

Complete-run logs are `target/release-checks/fix-full-2.log` and `fix-full-3.log`.
Seven Python tests, six Android JVM tests, Ruff, Spotless and Android release lint
pass. The Python tests include device-setting restoration after exceptions and
shell quoting of saved values. The 30-minute soak cases were not rerun for this
test-harness change; their earlier evidence remains in [progress.md](progress.md).

The maintainer reports that ARM64 physical-device basic functionality passes.
See [progress.md](progress.md) for the earlier feature acceptance and its limits,
and [releasing.md](releasing.md) for signing, backup and publication instructions.
