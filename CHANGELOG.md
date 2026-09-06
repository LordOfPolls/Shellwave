# Changelog


## 1.6.1 - 2026-09-06

### New
- Expose the visible terminal screen to screen readers
- List and forget trusted host keys from settings
- Browse, rename, delete and upload over SFTP from a session
- Search scrollback and log sessions to a file
- Generate ECDSA keys, honour Match blocks and attach OpenSSH certificates
- Offer the supporter donation in three tiers
- Build and publish the Play bundle from the justfile

### Fixed
- Revoke superseded host key on accepted key-change
- Add handshake read timeout to SOCKS5 forwarder
- Stop config import overwriting the default terminal profile and colour scheme
- Read the activity through LocalActivity so lint passes
- Keep SFTP browser scroll position across same-directory refreshes
- Make radio button labels tappable across the app
- Give SFTP failures a useful reason instead of OpenSSH's bare "Failure"
- Stop dropping keystrokes under rapid terminal input
- Send typed text to the selected session after a tab switch
- Let soft-keyboard autocorrect replace the word in place

### Internal
- Build, test and guard the standing invariants on every push and commit
- Freeze existing lint warnings in a baseline and fail on new ones
- Resolve a host's connection spec in one place
- Move host delete and duplicate out of MainActivity
- Split the overflow menu, script picker and split layout out of SessionsScreen
- Share the keyed form-state guard between the host and script editors
- Observe prefs changes instead of mirroring them

### Documentation
- Tweak readme

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.5.0...1.6.1)

## 1.5.0 - 2026-08-29

### New
- Let a widget tap unlock a biometric-gated host
- Move terminal settings to a dedicated screen
- Report whether a credential is biometric-gated
- Reorganise hosts form

### Fixed
- Deep-copy duplicated hosts and give Sessions an idle empty state

### Performance
- Stop polling every vsync frame for idle terminal sessions

### Internal
- Drop unused connectionCount from SupportPromptDialog

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.4.2...1.5.0)

## 1.4.2 - 2026-08-28

### Fixed
- Clamp scrollback viewport row to the live transcript when drawing
- Notification reflects host being disconnected

### Internal
- Update AGP version

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.4.1...1.4.2)

## 1.4.1 - 2026-08-26

### New
- Implement one-time supporter prompt

### Fixed
- Enable auto reconnection for Play Billing client
- Unblock a hung capture-mode command past the 120s timeout
- Keep concurrent background script runs from killing each other

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.4.0...1.4.1)

## 1.4.0 - 2026-08-26

### New
- Import a configuration export
- Keep the screen awake while a session is on screen
- Hold an arrow key on the key bar to repeat it
- Haptic feedback on key bar presses

### Fixed
- Disable quick connect on an address it cannot parse

### Internal
- A real top bar on every pushed screen

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.3.1...1.4.0)

## 1.3.1 - 2026-08-25

### Internal
- Drop the foojay-provisioned daemon JVM toolchain
- Build the release on JDK 25

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.3.0...1.3.1)

## 1.3.0 - 2026-08-25

### New
- Toast when a background trigger starts a script
- Put a capture run's output in its result notification
- Scrollable, themed widget with a header that opens the app

### Fixed
- Refresh the widget when a script is pinned or unpinned

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.2.2...1.3.0)

## 1.2.2 - 2026-08-25

### Fixed
- Keep the WorkManager InputMerger constructor R8 strips

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.2.1...1.2.2)

## 1.2.1 - 2026-08-25

### Fixed
- Missing glance view on widget

### Documentation
- Privacy.md

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.2.0...1.2.1)

## 1.2.0 - 2026-08-25

### New
- Detect a host's MAC address over SSH

### Fixed
- Show why a session failed to connect on the terminal screen

### Internal
- Write release notes from the commit history

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.1.0...1.2.0)

## 1.1.0 - 2026-08-25

### New
- Wake a host over Wake-on-LAN
- Export the app's configuration to a JSON file

### Internal
- Pin the daemon JVM with a foojay-provisioned toolchain

### Documentation
- Point to release APKs

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.0...1.1.0)

## 1.0 - 2026-08-24

### Other changes
- An Android SSH client for Android 12+
