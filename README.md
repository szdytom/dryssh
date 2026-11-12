# DrySSH

A terminal-first Android SSH client, optimized for an external keyboard.

This is under active development. The initial terminal/bootstrap is not yet a
usable SSH client. See [delivery status](docs/progress.md) for verified features
and outstanding daily-use requirements.

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
