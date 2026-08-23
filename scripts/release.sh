#!/bin/sh
# Release com.github.primegraph:primegraph-core-kt to GitHub Packages Maven.
#
# Usage: sh scripts/release.sh <semver>       e.g. sh scripts/release.sh 1.4.0
#
# The argument is a bare semver with no leading "v"; the git tag gets the "v".
# Every check that can fail runs before anything is mutated, so an already
# released version stops the script instead of half-publishing it.
#
# Requires the Central Portal user token and the GPG signing key, both read by
# Gradle from ~/.gradle/gradle.properties and never from this repo:
#   mavenCentralUsername / mavenCentralPassword   — the Central Portal token
#   signingKeyId / signingInMemoryKeyPassword     — which GPG key to sign with
#
# The secret key itself stays in the GPG keyring and is exported into the
# environment for the length of the upload: a properties file cannot hold its
# newlines, and a copy on disk is one more place it can leak from.
#
# rootProject.name in settings.gradle matters as much as the artifactId. A
# Gradle source-dependency consumer (includeBuild / dependencySubstitution)
# matches requested coordinates on group + *project name*, not on the published
# artifactId, so the two are deliberately kept equal: renaming one without the
# other silently breaks one of the two consumption paths.

set -eu

usage() {
  echo "Usage: sh scripts/release.sh <semver>   (bare semver, no leading 'v')" >&2
  exit 1
}

[ "$#" -eq 1 ] || usage
VERSION="$1"

SEMVER_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
if ! printf '%s' "$VERSION" | grep -Eq "$SEMVER_RE"; then
  echo "release: '$VERSION' is not a bare semver (expected 1.4.0, not v1.4.0)" >&2
  exit 1
fi
TAG="v$VERSION"

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

if [ -x ./gradlew ]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
else
  echo "release: neither ./gradlew nor gradle is available" >&2
  exit 1
fi

# --- preflight --------------------------------------------------------------

DEFAULT_BRANCH=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)
[ -n "$DEFAULT_BRANCH" ] || DEFAULT_BRANCH=main

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" != "$DEFAULT_BRANCH" ]; then
  echo "release: on branch '$CURRENT_BRANCH', releases are cut from '$DEFAULT_BRANCH' only" >&2
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "release: the working tree is dirty, commit or stash first" >&2
  git status --short >&2
  exit 1
fi

if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null; then
  echo "release: tag $TAG already exists locally, nothing was changed" >&2
  exit 1
fi

if [ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]; then
  echo "release: tag $TAG already exists on origin, nothing was changed" >&2
  exit 1
fi

git fetch --quiet origin "$DEFAULT_BRANCH"
if [ "$(git rev-parse HEAD)" != "$(git rev-parse "origin/$DEFAULT_BRANCH")" ]; then
  echo "release: HEAD and origin/$DEFAULT_BRANCH differ, pull or push first" >&2
  exit 1
fi

# Checked before anything is bumped or tagged: Central rejects an unsigned or
# unauthenticated bundle, and a half-done release is worse than none.
GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
for key in mavenCentralUsername mavenCentralPassword signingKeyId signingInMemoryKeyPassword; do
  if ! grep -Eq "^${key}=" "$GRADLE_PROPS" 2>/dev/null; then
    echo "release: $key is missing from $GRADLE_PROPS, the Central upload would fail after tagging" >&2
    exit 1
  fi
done

if ! grep -Eq '^version=' gradle.properties; then
  echo "release: gradle.properties has no 'version=' line to bump" >&2
  exit 1
fi

# --- version, build ---------------------------------------------------------

echo "release: preparing $TAG"
TMP_PROPS=$(mktemp)
sed "s#^version=.*#version=$VERSION#" gradle.properties > "$TMP_PROPS"
mv "$TMP_PROPS" gradle.properties

"$GRADLE" --quiet build

# --- commit, tag, push ------------------------------------------------------

git add gradle.properties
git commit -m "chore(release): $TAG"
git tag -a "$TAG" -m "$TAG"
git push origin "$DEFAULT_BRANCH"
git push origin "$TAG"

# --- publish ----------------------------------------------------------------

SIGNING_KEY_ID=$(grep '^signingKeyId=' "$GRADLE_PROPS" | cut -d= -f2-)
SIGNING_PASS=$(grep '^signingInMemoryKeyPassword=' "$GRADLE_PROPS" | cut -d= -f2-)
ORG_GRADLE_PROJECT_signingInMemoryKey=$(gpg --batch --pinentry-mode loopback \
  --passphrase "$SIGNING_PASS" --armor --export-secret-keys "$SIGNING_KEY_ID")
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$SIGNING_PASS"
export ORG_GRADLE_PROJECT_signingInMemoryKey ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
if [ -z "$ORG_GRADLE_PROJECT_signingInMemoryKey" ]; then
  echo "release: could not export $SIGNING_KEY_ID from the GPG keyring" >&2
  exit 1
fi

# publishAndReleaseToMavenCentral uploads the signed bundle and promotes it, so
# no manual step is left in the Portal UI.
if ! "$GRADLE" --quiet publishAndReleaseToMavenCentral --no-configuration-cache; then
  echo "release: $TAG is committed, tagged and pushed but the Central upload failed" >&2
  echo "release: fix the cause and re-run only the upload, do not re-run this script" >&2
  exit 1
fi

echo "release: published io.github.primegraph:primegraph-core-kt:$VERSION ($TAG)"
