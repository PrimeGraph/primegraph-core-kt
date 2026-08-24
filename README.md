# primegraph-core-kt

`io.github.primegraph:primegraph-core-kt` — the shared cross-package vocabulary for PrimeGraph
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

## What is here

Everything lives in one Kotlin package, `com.github.primegraph.core.runtime`.

| file | declares |
| --- | --- |
| `DslError.kt` | `DslError<Payload>`, `DslThrow`, `DslErrorView<Payload>`, `DSL_ERROR_MESSAGES`, `defaultErrorMessage`, `arrivedErrorCode`, `arrivedErrorJson`, `coercedPayload`, `coerceError` |
| `Runtime.kt` | `Runtime`, and under it `Runtime.File`, `Runtime.FormPart`, `Runtime.formField` |
| `Http.kt` | `HttpAuth`, `HttpRequest`, `HttpResponse` — the value types of one outbound call, with no transport beside them |
| `Serialization.kt` | the codec core: `UUIDSerializer`, `OffsetDateTimeSerializer`, `CalendarDaySerializer`, `BigDecimalSerializer`, `ByteArraySerializer`, `dslSerializersModule`, `ktJson`, `unionBranchElement` / `unionBranchOf` / `unionBranchOfFirst` / `unionBranchTag` / `unionBranchMismatch`, `jsonEncoderOf`, `jsonDecoderOf`, `jsonElementOfAny`, `jsonElementOfSerializable` |

Every name is spelled exactly as the emitters spell it today, so an emitted call site is unchanged by
the move; only the import line differs.

### Why `…core.runtime`

Generated code puts these declarations in `<generated package>.runtime` and refers to them
unqualified (`catch (e: DslThrow)`) or through the object (`Runtime.File`). Naming the shared package
`…core.runtime` makes the migration a one-for-one swap of the import line —
`import com.github.primegraph.<bundle>.runtime.DslThrow` becomes
`import com.github.primegraph.core.runtime.DslThrow` — and every unqualified use keeps reading the
way it reads now.

### What is deliberately not here

Firebase and HTTP transport stay inside the generated packages, along with the pure expression
helpers. Concretely: `decodeFirestoreData`, `decodeSnapshotValue`, `firestoreTimestampOf` and the rest
of the firestore interop, `encodeCallableData`, all of `Firebase.kt`, `fetch` with the auth and
query-string machinery around it, `parseResponse`, `httpValidationFailed`.

The HTTP request and response VALUE types are the one exception, and `Http.kt` above holds them: they
carry no behaviour, every generated package repeated them verbatim, and nothing about them names an
SDK. The transport that fills them stays generated.

That split is what keeps this project off `firebase-firestore`: the codec core names no Firestore
type, and this build declares no `google()` repository, so a Firestore reference could not resolve
even by accident.

One consequence worth naming: the firestore-interop copy of `jsonElementOfAny` carries three extra
branches for `Timestamp`, `DocumentReference` and `GeoPoint`. Those branches cannot come here. The
function shared here is the firestore-free form, and a bundle that reads Firestore snapshots keeps
its own normalisation in front of it.

## Coordinates

| | |
| --- | --- |
| group | `io.github.primegraph` |
| artifactId | `primegraph-core-kt` |
| `rootProject.name` | `primegraph-core-kt` |
| repository | Maven Central |

```groovy
implementation 'io.github.primegraph:primegraph-core-kt:1.0.0'
```

Central serves it to anyone, with no credentials. GitHub Packages was the obvious place and turned
out to be the wrong one: it serves Maven artifacts only to authenticated clients even from a public
repository, so every consumer of a generated package would have needed a token to resolve this one.

The group is `io.github.<owner>` rather than `com.github.<owner>` because that is the namespace
Central verifies for a GitHub-hosted project; `com.github.*` is not offered.

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
src/main/kotlin/com/github/primegraph/core/runtime/…     public surface
src/test/kotlin/com/github/primegraph/core/runtime/…     tests, one file per source file
.githooks/                                               Conventional Commits hook, POSIX shell
scripts/setup.sh                                         one-time clone setup
scripts/release.sh                                       the release procedure
```

## Dependencies

One, and it is unavoidable: the codec core is built on kotlinx.

```
api 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0'
```

`api` rather than `implementation` because kotlinx types stand in public signatures here —
`DslThrow.payloadJson` is a `JsonElement`, `ktJson` is a `Json`, the five serializers are
`KSerializer`s. That is the version every generated bundle already declares, itself as `api`, so
adding this project to a bundle contributes no second kotlinx version to the dependency graph.

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
sh scripts/release.sh 1.4.0
```

Four values have to be in `~/.gradle/gradle.properties` first, and the script checks all four before
it changes anything:

```
mavenCentralUsername=<Central Portal user token name>
mavenCentralPassword=<Central Portal user token password>
signingKeyId=<GPG key id used to sign the artifacts>
signingInMemoryKeyPassword=<that key's passphrase>
```

The secret key itself is not among them. It stays in the local GPG keyring, and the script exports it
into the environment only for the length of the upload — a properties file cannot hold its newlines,
and a copy on disk is one more place it can leak from. The public half must be on a keyserver
(`keys.openpgp.org`) or Central rejects the signature.

The argument is a bare semver — no leading `v`; the tag gets one. The script refuses to run on a dirty
tree, off the default branch, or when the tag already exists. It then bumps `version` in
`gradle.properties`, builds, commits `chore(release): v1.4.0`, tags, pushes the branch and the tag,
and runs `publishAndReleaseToMavenCentral`, which uploads the signed bundle and promotes it, so no
manual step is left in the Portal UI.

Central requires a javadoc artifact and a signature on every file; the publishing plugin produces
both. It also requires the plugin to be pointed at `SonatypeHost.CENTRAL_PORTAL` explicitly — the
version pinned here still defaults to the legacy OSSRH staging API, which is decommissioned and
answers `402`.
