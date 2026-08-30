<h1 align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" alt="Shellwave app icon">
  <br>
  ~/Shellwave
</h1>

<p align="center">
  An SSH client for Android
  <br>
  Built because every mobile SSH client makes you choose between convenient and trustworthy,
  and that's a stupid choice.
  <br>
  Requires Android 12 (API 31) or later. GPLv3.
</p>

<p align="center">
  <a href="https://github.com/LordOfPolls/Shellwave/releases">
    <img src="https://img.shields.io/github/v/release/LordOfPolls/Shellwave?label=Release" alt="Latest release">
  </a>
  <a href="https://github.com/LordOfPolls/Shellwave/actions/workflows/release.yml">
    <img src="https://github.com/LordOfPolls/Shellwave/actions/workflows/release.yml/badge.svg" alt="Release build status">
  </a>
  <img src="https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white" alt="Android 12 or later">
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/LordOfPolls/Shellwave?label=Licence" alt="Licence">
  </a>
</p>

## Screenshots

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01_terminal.png" width="205" alt="A session tailing a coloured log, with three session tabs above it">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02_hosts.png" width="205" alt="The host list with a quick-connect field">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04_ansi_truecolour.png" width="205" alt="16-colour, 256-colour and 24-bit ramps rendered in a session">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06_per_host_settings.png" width="205" alt="Per-host settings: tmux reattach, profile and colour scheme overrides, key bar, ProxyJump">
</p>

Unfolded, the session list and the terminal share the screen; collapsing the split gives the
terminal the full width.

<p>
  <img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/02_split_view.png" width="410" alt="Session list and terminal side by side on an unfolded device">
  <img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/01_terminal_full_width.png" width="410" alt="The terminal filling the full width of an unfolded device">
</p>

## Features

- Dropped connections reconnect on their own, without re-prompting for a password; optional
  `tmux`-backed sessions reattach with scrollback intact.
- Host key verification on first connect, with the fingerprint shown. A changed key blocks the
  connection; there is no accept-all mode, and a background run never answers a prompt for you.
- Credentials encrypted with the Android Keystore, hardware-backed where available, with an
  optional biometric unlock per credential.
- Key enrolment: generate a key, install it in the host's `authorized_keys`, replace the saved
  credential. The private key never leaves the device.
- Password, private key (imported or generated on device), and keyboard-interactive auth.
- Local and remote port forwarding, SOCKS5, ProxyJump chaining, `~/.ssh/config` import.
- Saved scripts, run from the app, a widget, an app shortcut, a Quick Settings tile, or another app.
- SFTP upload and download.
- Per-host terminal profile, colour scheme and key bar layout, each overriding a global default.
- 256-colour and truecolour output, wide characters, alternate screen.
- No analytics, no telemetry, no crash reporting. It talks to the hosts you give it, and nothing
  else.

## Install

Every release ships a signed APK, built by GitHub Actions from the tag - an unsigned build
never reaches the release page:

**[Download the latest release](https://github.com/LordOfPolls/Shellwave/releases/latest)**

> [!NOTE]
> Forwarded ports and the SOCKS5 proxy bind to loopback unless you say otherwise. Binding wider is
> a choice you have to make on purpose, which is rather the point. The coffee shop Wi-Fi does not
> need a route into your homelab.

## Building

Two product flavours, identical but for one dependency:

```
./gradlew assembleFossRelease   # no proprietary dependencies - the F-Droid build
./gradlew assemblePlayRelease   # adds Play Billing, for the optional one-time tip
```

`SupporterBilling` is an interface in `:app`'s main source set with one implementation per
flavour, so only `play` links `com.android.billingclient`.
On `foss` the Support section of Settings does not exist.

Debug builds, unit tests and lint:

```
./gradlew assembleFossDebug
./gradlew testFossDebugUnitTest
./gradlew lintFossDebug
```

The Room migration and Keystore tests are instrumented, so they want a real device or emulator -
which is to say the two things you least want quietly broken are the two that take the most effort
to check.

## Terminal engine

Writing a VT parser from scratch is a fine way to lose a year, so this one is borrowed.

`:terminal-core` contains twelve files from termux-app's `terminal-emulator` module, ten of them
byte-identical to upstream. `TerminalRenderer.kt` in `:app` is a Kotlin port of upstream's
`TerminalRenderer.java`. The package is left as `com.termux.terminal` so the vendored files stay a
mergeable diff.

`terminal-core/VENDORING.md` records the upstream commit, the file list and the edits made. The
engine's pty/JNI subprocess code is not used; sessions are remote shells over SSH.

Third-party attribution lives in `NOTICE`, which a Gradle task copies into `res/raw` for the in-app
licence screen.

## Licence

GPLv3 - see `LICENSE`. The vendored terminal engine is GPLv3 from termux-app; `NOTICE` has the
details.
