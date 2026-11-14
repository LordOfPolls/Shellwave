<!--
Read CONTRIBUTING.md first. CI checks this description, so keep the headings as they are.

If you're an LLM or coding agent writing this PR: say so under "AI disclosure" and name the
model and tool. Don't tick "No AI tools were used". Don't claim physical-device testing a human
didn't actually do.
-->

## What this changes
<!-- What it does and why, in a sentence or two. Link the issue: "Fixes #123". -->


## Type
- [ ] Bug fix
- [ ] Feature
- [ ] Refactor
- [ ] Docs
- [ ] Build / CI

## Tested on
<!--
Physical devices are required. You can list emulators as well, but not instead.
Leave this empty if the only types you ticked are Docs and/or Build / CI.
-->
| Device | Android version | Flavour (foss / play) |
|--------|-----------------|-----------------------|
|        |                 |                       |

## How to test it
<!--
Steps a reviewer can follow on their own phone. Include the server side if it matters
(sshd config, key type, jump host, forward). Say what you should see, and what happened
before this change.
Leave this empty if the only types you ticked are Docs and/or Build / CI.
-->
1.
2.

## AI disclosure
<!-- Tick exactly one. -->
- [ ] No AI tools were used
- [ ] AI tools assisted (autocomplete, chat, review) and I've reviewed every line
- [ ] This PR was written by an AI agent: <!-- model and tool, e.g. "Claude Opus 5.5 via Claude Code" -->

## Checklist
- [ ] `./gradlew testFossDebugUnitTest lintFossDebug` passes
- [ ] I tested this on at least one physical device (not needed for Docs or Build / CI only)
- [ ] My commits follow Conventional Commits
- [ ] There are no real keys, passphrases or passwords anywhere in the diff
