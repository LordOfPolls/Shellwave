#!/usr/bin/env bash
#
# Prepares a release: bumps the version, rewrites CHANGELOG.md and the F-Droid changelog

set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

gradle_file='app/build.gradle.kts'
fdroid_file='fdroid/io.github.lordofpolls.shellwave.yml'
changelog_file='CHANGELOG.md'

fdroid_changelog_limit=500

fastlane_template='{% for group, commits in commits | group_by(attribute="group") %}{% set heading = group | striptags | trim %}{% if heading in ["New", "Fixed", "Performance"] %}{{ heading }}:
{% for commit in commits %}- {{ commit.message | upper_first }}
{% endfor %}
{% endif %}{% endfor %}'

die() {
    echo "prepare-release: $*" >&2
    exit 1
}

version=${1-}
[[ -n $version ]] || die "usage: tools/prepare-release.sh <version>, e.g. 1.2.0"
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "'$version' is not major.minor.patch - the release workflow only triggers on those"

command -v git-cliff >/dev/null 2>&1 ||
    die "git-cliff is not on PATH - see the comment at the top of this script"

git diff --quiet && git diff --cached --quiet ||
    die "working tree has uncommitted changes - commit or stash them first"

! git rev-parse -q --verify "refs/tags/$version" >/dev/null ||
    die "tag $version already exists"

eval "$(tools/version.sh)"
current_name=$name current_code=$code

IFS=. read -r major minor patch <<<"$version"
code=$((10#$major * 10000 + 10#$minor * 100 + 10#$patch))

((code > current_code)) ||
    die "versionCode $code is not above the current $current_code - Android will not treat $version as an update to $current_name"

echo "prepare-release: $current_name ($current_code) -> $version ($code)"


tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

awk -v name="$version" -v code="$code" '
    !seen_code && /^[[:space:]]*versionCode[[:space:]]*=/ { sub(/=.*/, "= " code); seen_code = 1 }
    !seen_name && /^[[:space:]]*versionName[[:space:]]*=/ { sub(/=.*/, "= \"" name "\""); seen_name = 1 }
    { print }
' "$gradle_file" >"$tmp"
cat "$tmp" >"$gradle_file"



git-cliff --config cliff.toml --tag "$version" >"$tmp"
printf '%s\n' "$(cat "$tmp")" >"$changelog_file"


fdroid_changelog_file="fastlane/metadata/android/en-US/changelogs/$code.txt"
notes=$(GIT_CLIFF_TEMPLATE="$fastlane_template" \
    git-cliff --config cliff.toml --tag "$version" --unreleased --strip all)

notes=$(awk -v limit="$fdroid_changelog_limit" '
    { used += length($0) + 1 }
    used > limit { exit }
    { print }
' <<<"$notes")


[[ -n ${notes//[[:space:]]/} ]] || notes="Maintenance release. See the release notes for details."

printf '%s\n' "$notes" >"$fdroid_changelog_file"



awk -v name="$version" -v code="$code" '
    /^AutoUpdateMode:/ && !seen {
        printf "  - versionName: %c%s%c\n", 39, name, 39
        printf "    versionCode: %s\n", code
        # Quoted, like versionName above it: unquoted, a two-component tag such as 1.0 is a YAML
        # float, and F-Droid would look for a commit called "1".
        printf "    commit: %c%s%c\n", 39, name, 39
        print  "    subdir: app"
        print  "    gradle:"
        print  "      - foss"
        print  ""
        seen = 1
    }
    /^CurrentVersion:/ { printf "CurrentVersion: %c%s%c\n", 39, name, 39; next }
    /^CurrentVersionCode:/ { print "CurrentVersionCode: " code; next }
    { print }
' "$fdroid_file" >"$tmp"
cat "$tmp" >"$fdroid_file"



cat <<EOF

Written:
  $gradle_file                 versionName $version, versionCode $code
  $changelog_file
  $fdroid_changelog_file
  $fdroid_file

Review the diff, then \`just release\` - or, without just:
  git commit -am 'chore(release): $version'
  git tag $version
  git push origin --atomic HEAD $version

Pushing the tag builds the FOSS APK and publishes the release with these notes attached.
EOF
