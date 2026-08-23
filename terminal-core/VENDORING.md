# Vendoring notes — termux-app `terminal-emulator`

- **Upstream repo:** https://github.com/termux/termux-app
- **Upstream commit:** `3df69d1da197dd9bd71a3bafd902dffd720576b4` (branch `master`, fetched
  2026-08-21)
- **Upstream path:** `terminal-emulator/src/main/java/com/termux/terminal/`
- **Upstream licence:** GPLv3 only (see `terminal-emulator`/`terminal-view` exception note in
  upstream `LICENSE.md`: this code originates from `jackpal/Android-Terminal-Emulator`,
  Apache-2.0, but is distributed by termux-app under the repository's overall GPLv3 licence).
  This project vendors it under GPLv3 per the plan's licensing decision.
- **Vendored into:** `terminal-core/src/main/java/com/termux/terminal/` (package kept as
  `com.termux.terminal`, unchanged, so the files stay a mergeable diff against upstream)

## Files vendored byte-identical (no edits)

The plan named exactly these ten:

- `TerminalEmulator.java`
- `TerminalBuffer.java`
- `TerminalRow.java`
- `TerminalColors.java`
- `TerminalColorScheme.java`
- `TextStyle.java`
- `WcWidth.java`
- `KeyHandler.java`
- `TerminalOutput.java`
- `ByteQueue.java`

Verified with `diff -q` against the upstream checkout — all ten are byte-for-byte identical
to upstream at the commit above.

## Files added beyond the plan's list (required to compile)

`TerminalEmulator.java` has two compile-time dependencies that are neither pty/JNI code nor in
the plan's ten-file list:

- **`Logger.java`** — vendored **byte-identical** (no edits). It is a small static logging
  shim (`Logger.logError/logWarn/.../getStackTraceString`) with no pty/JNI/`TerminalSession`
  dependency of its own, so nothing precluded vendoring it unmodified.
- **`TerminalSessionClient.java`** — vendored **with edits** (see below). Upstream declares this
  as the callback interface between `TerminalSession` (the pty/JNI class we are deliberately not
  vendoring) and its client, so most of its methods are typed in terms of `TerminalSession`
  (`onTextChanged(TerminalSession)`, `onSessionFinished(TerminalSession)`, etc.), which would drag
  in `TerminalSession` transitively if vendored as-is.

  `TerminalEmulator.java` itself only ever calls two members of its `mClient` field —
  `mClient.getTerminalCursorStyle()` and `mClient.onTerminalCursorStateChange(boolean)` — plus,
  via `Logger`, the five `log*(String, String)` methods. Every other member of the upstream
  interface (`onTextChanged`, `onTitleChanged`, `onSessionFinished`, `onCopyTextToClipboard`,
  `onPasteTextFromClipboard`, `onBell`, `onColorsChanged`, `setTerminalShellPid`,
  `logStackTraceWithMessage`, `logStackTrace`) is unused by the vendored file set and was removed
  from the interface, because every one of them is typed in terms of `TerminalSession`.

  This was the minimum edit that let `TerminalEmulator.java` compile standalone without vendoring
  `TerminalSession`/JNI, without touching `TerminalEmulator.java` itself. The trimmed file carries
  a comment explaining the cut so it's obvious on inspection why it diverges from upstream.

## Files explicitly not vendored

`TerminalSession.java`, `JNI.java`, and all pty/native code under `terminal-emulator/src/main/jni`
— these drive a local subprocess via a native pseudoterminal, which this app does not need (we
drive the emulator from an SSH stream instead). Confirmed there is no NDK, no
`externalNativeBuild`, and no `.c`/`.h` file anywhere in this module.

## Module

`terminal-core/build.gradle.kts` is a plain `com.android.library` Java module (no Kotlin plugin
applied), namespace `com.termux.terminal` — matching the vendored source package rather than the
app's `io.github.lordofpolls.shellwave` namespace, since these files are kept as an unmodified,
mergeable copy of upstream. Only dependency is `androidx.annotation:annotation`, matching what
upstream's own `terminal-emulator/build.gradle` depends on.
