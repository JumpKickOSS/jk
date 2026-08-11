# Official JumpKick Maven repository

First-party artifacts (`cc.jumpkick.*`, and later `build.jumpkick.*`) resolve from a **GCS-backed
Maven repository**, not Maven Central. This is the path that makes a clean `jk` install able to
fetch workers (test-runner, kotlin-compiler, …) without a prior `./gradlew installLocal`.

## URL layout

| Role | URL |
|------|-----|
| **Canonical (CDN / site)** | `https://jumpkick.build/repo/` |
| **Origin (always works)** | `https://storage.googleapis.com/jkbuild-releases/repo/` |
| **GCS** | `gs://jkbuild-releases/repo/` |

Maven path (standard):

```text
repo/
  cc/jumpkick/
    jk-test-runner/
      0.12.0/
        jk-test-runner-0.12.0.jar
        jk-test-runner-0.12.0.jar.sha256
        jk-test-runner-0.12.0.pom
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
3. **Plugin workers** (`PluginJar`): look in `repos/local`, then `repos/jumpkick`, then
   `repos/central`; on miss, **HTTP-fetch** from the official repo into `repos/jumpkick/`.
4. **Path / git remotes** prepended for a project must **preserve exclusive group bindings**
   from the base remote set (otherwise a path overlay can drop JumpKick exclusivity and cause
   a 404 storm against the CDN for third-party coordinates).

Override base URL: `JK_OFFICIAL_REPO_URL=https://…/repo/`.

User guide (exclusive groups + custom internal repos): [guide.md](guide.md#auth-and-repositories).

## Publish (with a binary release)

```bash
./gradlew clean dist installLocal
export JK_RELEASE_SIGNING_KEY=…   # optional for binary .sig
scripts/assemble-release-dir.sh build/release/0.12.0
# upload releases/
gsutil -m rsync -r build/release/0.12.0/ gs://jkbuild-releases/releases/0.12.0/
# upload first-party Maven modules
scripts/publish-maven-repo.sh
```

CI (when Actions billing works) should call `publish-maven-repo.sh` after `installLocal` in the
same tag job that uploads `releases/`.

## Why not only `releases/`?

- `releases/` is the **product** layout (native client + engine jar + checksums).
- `repo/` is a **Maven repository** so dependency resolution, exclusive groups, and future
  `build.jumpkick` libraries share one coordinate system (`group:artifact:version`).
