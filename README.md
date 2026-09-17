# inlay

Fetch and verify WebAssembly modules from OCI registries for the JVM.

## Quick start

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.roastedroot</groupId>
      <artifactId>inlay-maven-plugin</artifactId>
      <version>${inlay.version}</version>
      <executions>
        <execution>
          <goals><goal>fetch</goal></goals>
          <configuration>
            <modules>
              <module>
                <imageRef>ghcr.io/roastedroot/sqlite4j-wasm:3.51.0</imageRef>
                <outputFile>${project.build.directory}/wasm/libsqlite3.wasm</outputFile>
              </module>
            </modules>
          </configuration>
        </execution>
      </executions>
    </plugin>

    <plugin>
      <groupId>run.endive</groupId>
      <artifactId>endive-compiler-maven-plugin</artifactId>
      <version>${endive.version}</version>
      <executions>
        <execution>
          <goals><goal>compile</goal></goals>
          <configuration>
            <wasmFile>${project.build.directory}/wasm/libsqlite3.wasm</wasmFile>
            <name>com.example.SqliteModule</name>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

See [examples/basic/](examples/basic/).

## Caching

Fetched artifacts are cached in `~/.cache/inlay/` (or `$XDG_CACHE_HOME/inlay/`), keyed by OCI digest. Survives `mvn clean`.

| Flag | Behavior |
|------|----------|
| (default) | Cache hit → no network |
| `-Dinlay.update` | Re-resolves digest, updates lock file |
| `-Dinlay.noCache` | Always pulls from registry |
| `-Dinlay.skip` | Skips fetch entirely |

## Lock file

`inlay:fetch` writes `wkg.lock` using the [wkg format](https://github.com/bytecodealliance/wasm-pkg-tools) (Rust [wasm-pkg-common](https://crates.io/crates/wasm-pkg-common) types compiled to wasm). Commit it for reproducible builds.

If the registry digest changes but the lock hasn't been updated, the build fails.

### Upgrading

```sh
mvn io.roastedroot:inlay-maven-plugin:fetch -Dinlay.update
```

## Local development

When iterating on a wasm module locally, you can avoid re-fetching from the registry:

**Option A — `outputFile` outside `target/`:** set `outputFile` to a path that `mvn clean` won't delete. Once the lock entry exists and the file is on disk, the plugin skips fetching:

```xml
<module>
  <imageRef>ghcr.io/roastedroot/my-module-wasm:1.0.0</imageRef>
  <outputFile>${project.basedir}/src/main/resources/wasm/my-module.wasm</outputFile>
</module>
```

Rebuild your wasm locally and overwrite that file — the plugin will not touch it.

**Option B — skip the fetch entirely:**

```sh
mvn compile -Dinlay.skip
```

Useful when you manage the wasm file yourself and don't need the plugin at all during local builds.

## Authentication

Resolved in order:

1. Maven `settings.xml` `<server>` entries keyed by registry hostname
2. Docker/Podman credential stores (`~/.docker/config.json`)

For local dev: [`oras login ghcr.io`](https://oras.land/docs/installation) or `docker login ghcr.io`. For CI:

```xml
<server>
  <id>ghcr.io</id>
  <username>${env.GHCR_USER}</username>
  <password>${env.GHCR_TOKEN}</password>
</server>
```

## Package references

Use wkg-style names instead of full OCI refs. Namespaces resolve via `~/.config/wasm-pkg/config.toml`:

```xml
<module>
  <packageRef>roastedroot:sqlite4j-wasm@3.51.0</packageRef>
</module>
```

Builtins: `wasi:*` → `wasi.dev`, `ba:*` → `bytecodealliance.org`.

## Signature verification

Verification runs inline after fetch — no separate step. Configure on each module:

```xml
<module>
  <imageRef>ghcr.io/roastedroot/sqlite4j-wasm:3.51.0</imageRef>
  <outputFile>${project.build.directory}/wasm/libsqlite3.wasm</outputFile>
  <sigstoreIssuer>https://token.actions.githubusercontent.com</sigstoreIssuer>
  <sigstoreIdentity>https://github.com/OWNER/REPO/.github/workflows/wasm-publish.yml@refs/heads/main</sigstoreIdentity>
</module>
```

`sigstoreIssuer` and `sigstoreIdentity` are matched **exactly** — a glob such as `https://github.com/myorg/*` never matches, because the `*` is compared as a literal character. For patterns, use `sigstoreIssuerRegex` / `sigstoreIdentityRegex` instead:

```xml
<sigstoreIssuer>https://token.actions.githubusercontent.com</sigstoreIssuer>
<sigstoreIdentityRegex>https://github\.com/myorg/.*</sigstoreIdentityRegex>
```

Setting both the exact and the regex form of the same field is an error. Setting neither leaves that field unconstrained.

To read the right values off an existing signature, inspect the signing certificate in the bundle: the identity is its subject alternative name, and the issuer is the OIDC issuer extension (`1.3.6.1.4.1.57264.1.1`). An artifact signed locally rather than by CI has the issuer `https://github.com/login/oauth` and the signer's email address as its identity.

> **A reusable workflow's identity is shared.** For a job run through a reusable workflow, the certificate identity is the *reusable* workflow's ref — for the publisher below, `https://github.com/roastedroot/inlay/.github/workflows/wasm-publish.yml@refs/heads/main` — and it is the same for **every** repository that calls it. Pinning it proves the artifact was signed by something using that workflow, not that it came from the repository you expect. The calling repository appears only in other certificate extensions, which inlay does not currently check. If that distinction matters to you, sign from a workflow of your own instead.

Uses [sigstore-java](https://github.com/sigstore/sigstore-java) keyless verification. The bundle is discovered through OCI referrers on the resolved manifest digest — nothing needs to sit next to the artifact. If several sigstore bundles are attached, each is tried in a stable order and any one that verifies against the configured identity is accepted.

Verification binds to the digest recorded in `wkg.lock`, and the artifact is fetched by that digest, so re-pointing a tag cannot change what you get.

> **Sign with `cosign sign-blob`, not `cosign sign`.** `cosign sign` binds the signature to the OCI manifest digest inside a DSSE envelope, and sigstore-java (through 2.2.0) cannot parse those payloads: it requires an in-toto subject `name`, which is optional per the spec and which cosign omits. inlay reports this explicitly rather than failing obscurely. Once [sigstore-java](https://github.com/sigstore/sigstore-java) makes `InTotoPayload.Subject#getName` `@Nullable`, `cosign sign` will verify with no change to inlay.

## Publishing wasm to OCI

Requires [oras CLI](https://oras.land/docs/installation) and optionally [cosign](https://docs.sigstore.dev/cosign/system_config/installation/) v3+ (on cosign v2.x, add `--new-bundle-format` to `sign-blob`). Packages appear at `https://github.com/orgs/<org>/packages` — [set visibility to public](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility) after first push.

```sh
echo $GHCR_TOKEN | oras login ghcr.io -u $GHCR_USER --password-stdin

oras push ghcr.io/roastedroot/sqlite4j-wasm:3.51.0 \
  libsqlite3.wasm:application/wasm

cosign sign-blob --yes \
  --bundle libsqlite3.wasm.sigstore.json \
  libsqlite3.wasm

oras attach \
  --artifact-type application/vnd.dev.sigstore.bundle.v0.3+json \
  ghcr.io/roastedroot/sqlite4j-wasm:3.51.0 \
  libsqlite3.wasm.sigstore.json:application/vnd.dev.sigstore.bundle.v0.3+json
```

### GitHub Actions — publisher

Use the reusable workflow included in this repo:

```yaml
name: Publish Wasm
on:
  push:
    paths: ['wasm-build/**']
    branches: [main]
permissions:
  contents: read
  packages: write
  id-token: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./wasm-build/build.sh
  publish:
    needs: build
    uses: roastedroot/inlay/.github/workflows/wasm-publish.yml@main
    with:
      wasm-file: wasm-build/output/my-module.wasm
      image-ref: ghcr.io/roastedroot/my-module-wasm
      version: '1.0.0'
```

### GitHub Actions — consumer

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'
      - run: mvn verify
```

No wasm build step, no Docker, no Rust. `inlay:fetch` pulls from GHCR during `generate-sources`.

## Building from source

Requires JDK 17+ and Rust with `wasm32-wasip1` target. Docker for integration tests.

```sh
cd wkg-wasm && make build
mvn install                  # unit tests
mvn verify                   # unit + integration tests (Docker required)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for architecture and development details.
