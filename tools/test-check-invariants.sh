#!/usr/bin/env bash
set -euo pipefail

repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q
git config user.email test@test
git config user.name test
mkdir -p app/src/main/java app/src/foss/java app/src/play/java terminal-core
cp "$OLDPWD/tools/check-invariants.sh" check-invariants.sh
echo 'class A' > app/src/main/java/A.kt
echo 'x' > terminal-core/x
git add -A && git commit -qm init

check() {
    local desc="$1" want="$2"; shift 2
    set +e; ./check-invariants.sh "$@" >/dev/null 2>&1; local got=$?; set -e
    [[ "$got" == "$want" ]] || { echo "FAIL: $desc (want $want, got $got)" >&2; exit 1; }
    echo "ok: $desc"
}

check "clean tree" 0 --cached

echo y >> terminal-core/x && git add terminal-core/x
check "staged terminal-core change" 1 --cached
git reset -q
git checkout -q -- terminal-core/x

echo 'db.fallbackToDestructiveMigration() // `fallbackToDestructiveMigration`' > app/src/main/java/A.kt
git add app/src/main/java/A.kt
check "banned API, backtick-escaped comment" 1 --cached
git reset -q -- app/src/main/java/A.kt && git checkout -q -- app/src/main/java/A.kt

echo ' * `PromiscuousVerifier` is never used' > app/src/main/java/A.kt
git add app/src/main/java/A.kt
check "kdoc-only mention" 0 --cached
git reset -q -- app/src/main/java/A.kt && git checkout -q -- app/src/main/java/A.kt

echo 'PromiscuousVerifier()' > app/src/foss/java/B.kt
git add app/src/foss/java/B.kt
check "banned call under app/src/foss" 1 --cached
git reset -q
rm -f app/src/foss/java/B.kt

base=$(git rev-parse HEAD)
echo 'class Unrelated' > app/src/main/java/C.kt
git add -A && git commit -qm "normal commit, no terminal-core change"
check "base-ref mode, normal base" 0 "$base"

echo y >> terminal-core/x && git add -A && git commit -qm "edit terminal-core"
echo 'class D' > app/src/main/java/D.kt
git add -A && git commit -qm "unrelated second commit"
check "base-ref mode, multi-commit range" 1 "$base"

echo "all tests passed"
