# Delivery status

Scope: `plan.md` sections 2–6, with **Android emulator acceptance** authorized by
the user on 2026-11-13. Hardware/HarmonyOS NEXT verification remains with the user.
A terminal demo is only M0; it does not satisfy the daily-use release requirements.

## Milestones

| Stage | Current evidence | Remaining |
| --- | --- | --- |
| M0 | APK installs and launches on API 35 x86_64; 5 device tests pass; ASCII and Chinese rendered; lint has no errors; build documented | Final ARM64 packaging, phone verification by user |
| M1 | Rust SSH driver and policy tests pass; Android/OpenSSH password login, host trust, PTY resize and I/O pass; original shell PID survives recreation, rotation and a short background interval with reconnect disabled | Broader tmux/Codex/git/nvim keyboard workflows |
| M2 | Native paste negotiation, UTF-8, Ctrl/Alt, OSC 52 and dimensions pass on device; foreground-service lifecycle tested end to end | Paste in tmux, 30-minute background and lock, secure persistence |
| M3 | Notification return/disconnect and automatic reconnect are implemented; OSC 52 callback is wired | Profiles, credentials, settings, editor, full touch/mouse and clipboard workflows |
| M4 | Not started | Full workflow acceptance and regression fixes |

## Decisions

- Kotlin/Compose app; platform-independent Rust core; small JNI bridge.
- Vendor only `src/main` from ConnectBot termlib at the plan's pinned commit.
  Preserve upstream source and licenses; record local patches in
  `android/terminal/UPSTREAM.md`.
- Terminal-first UI: slate background `#101A25`, foreground `#D4DFE9`, chrome
  `#182433`, accent `#8AC6DB`. Android sans for controls, monospace for terminal.
  A compact session/status row keeps terminal cells available on a phone.
- JDK 21, Gradle 9.9.1 (distribution SHA-256 verified by wrapper), AGP 9.4.1,
  Kotlin Compose plugin 2.4.21, Compose BOM 2026.09.00, NDK 29.0.14206865.

## Verification evidence

- API 35 AOSP x86_64 phone emulator, 2026-11-13: 13 instrumented tests passed,
  no skips, against the local OpenSSH 9.6 fixture. Report:
  `artifacts/android/emulator-5554/integration.txt` (generated, not tracked).
- The lifecycle case disables auto-reconnect and compares newly printed remote
  shell PIDs before/after recreation, landscape rotation and two seconds in the
  background. This is **not** the required 30-minute acceptance.
- Host SSH integration covers encrypted/unencrypted Ed25519 import, password
  rejection, changed/rejected host keys before authentication, resize, UTF-8,
  dropping pre-authentication input and cancellation during host-key approval.
- Strict WLAN socket/DNS route acceptance passed against the fixture using
  `10.0.2.2.nip.io`: a process-default cellular socket used `10.0.2.15`, while
  the explicit WLAN socket used `10.0.2.16`; both received OpenSSH banners.
  A separate WLAN-loss case also passes: cellular remains available while the
  session waits, recovery starts a new shell PID, offline input is not replayed,
  and explicit disconnect cancels future recovery.

- Direct Android IME acceptance now passes through the real rendered input view
  and OpenSSH: full text/extracted-text support, uncommitted candidate isolation,
  Chinese composition replacement, consecutive voice-style commits, local and
  committed deletion, `finishComposingText`, API 34 `replaceText`, and a following
  physical Ctrl-E event. The server verifies the exact final UTF-8 bytes. The
  original `TYPE_NULL` configuration failed this test before the adapter patch.
