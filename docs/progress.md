# Delivery status

Scope: `plan.md` sections 2–6, with **Android emulator acceptance** authorized by
the user on 2026-11-13. Hardware/HarmonyOS NEXT verification remains withthe user.
A terminal demo is only M0; it does not satisfy the daily-use release requirements.

## Milestones

| Stage | Current evidence | Remaining |
| --- | --- | --- |
| M0 | APK installs and launches on API 35 x86_64; 5 device tests pass; ASCII and Chinese rendered; lint has no errors; build documented | Final ARM64 packaging, phone verification by user |
| M1 | Not implemented | SSH transport, trust, auth, PTY, I/O, resizing, keyboard |
| M2 | Native terminal integration tests written, not yet run | IME, paste in tmux, service lifecycle, 30-minute background and lock, WLAN binding, secure persistence |
| M3 | Not implemented | Profiles, credentials, reconnect, settings, editor, touch, clipboard, notification |
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
