#!/usr/bin/env bash
set -euo pipefail

# Usage: check-invariants.sh <base-ref> | check-invariants.sh --cached
#
# CI passes github.event.before / pull_request.base.sha; a missing, all-zero, or unreachable
# base (new branch, force push) falls back to the merge-base with origin/master.
if [[ "${1:-}" == "--cached" ]]; then
    changed=$(git diff --cached --no-renames --name-only)
else
    base="${1:-}"
    if [[ -z "$base" ]] || [[ "$base" =~ ^0+$ ]] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
        base=$(git merge-base origin/master HEAD)
    fi
    changed=$(git diff --no-renames --name-only "$base"...HEAD)
fi

if grep -q '^terminal-core/' <<<"$changed"; then
    echo "terminal-core/ is vendored Termux and must not be modified" >&2
    exit 1
fi

if grep -rnE 'fallbackToDestructiveMigration|PromiscuousVerifier' app/src/main app/src/foss app/src/play \
    | grep -vE '^[^:]*:[0-9]+:\s*(\*|//|/\*.*\*/\s*$)'; then
    echo "banned API above: destroys the vault on a schema bump or accepts any host key" >&2
    exit 1
fi
