# Releasing

## Versioning

[Semantic versioning](https://semver.org), with the version living in exactly one place —
`appVersionName` in `app/build.gradle.kts`. It drives the manifest, the Settings footer
**and** the APK filename, so a file sitting in a downloads folder always identifies itself.

`appVersionCode` must be bumped alongside it. Android compares the code, not the name, to
decide something is an upgrade; leaving it unchanged makes a rebuilt APK look identical to
the installer.

| Change | Bump |
|---|---|
| Bug fix, no new behaviour | patch — `1.4.0` → `1.4.1` |
| New feature, backwards compatible | minor — `1.4.1` → `1.5.0` |
| Breaking change to stored data or radio behaviour | major |

## Signing

Release builds are signed only if `keystore.properties` exists at the repo root. It is
gitignored, along with `*.jks`. Without it the release variant still builds but comes out
**unsigned**, and Android refuses to install it with
`INSTALL_PARSE_FAILED_NO_CERTIFICATES`.

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias watchtastic
```

```properties
storeFile=release.jks
storePassword=…
keyAlias=watchtastic
keyPassword=…
```

**Back that keystore up somewhere durable.** The in-app updater installs an APK only if
its signing certificate matches the running app's, so losing the key means you can never
ship an update that existing installs will accept. Everyone would have to sideload again.

Signing schemes are v2 + v3, with v1 disabled — v1 (JAR signing) is only needed below
API 24 and this app is minSdk 33. `apksigner` reports v3 alone for that reason.

## Cutting a release

1. Update `appVersionName` and `appVersionCode`.
2. Add a `CHANGELOG.md` entry describing what changed and why.
3. Verify everything locally:

   ```bash
   ./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease
   ```

4. Commit and push.
5. Tag and push the tag — this triggers the release workflow:

   ```bash
   git tag -a v1.5.0 -m "Watchtastic 1.5.0"
   git push origin v1.5.0
   ```

The workflow runs the tests, rebuilds the signed APK, **verifies the signature**,
generates a checksum, and publishes the release with the APK attached.

### CI secrets

The release workflow needs four repository secrets. Without them it fails loudly rather
than publishing something unusable:

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Releases can also be published manually with `gh release create`, which is how everything
through 1.4.1 was cut.

## The updater contract

Published releases are what the in-app updater reads, so publishing is what reaches real
watches. A few consequences:

- **Drafts and pre-releases are skipped** by the client, so staging a build is safe.
- The asset must be named `Watchtastic-<version>-release.apk`. The client reads the
  version from the filename, falling back to the tag.
- **Downgrades are refused** by the client, so a mistakenly published older version won't
  roll devices back — but it will become "latest" on GitHub, so fix it promptly.
- The APK must be signed with the same key as every previous release, or no existing
  install will accept it.
