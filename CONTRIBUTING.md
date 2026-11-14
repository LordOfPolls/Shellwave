# Contributing

This covers how to get a change into Shellwave.
It's an SSH client, so the bar for "it compiles" is not the bar for "it's merged".

## Before you start

Open an issue for anything bigger than a bug fix, and wait for a reply before you build it.
A PR for a feature nobody agreed to is a PR that sits there.

Some things are off limits:

- `terminal-core/` is a byte-identical vendored copy of Termux's terminal engine. Don't modify it.
- Terminal gestures are frozen. Don't add or change them.
- No accept-all host key verification, anywhere. Host keys are trust-on-first-use through the verification gate.
- Port forwards and SOCKS5 bind to loopback by default. Binding wider is only ever the user's explicit choice.
- `fallbackToDestructiveMigration` is banned. A schema change needs a real migration and a migration test.
- Never commit a real private key, passphrase or password, including in test fixtures. CI runs gitleaks.

## Build it

```sh
./gradlew assembleFossDebug
```

Work against the `foss` flavour unless your change is about billing.
`play` adds Play Billing and nothing else.

```sh
./gradlew testFossDebugUnitTest lintFossDebug
```

This is what CI runs. It has to pass before you open the PR.

<details><summary>Room migrations and Keystore tests</summary>

```sh
./gradlew connectedFossDebugAndroidTest
```

These need a device or emulator.
Run them if you touched `core/db/` or `core/crypto/`.
The biometric vault test fails on an emulator with no fingerprint enrolled; that one's expected.

</details>

## Test it on a real phone

Every PR has to be tested on at least one physical Android device. An emulator doesn't count on its own.
The one exception is a PR that only touches docs or CI. Tick just "Docs" and/or "Build / CI" and the device table, device checkbox and test guide can stay empty.

Emulators lie about the things Shellwave depends on: soft keyboards and IMEs, physical keyboards, the hardware Keystore, biometrics, doze and background limits on the foreground service, and flaky mobile networks.
Most of the bugs worth fixing only show up on real hardware.

You also need to write a test guide in the PR. That's a list of steps a reviewer can follow on their own phone to see your change working. A good one:

1. Says what server setup it needs, if any (sshd config, key type, jump host, port forward).
2. Lists the exact taps and keystrokes.
3. Says what you should see, and what happened before the change.

"Tested it, works" is not a test guide.

## Write your commits

Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/#summary), with a scope where one fits:

```
fix(terminal): send AltGr characters from physical keyboards
```

| Prefix     | Use it for                                    |
|------------|-----------------------------------------------|
| `feat`     | A new feature                                 |
| `fix`      | A bug fix                                     |
| `docs`     | Documentation only                            |
| `refactor` | Code changes that don't fix or add anything   |
| `perf`     | Performance                                   |
| `test`     | Adding or fixing tests                        |
| `build`    | Gradle, dependencies                          |
| `ci`       | Workflows                                     |
| `chore`    | Anything that doesn't touch source or tests   |
| `revert`   | Reverting an earlier commit                   |

For a breaking change, add `!` after the prefix: `feat(db)!: drop the legacy key table`.
Commits that don't follow this get squashed on merge, or you'll be asked to rename them.

Comments explain *why*, never *what*. If a comment restates the code, it'll be asked to go.
Non-trivial logic comes with one small test that fails when it breaks. Keep it on the JVM where you can.

## Open the pull request

Target `dev`. `master` is what ships, and only moves at release.
The exception is a fix for a live release: target `master` and say so in the description.
Fill in every section of the template. A CI check reads the description and fails the PR if a section is empty, the device table is blank, the test guide has fewer than two steps, or a checklist box is unticked.
Editing the description re-runs the check, so you don't need to push again.

## Disclose AI use

Using AI tools is fine. Hiding it isn't.
The template asks you to tick exactly one of these:

- No AI tools were used.
- AI tools assisted (autocomplete, chat, review), and you've reviewed every line.
- The PR was written by an AI agent. Name the model and tool.

If you're an LLM or coding agent opening this PR, you tick the third box and name yourself.
Don't tick "No AI tools were used", and don't claim physical-device testing a human didn't do.

Whoever opens the PR is responsible for every line in it, whoever typed it.
PRs that turn out to be undisclosed generated code get closed.

> [!NOTE]
> Why ask at all: generated code tends to look plausible and fail at the edges, and the edges are where an SSH client keeps your credentials.
> Knowing how a change was made tells a reviewer where to look harder.

## Licence

Shellwave is GPLv3. By contributing, you agree your contribution is licensed under the same terms.
