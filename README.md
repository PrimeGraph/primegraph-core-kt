# primegraph-core-kt

`com.github.primegraph:primegraph-core-kt` — the shared cross-package vocabulary for PrimeGraph
generated Kotlin packages.

## Why this project exists

The PrimeGraph compiler generates one package per graph bucket. Each generated package used to carry
its own private copy of a runtime, so a type declared in that runtime existed once per package. When
one generated package handed a value to another — a model field, a thrown error — the two copies were
different nominal types and the code broke: it failed to compile in Go and Swift, and in Kotlin a
`catch` silently failed to match, because the thrown class and the caught class were two unrelated
types that merely shared a name.

This project holds the vocabulary that crosses package boundaries, so a graph has exactly one nominal
type per concept no matter how many generated packages it spans.

Per-bundle machinery — Firebase, HTTP transport, server helpers — stays inside the generated packages
and does **not** belong here.

There are five of these, one per target language:
`primegraph-core-ts`, `primegraph-core-go`, `primegraph-core-swift`, `primegraph-core-py`,
`primegraph-core-kt`.

## Status

Scaffolding only. No shared declarations have been migrated yet;
`src/main/kotlin/com/github/primegraph/core/PrimeGraphCore.kt` holds a single placeholder so the build
has something to compile.

## Coordinates

| | |
| --- | --- |
| group | `com.github.primegraph` |
| artifactId | `primegraph-core-kt` |
| `rootProject.name` | `primegraph-core-kt` |
| repository | GitHub Packages Maven, `https://maven.pkg.github.com/primegraph/primegraph-core-kt` |

`rootProject.name` is load-bearing. A Gradle source-dependency consumer — `includeBuild` with
`dependencySubstitution` — matches the requested coordinates on group + **project name**, not on the
published `artifactId`. The two are kept equal on purpose so both the binary and the source
consumption path resolve to the same thing; changing one without the other silently breaks the other.

This is a plain Kotlin/JVM `java-library`, not a `com.android.library`. It touches no Android API, and
a JVM artifact at toolchain 17 is consumable by both the generated Android libraries and the generated
JVM ones.

## Layout

```
build.gradle                                             plugins, coordinates, publishing
settings.gradle                                          rootProject.name
gradle.properties                                        version, bumped by scripts/release.sh
src/main/kotlin/com/github/primegraph/core/…             public surface
.githooks/                                               Conventional Commits hook, POSIX shell
scripts/setup.sh                                         one-time clone setup
scripts/release.sh                                       the release procedure
```

## Setup

A fresh clone has to be pointed at the repository's own hooks once:

```sh
git config core.hooksPath .githooks
```

`sh scripts/setup.sh` does that for you.

The hook rejects any commit message that is not a Conventional Commit: `type(scope)!: subject`, one of
`build chore ci docs feat fix perf refactor revert style test`, header at most 100 characters, no
trailing period.

## Build

```sh
./gradlew build
```

## Releasing

```sh
GITHUB_ACTOR=<user> GITHUB_TOKEN=<token with write:packages> sh scripts/release.sh 1.4.0
```

The argument is a bare semver — no leading `v`; the tag gets one. The script refuses to run on a dirty
tree, off the default branch, or when the tag already exists, and it does all of those checks before it
changes anything. It then bumps `version` in `gradle.properties`, builds, commits
`chore(release): v1.4.0`, tags, pushes the branch and the tag, and runs `gradle publish`.
