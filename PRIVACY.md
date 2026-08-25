# Privacy Policy for Shellwave

**Last updated:** 25 August 2026

Shellwave ("the app") is an SSH client for Android. This policy explains what
data the app handles and, just as importantly, what it does not.

## Summary

Shellwave does not collect, transmit, or share any of your data. It has no
analytics, no crash reporting, no advertising, and no account system. All
data you enter — hosts, credentials, scripts, settings — stays on your
device, encrypted at rest, unless you explicitly export or share it yourself.

## Data the app stores

Everything below is stored **locally on your device only**, in a local
database and the Android Keystore. None of it is sent to the developer or
any third party.

- **Host details** you add (hostname, port, username, nicknames, per-host
  settings such as terminal profile or colour scheme).
- **Credentials** (passwords and/or private keys) you choose to save for a
  host, encrypted using Android's hardware-backed Keystore. Access to saved
  credentials can be gated behind your device's biometric/lock-screen
  authentication.
- **Scripts and automation triggers** you create for running commands on
  your hosts.
- **App preferences**, such as key bar layouts and terminal bell settings.

## Data the app transmits

The app connects, over SSH, only to the hosts **you** configure. That
network traffic goes directly from your device to the server you specified —
it never passes through the developer or any intermediary service. Shellwave
has no backend server of its own.

The app requests local network access solely so it can reach SSH servers on
your local network (e.g. a home server or Raspberry Pi).

## Data you choose to export

If you use the app's config export/import feature, an export file
(containing host and script configuration) is written to storage you
choose. Sharing that file is entirely under your control — Shellwave does
not upload it anywhere on its own.

## Third parties

Shellwave does not integrate any third-party analytics, advertising, or
tracking SDKs. It does not sell or share your data because it does not
collect any.

## Permissions

- **Internet / Local network access** — to establish SSH connections to
  hosts you configure.
- **Foreground service** — to keep SSH sessions and automations running
  while the app is backgrounded.
- **Notifications** — to alert you about session status and automation
  results.
- **Vibrate** — for haptic feedback (e.g. terminal bell).

## Data deletion

Because all data lives only on your device, uninstalling the app (or
deleting individual hosts/credentials within it) permanently removes that
data. There is nothing stored elsewhere to delete.

## Children's privacy

Shellwave is not directed at children and does not knowingly collect data
from anyone, including children, since it collects no data at all.

## Changes to this policy

If this policy changes, the "Last updated" date above will be revised and
the new version published at the same location.

## Contact

Questions about this policy can be raised via
[GitHub Issues](https://github.com/LordOfPolls/Shellwave/issues) or by
emailing dev@lordofpolls.com
