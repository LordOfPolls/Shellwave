# Shellwave. `just` on its own lists what there is.
#
#   just bump 1.2.0     rewrite the version and the changelogs from the commit history
#   just notes          read what the release page will say
#   just release        commit, tag and push what bump wrote
#


set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

fdroid_file := "fdroid/io.github.lordofpolls.shellwave.yml"

release_branch := "master"

default:
    @just --list

# Set the version and rewrite changelogs
bump VERSION:
    @tools/prepare-release.sh {{ VERSION }}

# Print the release notes as the workflow would write them for the commits since the last tag.
notes:
    #!/usr/bin/env bash
    set -euo pipefail
    eval "$(tools/version.sh)"
    git-cliff --config cliff.toml --tag "$name" --unreleased --strip all

# Commit, tag and push the release `bump` prepared. This publishes.
release:
    #!/usr/bin/env bash
    set -euo pipefail

    eval "$(tools/version.sh)"
    files=(
        app/build.gradle.kts
        CHANGELOG.md
        "fastlane/metadata/android/en-US/changelogs/$code.txt"
        {{ fdroid_file }}
    )

    branch=$(git branch --show-current)
    if [[ $branch != {{ release_branch }} ]]; then
        echo "release: on '$branch', not {{ release_branch }} - merge first, or see release_branch in the justfile" >&2
        exit 1
    fi

    if git rev-parse -q --verify "refs/tags/$name" >/dev/null; then
        echo "release: tag $name already exists - bump to a new version first" >&2
        exit 1
    fi

    pending=$(git status --porcelain -- "${files[@]}")
    if [[ -z $pending ]]; then
        echo "release: nothing to release - run 'just bump <version>' first" >&2
        exit 1
    fi

    echo
    echo "Releasing $name (versionCode $code) from $branch:"
    echo "$pending"
    echo
    echo "Only those files are committed; anything else you have modified stays put."
    echo "The tag push builds the APK and publishes the release page."
    read -r -p "Go? [y/N] " reply
    if [[ $reply != [yY] ]]; then
        echo "release: stopped. Nothing committed, tagged or pushed."
        exit 0
    fi

    git add -- "${files[@]}"
    git commit -qm "chore(release): $name"
    git tag "$name"

    git push origin --atomic HEAD "$name"

    echo "release: pushed $name. The build is at https://github.com/LordOfPolls/Shellwave/actions"
