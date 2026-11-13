# DrySSH

A terminal-first Android SSH client for an external keyboard, with a single active
session and multiple saved connection profiles. The first daily-use feature set
from `plan.md` is implemented; acceptance uses Android emulators as agreed with
the user. See [verification and limits](docs/progress.md).

The app uses immersive edge-to-edge windows; swipe from an edge to temporarily
reveal the system bars. Controls avoid the actual camera cutout, display corners
(Android 12+) and keyboard instead of reserving a uniform border. Portrait controls
use the space beside the camera, with overflow actions on narrow screens.
Landscape controls use a **52 dp icon rail**, choosing the side that gives the
terminal more usable area and expanding only when a cutout requires it. The
terminal keeps a complete rectangular grid. Icons have accessible names and long-press
tooltips. The plug opens connections, the pencil opens the local editor, the
keyboard toggles the IME, and the lightning icon opens auxiliary keys/actions.
The session menu contains status, screen-awake and disconnect controls.

Saved profiles support passwords and imported private keys, including encrypted
keys. Auto-reconnect defaults on; WLAN-only defaults off. Switching targets closes
the previous session. Reconnection starts a fresh shell and never replays input.
The default editor shortcut is Ctrl+Shift+E; mappings and quick actions are editable
in Settings, along with terminal themes, font import and size.

Settings → Appearance → Theme offers searchable previews of all **179 bundled
[Alacritty themes](https://github.com/alacritty/alacritty-theme)**, including
Everforest Dark Medium, plus the original DrySSH palette. Themes work offline and
apply foreground, background and all 16 ANSI colors together after saving.
Existing custom colors are retained as “Saved colors” until replaced by a theme.
The selected theme survives app restarts, including themes with identical palettes.

Direct Android IME composition stays local until commit. The native multiline
editor keeps a local draft and uses negotiated bracketed paste without adding
Enter. Esc closes the editor and keeps the draft; Shift+Enter pastes, while Enter
inserts a newline. The editor also uses the configured terminal font. When
bracketed paste is off, multiline paste stays blocked until the user chooses
an explicit plain-paste fallback. Long press opens local selection, clipboard and
history actions; ordinary taps/scrolling follow remote mouse and screen modes.

## Build and install

Use JDK 21, Rust 1.99.1, Android SDK Platform 37.0, Build Tools 36.0.0, CMake
3.22.1 and NDK 29.0.14206865. The checksum-pinned Gradle wrapper downloads Gradle.
Minimum Android version: **11 (API 30)**.

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'cmake;3.22.1' 'ndk;29.0.14206865'
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
python3 android/build.py --release --test
adb install -r target/android/dryssh-release.apk
```

The default APK contains ARM64 and x86_64. `--abi arm64-v8a` builds only for a phone;
`--abi x86_64` builds only for an emulator. `--release` optimizes the Rust core.
Both build types currently use the local Android debug signing key: preserve it
for compatible updates. These are personal development builds.

Use `--gradle-arg=OPTION` for local proxy/AAPT2 overrides. On NixOS, the NDK, CMake,
AAPT2 and emulator need an FHS environment or patched native executables.

## Architecture and storage

- The platform-independent Rust core owns SSH, profiles/settings and the connection
  policy. It receives an already-connected transport and cannot bypass platform
  network selection with its own default socket.
- The foreground service owns the SSH session, terminal, notification and partial
  wake lock. Activity recreation replaces the view; it does not reconnect SSH.
- WLAN-only reserves a Wi-Fi network, resolves DNS on that network and binds each
  socket to it. Loss closes the transport and waits rather than falling back.
- The independent `CredentialStore` interface uses Android Keystore AES-256-GCM.
  Configuration holds references; password/private-key/passphrase blobs are
  encrypted in `noBackupFilesDir`. There is no plaintext fallback. Clearing app
  data/uninstalling removes them; backup/export is outside this release.

The terminal snapshot and local patches are documented in
[UPSTREAM.md](android/terminal/UPSTREAM.md). Its Apache-2.0 and libvterm MIT licenses
are retained. The app does not require server-side helper software.

## Checks

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cd android
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintRelease
```

For device acceptance, build both app/test APKs first and start the fixture:

```sh
podman build --network host -t localhost/dryssh-fixture android/fixture
podman run -d --name dryssh-fixture -p 127.0.0.1:22222:22 localhost/dryssh-fixture
./android/fixture/install-codex.sh
adb -s emulator-5554 shell svc wifi enable
adb -s emulator-5554 shell cmd wifi connect-network AndroidWifi open
python3 android/test.py --skip-build --apk target/android/dryssh-release.apk \
  --ssh-host 10.0.2.2 --ssh-dns-host 10.0.2.2.nip.io --isolate-dns
```

The isolated fixture uses `dryssh` / `dryssh-test`; its published encrypted key and
passphrase (`dryssh-key-test`) are public test credentials. The pinned real Codex
CLI talks to a local fixed Responses service, which hashes the submitted text.
This tests its actual terminal UI without an account or model-inference charges.
The fixture provider follows the
[official provider configuration](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server).

`--isolate-dns` is an additional **AOSP emulator root test**: it blocks cellular DNS
and then cellular access to the fixture port while verifying WLAN DNS/SSH still
work. Rules and resolver settings are restored. The ordinary route test also
compares actual socket addresses with the process default forced to cellular.
Production app code never binds the whole process and does not require root.

`--class dev.dryssh.NetworkAccessTest` selects a case. Without SSH arguments,
fixture cases skip; the harness rejects skips when `--ssh-host` is supplied.
Reports/logcat/screenshots go to `artifacts/android/<serial>/`.

Long and restart acceptance use explicit runners, outside the ordinary suite:

```sh
python3 android/build.py --abi x86_64 --test \
  --gradle-arg=-Pdryssh.runner=dev.dryssh.CredentialRestartRunner
python3 android/restart.py --serial emulator-5554

python3 android/build.py --abi x86_64 --test \
  --gradle-arg=-Pdryssh.runner=dev.dryssh.SoakAcceptanceRunner
python3 android/soak.py --serial emulator-5556 --mode background
python3 android/soak.py --serial emulator-5558 --mode lock
```

Use separate emulators for parallel long tests. Each disables reconnect, unplugs
the simulated battery and holds the original connection for at least 30 minutes.
It verifies remote shell/sshd PIDs and one authentication for the recorded TCP
connection. Restore the ordinary test runner by building without `dryssh.runner`.

App logs/crashes: `adb logcat -s AndroidRuntime DrySSH`. Application logs contain
state transitions and route information, not credentials or terminal transcripts.
HarmonyOS/卓易通, actual Bluetooth keyboards and Doubao IME are verified by the user.
