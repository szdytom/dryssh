# Delivery status

The daily-use feature set in `plan.md` sections 2–6 is implemented and accepted in
Android emulators, as authorized by the user. Physical HarmonyOS/卓易通, Bluetooth
hardware and Doubao verification remain with the user.

## Milestones

| Stage | Accepted evidence |
| --- | --- |
| M0 | Installable signed APK; pinned toolchain/dependencies; ARM64 and x86_64 native libraries; build/logging instructions |
| M1 | Real SSH password/private-key login, host trust/change rejection, shell/PTY, keyboard input, recreation and orientation |
| M2 | Direct IME contract, tmux/nvim bracketed paste, original connections across background/lock for 30 minutes, strict WLAN routing/DNS and credential restart survival |
| M3 | Saved profiles, encrypted credentials, reconnect policy, appearance/fonts/keys/actions UI, editor, touch/mouse/history, clipboard and notification controls |
| M4 | Real tmux, Codex CLI, interactive git and nvim workflows; regression coverage; documented limits |

The user-requested landscape change uses a **52 dp left icon rail**, with no top or
bottom application toolbar. Tests compare rail/terminal bounds, exercise the
controls and check the original shell PID. Status, screen-awake and disconnect
remain available in the session menu. Icon controls have accessible names and
long-press tooltips. Settings pages keep their controls visible above the IME.

Appearance now offers searchable offline themes instead of individual color
fields: all 179 official Alacritty themes, including Everforest Dark Medium, plus
the original DrySSH palette. Each preview shows foreground/background and both
ANSI rows. Saving applies the complete palette; canceling preserves saved colors.
Existing custom palettes remain available as “Saved colors”. Theme IDs distinguish
upstream names with identical colors. Source pins, attribution and regeneration
instructions are recorded in [android/THEMES.md](../android/THEMES.md).

## Release acceptance — 2026-11-13

- **32 instrumented tests passed, zero skipped**, against the actual release APK
  on the API 35 AOSP x86_64 phone emulator. The suite includes the stronger
  cellular DNS/TCP isolation case. Generated report:
  `artifacts/android/emulator-5554/integration.txt`.
- **18 Rust tests passed**, including property-based policy/configuration tests and
  real SSH protocol integration. Formatting, Clippy with warnings denied,
  Spotless and Android release lint pass. Lint has no errors; remaining warnings
  concern dependency updates, style suggestions and resource/API metadata.
- Theme coverage checks every bundled palette through Rust normalization, exact
  Everforest colors, missing upstream ANSI defaults, identical-palette identities,
  legacy custom colors, cancellation, light/dark selection, repository reload,
  activity recreation, original SSH shell survival and landscape search with IME
  dismissal. Font import and keyboard mapping acceptance also selects a theme.
  Logs: `target/research/themes-regression-final.log`, `themes-rust-tests.log`,
  `themes-clippy.log` and `themes-build-final.log` in the same directory.
- The APK contains `arm64-v8a` and `x86_64`, requires API 30+, targets API 37,
  and passes APK signature verification. ARM64 is cross-compiled; emulator
  execution uses x86_64. The APK uses the local debug signing key for updates.

### Original connection survival

Auto-reconnect was **disabled**. Both devices used a simulated unplugged battery;
no battery-optimization exemption was added. The background case opened Android
Settings. The lock case asserted a locked keyguard and a noninteractive display,
which remained noninteractive through the full interval.

| Case | Elapsed | Shell PID before/after | sshd PIDs before/after | Authentications |
| --- | --- | --- | --- | --- |
| Background, API 35 tablet | 1,801.237 s | 1171 / 1171 | 1170, 1159 / 1170, 1159 | 1 |
| Lock, API 35 phone | 1,801.919 s | 1223 / 1223 | 1222, 1211 / 1222, 1211 | 1 |

The server records both phases; a process-environment snapshot identifies the
original TCP connection, and sshd logs show one authentication for its source
address/port. Generated evidence is under
`artifacts/android/emulator-5556/soak-background/` and
`artifacts/android/emulator-5558/soak-lock/`. The `soak.py --verify-only` check also
passes for both reports. These checks do not substitute a new connection or tmux
reattachment for keeping the original SSH session.

### Network policy

- With the test process default forced to cellular, an ordinary socket uses
  `10.0.2.15`; the strict WLAN socket uses `10.0.2.16`, and both read real SSH
  banners.
- The additional AOSP-root fixture blocks cellular UDP/TCP DNS. A fresh wildcard
  hostname fails on the cellular resolver. It then blocks cellular TCP to the SSH
  fixture and verifies a new cellular connection fails. The WLAN-specific DNS
  lookup and bound socket still reach the same SSH endpoint. Firewall/resolver
  settings are restored. Root is required only for this controlled test.
- A separate WLAN-loss case leaves cellular available, observes waiting for WLAN,
  reconnects to a new shell after recovery, and verifies offline input is not
  replayed. Explicit disconnect cancels subsequent recovery.

### Credentials and host trust

AES-256-GCM credentials survive repository recreation, application force-stop and
an emulator reboot. Both saved-password and saved encrypted-Ed25519-key login
succeed after each restart. Swapped/tampered ciphertext is rejected; failed profile
updates retain the prior credential. Configuration has references, not credential
values. Host integration also covers incorrect credentials, changed/rejected host
keys before authentication, unencrypted keys and transport cancellation.

### Input and terminal workflows

- The real Android input view and OpenSSH verify exact Chinese UTF-8 bytes for
  local composition, consecutive voice-style commits, deletion, composition
  finish, API 34 replacement and a following physical Ctrl-E event. The old
  `TYPE_NULL` implementation failed before the adapter change.
- A native multiline editor retains drafts across background/recreation. Real
  tmux/nvim file contents verify negotiated bracketed paste bypasses an Enter
  mapping and adds no trailing Return. Mode-off multiline paste stays blocked
  until explicit fallback. Concurrent native mode changes cannot leak unframed
  protected paste.
- Real Codex CLI 0.167.0 runs inside tmux, using the pinned standalone binary with
  `--no-daemon`. A local fixed Responses provider hashes the submitted multiline
  Chinese body and responds with that hash; paste alone does not submit it.
  Subsequent interactive `git add -p` stages the actual changed hunk. This tests
  the real CLI, not live cloud inference or account authorization.
- View-level touch coordinates modify the intended nvim character. Wheel input
  scrolls its view; nvim OSC 52 copying through tmux reaches Android's clipboard.
  Local history, explicit selection and copy also pass. Native protocol coverage
  includes mouse clicks/wheels, alternate scroll and DEC 1007.
- Actual orientation and IME changes are checked against remote `stty size`, while
  the original shell PID stays unchanged. A 600-character key burst stays ordered
  without closing SSH. Input backpressure is handled off the UI thread and queued
  work is cancelled with its connection.
- Unifont 18.0.01 import, glyph/dual-width checks, palette persistence, mapping
  edits/reload and the editor shortcut pass. Notification taps return to the
  original shell, and the notification disconnect action cancels recovery.

### Adaptive screen edges

- Immersive Activity and dialog windows share cutout, waterfall, rounded-corner
  (API 31+) and IME handling. Portrait controls use the camera's surrounding
  space; landscape chooses the rail side that leaves the largest safe terminal.
- Six JVM tests pass, including 180 randomized configurations compared with an
  independent exhaustive rectangle oracle. Twenty-three distinct device tests
  cover layout, editor focus/drafts, profiles, themes, input, mouse and clipboard.
- Release acceptance on the API 35 emulator also covers a rectangular display,
  the Pixel 6 punch hole and an actual simulated tall notch, in portrait and both
  landscape rotations. Swiping to reveal transient system bars preserves terminal
  bounds; IME/rotation resize the existing remote PTY without replacing its shell.
- Reports, display geometry and screenshots are in
  `artifacts/android/emulator-5554/edges/`. The universal ARM64/x86_64 release APK
  builds and passes signature verification. Physical devices remain unverified.

## Practical limits

- Phone/container-specific network enforcement, background survival, Android
  Keystore behavior, actual Bluetooth events and Doubao speech must be checked
  on the user's HarmonyOS/卓易通 environment. Emulator success does not assert
  those platform capabilities.
- OS process termination closes its TCP session. Profiles/credentials persist;
  active sessions and local editor drafts are not restored after process death.
- The platform input queue has a 4 MiB limit. Font imports are bounded
  to 32 MiB and private-key imports to 1 MiB.
- System/IME-reserved key combinations depend on the device; configurable
  mappings provide alternatives for events the application can receive.
- This release covers direct SSH and one active session. Jump hosts, Mosh, VPN
  adaptation, automatic tmux restoration, clipboard reads over OSC 52 and
  configuration backup/export remain outside the agreed first-release scope.

Build, fixture and rerun commands are in [README.md](../README.md). Terminal source
pins, licenses and behavior changes are in
[UPSTREAM.md](../android/terminal/UPSTREAM.md).
