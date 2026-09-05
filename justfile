# Shellwave. `just` on its own lists what there is.
#
#   just bump 1.2.0     rewrite the version and the changelogs from the commit history
#   just notes          read what the release page will say
#   just release        commit, tag and push what bump wrote
#   just bundle         build the signed Play Store .aab
#   just publish        push that .aab to the Play internal testing track
#


set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

fdroid_file := "fdroid/io.github.lordofpolls.shellwave.yml"

release_branch := "master"

default:
    @just --list

# Install the pre-commit hook that runs the invariant checks and gitleaks
hooks:
    ln -sf ../../tools/pre-commit.sh .git/hooks/pre-commit

# Set the version and rewrite changelogs
bump VERSION:
    @tools/prepare-release.sh {{ VERSION }}

# Print the release notes as the workflow would write them for the commits since the last tag.
notes:
    #!/usr/bin/env bash
    set -euo pipefail
    eval "$(tools/version.sh)"
    git-cliff --config cliff.toml --tag "$name" --unreleased --strip all

# Build the signed Play Store bundle
bundle:
    #!/usr/bin/env bash
    # Play needs the `play` flavour and an .aab, neither of which the tag workflow produces: that
    # builds the foss APK for the release page and F-Droid.
    set -euo pipefail

    eval "$(tools/version.sh)"

    # The same sources app/build.gradle.kts reads: the environment, then either gradle.properties
    # (GRADLE_USER_HOME's wins, as it does for Gradle itself). Catching it here beats finding out
    # from Play, three minutes of R8 later.
    props=("${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties" gradle.properties)
    signing_prop() {
        local name=$1 file value
        if [[ -n ${!name:-} ]]; then
            printf '%s' "${!name}"
            return
        fi
        for file in "${props[@]}"; do
            [[ -f $file ]] || continue
            # java.util.Properties, not shell: keys may be indented and separated by =, : or a
            # space. Trailing whitespace is trimmed, which Properties would keep - a signing value
            # that ends in a space is a typo here, not a password.
            value=$(awk -v k="$name" '
                { line = $0
                  sub(/^[[:space:]]+/, "", line)
                  if (line ~ "^" k "[[:space:]]*[=:]")
                      sub("^" k "[[:space:]]*[=:][[:space:]]*", "", line)
                  else if (line ~ "^" k "[[:space:]]")
                      sub("^" k "[[:space:]]+", "", line)
                  else
                      next
                  sub(/[[:space:]]+$/, "", line)
                  print line
                  exit
                }' "$file")
            if [[ -n $value ]]; then
                printf '%s' "$value"
                return
            fi
        done
    }

    missing=()
    for var in SHELLWAVE_RELEASE_STORE_FILE SHELLWAVE_RELEASE_STORE_PASSWORD \
               SHELLWAVE_RELEASE_KEY_ALIAS SHELLWAVE_RELEASE_KEY_PASSWORD; do
        [[ -n $(signing_prop "$var") ]] || missing+=("$var")
    done
    if (( ${#missing[@]} )); then
        echo "bundle: no release signing config - ${missing[*]} unset in the environment and in ${props[*]}" >&2
        exit 1
    fi

    store=$(signing_prop SHELLWAVE_RELEASE_STORE_FILE)
    if [[ ! -f $store ]]; then
        echo "bundle: SHELLWAVE_RELEASE_STORE_FILE points at $store, which does not exist" >&2
        exit 1
    fi

    changelog="fastlane/metadata/android/en-US/changelogs/$code.txt"
    if [[ ! -s $changelog ]]; then
        echo "bundle: $changelog is missing or empty - run 'just bump $name' before uploading" >&2
        exit 1
    fi

    ./gradlew bundlePlayRelease

    aab=app/build/outputs/bundle/playRelease/app-play-release.aab

    # The preflight above reads this shell's environment; AGP reads the Gradle daemon's, captured
    # when the daemon started. They can disagree, and the build says nothing when they do - so ask
    # the artefact, which is the only thing Play's rejection would be about. zipfile rather than
    # `unzip -l | grep`: unzip exits 1 on harmless warnings, and pipefail would read that as
    # "unsigned" while a perfectly good bundle sits on disk.
    # startswith anchors to the archive root: base/root/META-INF/*.RSA is an app resource.
    if ! python3 -c 'import sys, zipfile; ns = zipfile.ZipFile(sys.argv[1]).namelist(); sys.exit(0 if any(n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC")) for n in ns) else 1)' "$aab"; then
        echo "bundle: $aab came out UNSIGNED - Play will reject it." >&2
        echo "The signing config did not reach the Gradle daemon: './gradlew --stop' and retry." >&2
        exit 1
    fi

    # Signed is not the same as signed with YOUR key: -Pshellwave.allowDebugSignedRelease produces
    # a bundle that passes the check above, and it is exactly the flag someone reaches for when the
    # daemon is not picking the config up. Match fingerprints rather than blocklisting the debug
    # key's CN, which only catches the stock keystore. The store password goes in on stdin, never
    # in an argument where ps would show it.
    fingerprint() { awk '/SHA256:/ { print $2; exit }'; }
    cert=$(keytool -printcert -jarfile "$aab" 2>&1 || true)
    vault=$(printf '%s\n' "$(signing_prop SHELLWAVE_RELEASE_STORE_PASSWORD)" \
        | keytool -list -v -keystore "$store" \
              -alias "$(signing_prop SHELLWAVE_RELEASE_KEY_ALIAS)" 2>&1 || true)
    signer=$(printf '%s\n' "$cert" | fingerprint)
    expected=$(printf '%s\n' "$vault" | fingerprint)

    # keytool prints one block per signer in archive order, not signing order, so with more than
    # one the first SHA256 line is a coin flip - refuse rather than compare the wrong cert.
    signers=$(printf '%s\n' "$cert" | grep -c '^Signer #' || true)
    if (( ${signers:-0} > 1 )); then
        echo "bundle: $aab has $signers signers; cannot tell which key Play would record." >&2
        exit 1
    fi

    if [[ -z $signer || -z $expected ]]; then
        # Never report a pass this did not make: Android Studio's JBR keytool is often off $PATH,
        # but a stale alias or store password lands here too - say which, rather than guessing.
        echo "bundle: could not compare signing certificates - $aab is signed, but not verified" >&2
        echo "as signed with $store." >&2
        printf '%s\n' "$cert" "$vault" \
            | grep -m2 -iE 'not found|does not exist|password|no such file' >&2 || true
    elif [[ $signer != "$expected" ]]; then
        echo "bundle: $aab is signed with the WRONG KEY - never upload this." >&2
        echo "  bundle   $signer" >&2
        echo "  keystore $expected" >&2
        exit 1
    fi

    echo
    echo "bundle: $name (versionCode $code) -> $aab"
    echo "'just publish' uploads it to the internal track, with these release notes:"
    echo
    cat "$changelog"

# Upload the built bundle to the Play internal testing track
publish *ARGS:
    #!/usr/bin/env bash
    # The uploader lives in scripts/, which is not tracked - it holds a path to a Play service
    # account key, and nothing in CI needs it.
    set -euo pipefail

    if [[ ! -x scripts/play-publish.py ]]; then
        echo "publish: scripts/play-publish.py is missing (scripts/ is untracked - copy it in)" >&2
        exit 1
    fi

    scripts/play-publish.py {{ ARGS }}

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
