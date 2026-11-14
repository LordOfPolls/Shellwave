#!/usr/bin/env bash
# Reads a PR description on stdin and fails if the template wasn't filled in.
set -euo pipefail

body=$(tr -d '\r' | perl -0pe 's/<!--.*?-->//gs')
errors=()

section() {
  awk -v h="## $1" '$0 == h {on=1; next} /^## / {on=0} on' <<<"$body"
}
ticked() { grep -cE '^- \[[xX]\]' <<<"$1" || true; }
unticked() { grep -cE '^- \[ \]' <<<"$1" || true; }

for h in "What this changes" "Type" "Tested on" "How to test it" "AI disclosure" "Checklist"; do
  grep -qxF "## $h" <<<"$body" || errors+=("Missing the \"$h\" heading.")
done

grep -q '[[:alnum:]]' <<<"$(section "What this changes")" ||
  errors+=("\"What this changes\" is empty.")

(($(ticked "$(section "Type")") >= 1)) || errors+=("Tick at least one box under \"Type\".")

devices=$(section "Tested on" | grep '^|' | grep -v '^|[- |]*$' | grep -vi '^| *device *|' |
  grep -c '^| *[^ |]' || true)
types=$(section "Type")
docs_or_ci_only=0
if ! grep -qE '^- \[[xX]\] (Bug fix|Feature|Refactor)$' <<<"$types" &&
  grep -qE '^- \[[xX]\] (Docs|Build / CI)$' <<<"$types"; then
  docs_or_ci_only=1
fi

((docs_or_ci_only || devices >= 1)) || errors+=("List at least one physical device under \"Tested on\".")

steps=$(section "How to test it" | sed -E 's/^ *([0-9]+\.|[-*]) *//' | grep -c '[[:alnum:]]' || true)
((docs_or_ci_only || steps >= 2)) || errors+=("\"How to test it\" needs at least two steps a reviewer can follow.")

ai=$(section "AI disclosure")
if (($(ticked "$ai") != 1)); then
  errors+=("Tick exactly one box under \"AI disclosure\".")
elif grep -qE '^- \[[xX]\] This PR was written by an AI agent: *$' <<<"$ai"; then
  errors+=("Name the model and tool after \"written by an AI agent:\".")
fi

checklist=$(section "Checklist")
((docs_or_ci_only)) && checklist=$(grep -v 'physical device' <<<"$checklist")
(($(unticked "$checklist") == 0)) || errors+=("Every box under \"Checklist\" must be ticked.")

if ((${#errors[@]})); then
  printf '::error::%s\n' "${errors[@]}"
  echo "See CONTRIBUTING.md and .github/pull_request_template.md."
  exit 1
fi
echo "PR description looks complete."
