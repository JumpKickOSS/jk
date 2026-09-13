# Official JumpKick Maven repository

First-party artifacts (`cc.jumpkick.*`, and later `build.jumpkick.*`) resolve from a **GCS-backed
Maven repository**, not Maven Central. This is the path that makes a clean `jk` install able to
fetch workers (test-runner, kotlin-compiler, …) without a prior `jk install`.

## URL layout

| Role | URL |
|------|-----|
| **Product (baked into jk)** | `https://jumpkick.build/repo/` |
| **Current origin** (Hosting 302) | `https://storage.googleapis.com/jumpkick/repo/` |
| **GCS** | `gs://jumpkick/repo/` |

Maven path (standard):

```text
repo/
  cc/jumpkick/
    jk-test-runner/
      0.13.0/
        jk-test-runner-0.13.0.jar
        jk-test-runner-0.13.0.jar.sha256
        jk-test-runner-0.13.0.pom
        jk-test-runner-0.13.0.pom.sha256
      maven-metadata.xml
    jk-kotlin-compiler/
      …
```

Same bucket as **releases** (`releases/<ver>/jk`, `jk-engine-…jar`); only the prefix differs
(`repo/` vs `releases/`).

## Resolution rules (jk client)

1. Built-in remotes (when you declare none, or as fill-ins): **jumpkick → central → google**.
2. JumpKick is an **exclusive specialist** for `cc.jumpkick`, `cc.jumpkick.*`, `build.jumpkick`,
   `build.jumpkick.*` only. Those coordinates never resolve from Central (dependency-confusion
   safe). For every other groupId, remotes are **central then google** — JumpKick is **not**
   probed (no 404-then-fallthrough on first-party CDN for third-party GAVs).
3. **Plugin workers** (`PluginJar`): look in `repos/jk-local`, then the official repository's
   store (`repos/jumpkick` at the product URL; a `JK_OFFICIAL_REPO_URL` mirror gets its own
   origin-keyed tree), then `repos/central`; on miss, **HTTP-fetch** from the official repo into
   its store.
4. **Path / git remotes** prepended for a project must **preserve exclusive group bindings**
   from the base remote set (otherwise a path overlay can drop JumpKick exclusivity and cause
   a 404 storm against the CDN for third-party coordinates).

Override base URL: `JK_OFFICIAL_REPO_URL=https://…/repo/`.

User documentation (exclusive groups + custom internal repos): [repositories](../user/repositories.md).

## Publish (with a binary release)

```bash
jk build --skip-tests && jk install --skip-tests
export JK_RELEASE_RSA_SIGNING_KEY_FILE=/owner-only/path/release-key.pem
DIST_DIR=target/dist scripts/assemble-release-dir.sh build/release/0.13.0
# upload releases/
gsutil -m rsync -r build/release/0.13.0/ gs://jumpkick/releases/0.13.0/
# upload first-party Maven modules
scripts/publish-maven-repo.sh
```

The release workflow's linux-x86_64 lane runs `jk install` and then stages the repository with
`publish-maven-repo.sh`; the publish job uploads it beside `releases/`.

### The worker POM

A worker's POM is the one its launch classpath is rebuilt from. `jk install` renders it from the
module's jk.toml: `<dependencies>` names what the worker declares, with vendored workspace
siblings hoisted, and `<dependencyManagement>` pins every coordinate of the resolved runtime
closure, so a launch runs on the versions the build tested whatever a transitive POM asks for. The
engine walks that POM nearest-wins (Maven's rule) and applies the pins at every depth. Each
first-party rung on the shelf carries its own POM declaring its direct dependencies, so the walk
reaches a rung's third-party needs without the worker listing them. G19 (`published-poms`) refuses
a POM naming a coordinate this build does not publish.

`publish-maven-repo.sh` refuses to stage a worker POM (an artifact whose module lives under
`plugins/`) that declares no dependencies, or that names a `cc.jumpkick` artifact the stage does
not hold at that version — either way the published worker could not start. The fixture test
covers both refusals and a well-formed worker.

Every `maven-metadata.xml` the script writes is a merge: it fetches the artifact's current
metadata from the repository over the public origin (no credentials needed to read), unions
the version list with what is staged, and names the merged maximum as `<latest>` (`<release>`
skips snapshots). An artifact the store holds only at other versions keeps the repository's
metadata. `scripts/test-publish-maven-repo.sh` runs the merge against a fixture, network-free.

`jk-guards-junit` (the guard-test library a project's `src/guard` suite compiles against) rides the
same path as the worker jars: `jk install` shelves `cc/jumpkick/jk-guards-junit/<ver>/` (jar + POM)
into `store/repos/jk-local`, the engine copies it from `~/.m2` when the store lacks it (`jk sync`,
or the first `compile-guard`), and the lock pins it under `[[plugin]]` at jk's version.

## Why not only `releases/`?

- `releases/` is the **product** layout (native client + engine jar + checksums).
- `repo/` is a **Maven repository** so dependency resolution, exclusive groups, and future
  `build.jumpkick` libraries share one coordinate system (`group:artifact:version`).
