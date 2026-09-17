# CI and releases

The workflows follow avro2s's PR, pre-release and release approach. This build uses
`actions/checkout@v4`, `coursier/setup-action@v1`, Temurin 21, sbt 1.13.0,
sbt-pgp 2.3.1 and Central Portal uploads. Scala is pinned
to 3.3.8, so `sbt test` tests the whole build without the original project's
Scala 2 cross-build or sbt-plugin scripted tests.

## Workflows

- **Pull Request** runs all tests, including generated-code and property tests.
- **Pre-Release** runs on pushes to `main` or manual dispatch on `main`. It removes
  `-SNAPSHOT` and pushes a `Version ... [release]` commit. Commits containing
  `[snapshot]`, `[release]` or `[skip release]` do not automatically start it.
  Changes confined to README, docs or workflow files also do not start it.
- **Release** runs for `[release]` commits on `main`, or manual dispatch on `main`
  when `build.sbt` already has a stable version. It validates the version, tests,
  stages signed artifacts with `sbt publishSigned`, then runs `sbt sonaUpload`.
  After upload, it increments the minor version and pushes a `[snapshot]` commit.
  If `main` has moved meanwhile, it stops that version bump for manual handling.

`sonaUpload` uploads a **User Managed** deployment: finish validation and publishing
in the [Central Portal](https://central.sonatype.com/), as with avro2s. A successful
workflow upload is not itself confirmation that artifacts are publicly available.
There is no snapshot-publishing workflow. The next `-SNAPSHOT` commit marks the next
development version only. For example: `0.1.0-SNAPSHOT` → `0.1.0` → `0.2.0-SNAPSHOT`.

## Repository setup

Configure these GitHub Actions secrets on `psilicon/avro2s-wire` (or grant it access
to the existing organization secrets used by avro2s):

| Secret | Purpose |
| --- | --- |
| `GH_PAT` | Token permitted to push the version commits to `main`; its pushes trigger the next workflow. |
| `GPG_PRIVATE_KEY` | Base64-encoded signing private key, in the same format as avro2s. |
| `GPG_PASSPHRASE` | Signing key passphrase. |
| `SONATYPE_USERNAME` | Central Portal user-token username. |
| `SONATYPE_PASSWORD` | Central Portal user-token password. |

The Central account must be authorized for `io.psilicon`, and the signing public key
must meet Central's requirements. Branch rules must allow the version bot's pushes.
The PR workflow uses no publishing secrets. Set up the secrets before merging
release-triggering changes. Use `[skip release]` on the merge/push commit to defer
release preparation, then manually dispatch Pre-Release on `main` when ready.

Only `avro2s-wire-runtime_3`, `avro2s-wire-compiler_3`, `avro2s-wire-java-backend_3`
and `avro2s-wire-resolution_3` are published, under `io.psilicon`. Root, fixtures,
benchmarks and property-test modules retain `publish / skip := true`.

Local checks that do not upload anything:

```sh
sbt test 'runtime/makePom' 'compiler/makePom' 'javaBackend/makePom' 'resolution/makePom'
```

The signing and upload steps require the repository secrets and Central service;
a local test run does not exercise them.
