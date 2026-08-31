#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

tools/check-invariants.sh --cached

if command -v gitleaks >/dev/null; then
    gitleaks protect --staged --no-banner
else
    echo "gitleaks not installed - skipping secret scan"
fi
