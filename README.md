# DrySSH

A terminal-first Android SSH client, optimized for an external keyboard.

This is under active development. Password SSH, host-key trust, automatic
reconnect, strict WLAN transport and foreground sessions are implemented.
Saved profiles, encrypted credentials and the local editor are implemented;
the remaining daily-use tools are still being built. See [delivery status](docs/progress.md) for acceptance evidence.

## Build

Use JDK 21, Rust 1.99.1, Android SDK Platform 37.0, Build Tools 36.0.0, CMake
3.22.1 and NDK 29.0.14206865. Gradle is downloaded by the checksum-pinned wrapper.
The minimum Android version is 11 (API 30).

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'cmake;3.22.1' 'ndk;29.0.14206865'
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
python3 android/build.py
adb install -r target/android/dryssh-debug.apk
```

`--abi x86_64` builds only for an emulator; `--abi arm64-v8a` builds for a phone.
`--release` enables Rust optimization. Both APK variants currently use the local
Android debug signing key. Keep that key to allow installation of updates.
These are development builds, not distribution-signed releases.

Use `--gradle-arg=OPTION` to pass local proxy or AAPT2 overrides without adding
host-specific paths to the project. On NixOS the NDK, CMake, AAPT2 and emulator
need an FHS environment or patched native executables.

## Checks

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cd android
./gradlew spotlessCheck lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Build the bridge first with `python3 android/build.py --abi x86_64 --test`.
Device tests exercise the real libvterm native library and Rust JNI bridge.

The app's standard logs and crashes are available through
`adb logcat -s AndroidRuntime DrySSH`. Terminal output and credentials must not
be included in application logs. Phone-specific HarmonyOS/卓易通 installation,
keyboard, IME and routing verification will be performed by the user.

## Emulator SSH fixture

The fixture contains OpenSSH, tmux, git and nvim, with the local test account
`dryssh` / `dryssh-test`. Keep the published port on loopback:

```sh
podman build --network host -t localhost/dryssh-fixture android/fixture
podman run -d --name dryssh-fixture -p 127.0.0.1:22222:22 localhost/dryssh-fixture
adb shell svc wifi enable
adb shell cmd wifi connect-network AndroidWifi open
python3 android/test.py --ssh-host 10.0.2.2 --ssh-dns-host 10.0.2.2.nip.io
```

The route case requires both emulator WLAN and cellular networks. It forces
the **test process** default onto cellular, verifies its actual socket source
address, then connects to the same SSH server through the reserved WLAN network
and network-specific DNS. Application code never binds the whole process.
The `nip.io` name is only a test DNS convenience, not an app dependency.

`--skip-build` uses existing APKs. `--class dev.dryssh.NetworkAccessTest` selects
a case. Tests with no SSH fixture argument skip SSH scenarios; the harness
rejects any skipped case when `--ssh-host` is supplied. Reports, logcat and a
screenshot are retained under `artifacts/android/<serial>/`.

Saved credentials use an independent `CredentialStore` interface. Android stores
AES-256-GCM ciphertext in `noBackupFilesDir`, with the key in Android Keystore.
Configuration contains references only; private keys and passphrases are encrypted
together. No plaintext fallback is used if Keystore fails. Clearing app data or
uninstalling removes saved connections; backup/export is outside this release.
The fixture's encrypted Ed25519 key and its passphrase (`dryssh-key-test`) are
public test credentials, authorized only by the isolated fixture image.
