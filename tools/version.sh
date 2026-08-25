#!/usr/bin/env bash
# Prints the version app/build.gradle.kts declares, as shell assignments:

set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

gradle_file='app/build.gradle.kts'

name=$(awk -F'"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$gradle_file")
code=$(awk -F'=' '/^[[:space:]]*versionCode[[:space:]]*=/ { gsub(/[^0-9]/, "", $2); print $2; exit }' "$gradle_file")

if [[ ! $name =~ ^[0-9]+(\.[0-9]+)+$ || ! $code =~ ^[0-9]+$ ]]; then
    echo "version: could not read versionName/versionCode from $gradle_file" >&2
    exit 1
fi

printf 'name=%s\ncode=%s\n' "$name" "$code"
