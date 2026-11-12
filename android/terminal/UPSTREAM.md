# Terminal dependency

Source: https://github.com/connectbot/termlib

Pinned commit: `e777be1df1c98c0e01af77787b9a83060c3e6c2d`.

`src/main` is a source snapshot, including upstream libvterm, with the local patches below.
The local build file removes publishing, benchmarks and documentation plugins.
Local changes to terminal behavior must be recorded here, with regression tests.
The Apache-2.0 license is in `LICENSE`; libvterm retains its own MIT license.

## Local patches

- `ImeInputView.kt`: normal input uses a full Android text connection, including
  extracted text for speech input, with personalized learning disabled. Composition
  remains in the local editable until commit or finish; committed speech corrections
  are applied as terminal edits. The optional upstream compose-overlay mode retains
  its existing behavior. Regression: `ImeIntegrationTest` drives the actual Android
  input connection through OpenSSH, including composition replacement, consecutive
  speech commits, deletion, finish and a following Ctrl shortcut.
