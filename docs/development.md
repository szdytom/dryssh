# Development and acceptance

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
python3 -m unittest discover -s android -p 'test_*.py' -v
cd android
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintRelease
```

For device acceptance, build the debug app/test APKs first and start the fixture:

```sh
python3 android/build.py --abi x86_64 --test
podman build --network host -t localhost/dryssh-fixture android/fixture
podman run -d --name dryssh-fixture -p 127.0.0.1:22222:22 localhost/dryssh-fixture
./android/fixture/install-codex.sh
adb -s emulator-5554 shell svc wifi enable
adb -s emulator-5554 shell cmd wifi connect-network AndroidWifi open
python3 android/test.py --skip-build \
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

Device harnesses temporarily mark Android's immersive-mode tutorial as confirmed
while instrumentation runs, then restore the previous secure setting, including
after failure. This prevents the system tutorial window from taking focus away
from editor, IME and clipboard tests on a fresh emulator. Application UI tests
still wait for actual Android window focus before keyboard actions and assertions.
The setting follows [AOSP's immersive confirmation state](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/wm/ImmersiveModeConfirmation.java).

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
The maintainer reports successful ARM64 physical-device basic testing. Detailed
platform-specific acceptance remains documented in [progress.md](progress.md).
