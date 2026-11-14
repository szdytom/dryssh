# Terminal dependency

Source: https://github.com/connectbot/termlib

Pinned commit: `e777be1df1c98c0e01af77787b9a83060c3e6c2d`.

`src/main` is a source snapshot, including upstream libvterm, with the local patches below.
The local build file removes publishing, benchmarks and documentation plugins.
Local changes to terminal behavior must be recorded here, with regression tests.
The Apache-2.0 license is in `LICENSE`; libvterm retains its own MIT license.

## Local patches

- `Terminal.kt`: acquire input focus independently of soft-keyboard visibility
  and restore it when the terminal window becomes active, except in accessibility
  review mode. Attach the focus requester before its focus target. Regression:
  `TerminalFocusTest` checks hardware characters, Tab and arrows with the IME
  hidden, terminal taps after a control takes focus, background return, menu
  dismissal and dialog input isolation/restoration.

- `ImeInputView.kt`: normal input uses a full Android text connection, including
  extracted text for speech input, with personalized learning disabled. Composition
  remains in the local editable until commit or finish; committed speech corrections
  are applied as terminal edits. The optional upstream compose-overlay mode retains
  its existing behavior. Regression: `ImeIntegrationTest` drives the actual Android
  input connection through OpenSSH, including composition replacement, consecutive
  speech commits, deletion, finish and a following Ctrl shortcut.

- `Terminal.kt` / `ImeInputView.kt`: report the rendered terminal cursor through
  Android cursor-anchor updates, including the screen transform, font metrics,
  selection and composing text. Candidate windows track the terminal instead of
  the hidden editor. Regression: `ImeCursorPositionTest` checks cursor movement,
  composition, layout offsets, font changes and immediate/monitor requests.

- `keyboard.c` / `vterm.h`, terminal JNI and emulator API: expose the kernel's
  bracketed-paste flag and an atomic guarded paste. A rejected guarded paste
  emits no bytes. Regression: `TerminalIntegrationTest` covers split negotiation,
  mode changes, exact multiline framing and refusal with the mode disabled.

- Mouse JNI/emulator API: atomically consult negotiated mouse/alternate-screen
  modes before encoding clicks or wheel steps. DEC 1007 controls alternate scroll
  (enabled by default); reset clears mouse protocol/button state. Native protocol
  regression: `MouseProtocolTest`.
- `Terminal.kt`: route taps and finger/wheel scroll to the remote application when
  requested, otherwise retain local history scrolling. Callers can supply a local
  long-press menu, explicit selection and history controls; this disables implicit
  double-tap selection. End-to-end: `TouchClipboardTest` drives the actual view,
  tmux and nvim, checks the resulting file, and verifies the system clipboard.
