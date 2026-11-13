# Releasing DrySSH

## Signing identity

Run once on the release machine:

```sh
python3 android/release.py --init
```

This creates a dedicated RSA-4096 PKCS12 key and random password in
`~/.local/share/dryssh/signing/`. The directory is private (`0700`), and its
`release.p12` and `signing.json` files are private (`0600`). The key is valid for
10,000 days. `--signing-dir /absolute/path` selects another directory outside the
repository. Existing identities are never overwritten.

Back up **both files together**, encrypted and offline. Future releases must use
the same identity. Do not upload either file as a release asset or place it in
the repository. Passwords pass to signing tools through their environment.

## Build and verify

Set up the toolchains as described in the README, then run:

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
python3 android/release.py --test
cd android
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintRelease
```

The script always builds ARM64 and x86_64, signs each APK using the dedicated
identity and verifies it with `apksigner`. It never falls back to the debug key.
`android/build.py --release` alone produces one unsigned APK per ABI for CI:
`dryssh-arm64-v8a-release-unsigned.apk` and `dryssh-x86_64-release-unsigned.apk`.
Each APK contains only its own `libdryssh.so`, and one device runs one of them.
Local proxy/AAPT2 options can be passed through `--gradle-arg=OPTION`.

For version 0.1.0, review these files in `target/android/release/0.1.0/`:

- `dryssh-0.1.0-arm64-v8a.apk` and `dryssh-0.1.0-x86_64.apk`: signed per-ABI APKs.
- `dryssh-0.1.0.zip`: both APKs, release notes, certificate and vendored notices.
- `SIGNING-CERTIFICATE.txt`: verified certificate details and SHA-256 fingerprint.
- `SHA256SUMS`: hashes of both APKs, the archive and the certificate file.

`--test` also creates `dryssh-test.apk` with the same release identity. It is a
test-only artifact and is not included in the ZIP or public assets. On a clean
test device, use the fixture instructions in [development.md](development.md),
then run:

```sh
python3 android/test.py --skip-build \
  --apk target/android/release/0.1.0/dryssh-0.1.0-x86_64.apk \
  --test-apk target/android/release/0.1.0/dryssh-test.apk \
  --ssh-host 10.0.2.2 --ssh-dns-host 10.0.2.2.nip.io --isolate-dns
```

The existing debug-signed installation cannot be updated using the new release
identity. Do not uninstall a personal installation without first retaining its
connection details and original private keys: uninstalling clears app data.
For subsequent official versions, verify `adb install -r` preserves saved
profiles, credentials and settings, and that saved credentials still authenticate.

The restart harness also checks saved profiles and both password/encrypted-key
authentication after replacing the APK, force-stop and reboot. Build its explicit
runner first, then run it on the test device:

```sh
python3 android/release.py --test \
  --gradle-arg=-Pdryssh.runner=dev.dryssh.CredentialRestartRunner
python3 android/restart.py --serial emulator-5554 \
  --apk target/android/release/0.1.0/dryssh-0.1.0-x86_64.apk \
  --test-apk target/android/release/0.1.0/dryssh-test.apk
```

Build again without `dryssh.runner` to restore the ordinary instrumentation runner.

## Publish

Before each release, update the Rust workspace version and Android `versionName`
together, increment Android `versionCode`, and add `changelog.d/VERSION.md`.
Use one Markdown file per version (for example, `changelog.d/0.2.0.md`), with the
user-visible changes and compatibility notes for that version. Keep previous
files as release history. This file becomes the GitHub Release body and is also
included in the release ZIP.

Run CI and device acceptance against the candidate. Review `SHA256SUMS` and the
certificate fingerprint, and compare the fingerprint with the previous release.
Commit and push the version changes, then publish a stable version tag:

```sh
git tag -a v0.2.0 -m 'Release 0.2.0'
git push origin v0.2.0
```

The Release workflow runs on `v*` tag pushes. It rejects tags other than
`vX.Y.Z`, mismatched Rust/Android versions, and missing or empty changelog files
before building. It runs Rust and Android checks, tests APK signatures and tamper
detection, then uses the existing release identity to build and verify both APKs.
Only successful builds create a GitHub Release with these five assets: the two
signed APKs, ZIP, `SIGNING-CERTIFICATE.txt`, and `SHA256SUMS`. Test APKs and signing
material are excluded.

Configure these repository Actions secrets before pushing a release tag:

- `DRYSSH_SIGNING_P12_BASE64`: base64 encoding of `release.p12`.
- `DRYSSH_SIGNING_JSON`: the complete contents of `signing.json`.

The workflow restores them to a private temporary directory outside the checkout
and removes it after signing, including on failure. It uses the built-in
`GITHUB_TOKEN` with `contents: write` to publish. The local release script still
does not create tags, push commits or publish releases.
