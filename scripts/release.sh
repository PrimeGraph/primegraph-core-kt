#!/bin/sh
# Release com.github.primegraph:primegraph-core-kt to GitHub Packages Maven.
#
# Usage: sh scripts/release.sh <semver>       e.g. sh scripts/release.sh 1.4.0
#
# The argument is a bare semver with no leading "v"; the git tag gets the "v".
# Every check that can fail runs before anything is mutated, so an already
# released version stops the script instead of half-publishing it.
#
# Requires GITHUB_ACTOR and GITHUB_TOKEN, the latter with write:packages.
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

if [ -z "${GITHUB_ACTOR:-}" ] || [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "release: GITHUB_ACTOR and GITHUB_TOKEN must be set, gradle publish would fail after tagging" >&2
  exit 1
fi

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

if ! "$GRADLE" --quiet publish; then
  echo "release: $TAG is committed, tagged and pushed but gradle publish failed" >&2
  echo "release: fix the cause and re-run only 'gradle publish', do not re-run this script" >&2
  exit 1
fi

echo "release: published com.github.primegraph:primegraph-core-kt:$VERSION ($TAG)"
