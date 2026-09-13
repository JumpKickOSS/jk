# Lockfile

`jk-lock.toml` is **canonical**. Commit it. Day-to-day you should not think about it: any
command that needs a current lock (build, explain, status, tree, sync, export, ide, …)
auto-refreshes when the lock is missing or out of sync with manifests.

`jk build` **does not re-resolve**. That is the product.

```bash
jk lock          # resolve → write jk-lock.toml, keeping pinned versions
jk lock -F       # same, but float every pin within its declared range
jk sync          # materialize cache / --offline-prepare
jk outdated      # read-only: newer versions than the lock
jk update        # re-resolve within declared ranges; rewrite the lock
jk tree / jk why
```

There is **one** lockfile: workspace root or standalone project root. Members redirect to
the root lock. Never write per-module lockfiles.

## Commands

| Command | Role |
|---------|------|
| `jk lock` | Resolve and write the lock, keeping every pinned version; only what a changed constraint rules out moves. Metadata warm within 24h TTL (local first) |
| `jk lock -F` | The same resolve, but float every pin to the newest version its declared range allows; revalidates metadata past the TTL |
| `jk sync` | Materialize cache; `--offline-prepare` for offline CI |
| `jk outdated` | Current / Compatible / Latest table (exit 0 always on success) |
| `jk update` | Re-resolve on purpose; revalidates metadata |
| `jk build` | Uses the lock; does not re-resolve |
| `jk tree` / `jk why` | Inspect the graph offline |

Metadata indexes live under the store (`metadata/`, 24h TTL + ETag). Back-to-back `jk lock`
hits disk only; use `jk update` or `-F` when you need Central’s current version lists today.

**`jk lock` keeps pins.** Like `uv lock` and `poetry lock`, the everyday verb re-resolves without
taking upstream drift: existing pins seed the solver, and only coordinates a new or changed
constraint rules out move. That is what you want for landing a manifest edit that changes no
dependency — the lockfile diff is the manifest stamp and nothing else.

Floating is deliberate and has two spellings: `jk lock -F` (a forced lock revalidates metadata and
takes the newest compatible versions) and `jk update` (the same, plus toolchain suggestions, git
refresh and `--platform`). Automatic refreshes — a stale lock on `jk build`, or any command that
needs a current lock — always keep pins, `-F` or not.

## `jk outdated`

```bash
jk outdated
jk outdated --exclude-up-to-date
jk outdated --output json
jk outdated --offline
```

| Column | Meaning |
|--------|---------|
| **Current** | Version pinned in `jk-lock.toml` (empty if unlocked) |
| **Compatible** | Newest version that still satisfies the declared range |
| **Latest** | Newest stable in the repo (may be outside the range) |
| **Tip** | With `--show-tip`: prerelease / git frontier ahead of Latest |

Exit code is always `0` on a successful report. There is no `--fail-if-outdated` — lockfile
changes stay intentional. For CI “fail if drift”, parse `--output json`.

`--offline` uses only local cache / repo mirrors. Unreachable remotes look empty on
Compatible/Latest — the CLI prints a note so that is not mistaken for “everything is current.”

JSON is an **array** of row objects (`module`, `dependency`, `display`, `scope`, `current`,
`compatible`, `latest`, `tip`). `module` is empty for a single-project root. `display` is
the catalog short name when known.

## Toolchain pins

`jk lock` records the JDK (and GraalVM, when one was in play) that resolved the graph, on two
independent axes:

```toml
[jdk]
suggested-vendor  = "temurin"     # what built it; a later build may differ
suggested-version = "25.0.4.1"    # floor on the MAJOR: 25.0.1 and 26.x clear it, 21 does not

[graal]                           # omitted when no Graal was used
required-vendor   = "graalvm-ce"  # no choice — install it or the build fails
required-version  = "25.0.4"      # exact: 25.0.3, 25.1.0 and 26.0.2 all fail
```

`suggested-*` is a record of what created the lock. It binds nothing but the major: a build on a
newer JDK is fine, an older one is not — and because it is a record, `jk lock` leaves it as it
found it. Re-locking on a machine with a different vendor does not rewrite what built the lock;
only `jk update`, whose job is moving forward, refreshes it. An exception: a previous suggestion
that names a vendor jk cannot install is dropped and rewritten from the toolchain that resolved.
Copying `nosuchvendor-99` forward would make the next `jk build` try to install a catalog-missing
spec after the agent already removed the pin.

`required-*` is a pin the project asked for, and only an `=` in `jk.toml` writes one:

| jk.toml | lock |
| --- | --- |
| `jdk = "temurin-25"` | `suggested-vendor`, `suggested-version` |
| `jdk = "=temurin-25.0.4"` | `required-vendor`, `required-version` |
| `jdk = "=temurin-25"` | `required-vendor` + `suggested-version` (no patch to be exact about) |
| `jdk-vendor = "=corretto"`, `jdk-version = 25` | `required-vendor` + `suggested-version` |
| `[native] graal = "=25.2.4-graalce"` | `required-vendor`, `required-version` (SDKMAN order) |

The fields mix freely, and each axis is written on one side only — a required vendor makes the
suggested one meaningless. Vendors are lower-cased short ids.

Among installs that satisfy the pin, the hook prefers exact vendor+version, then the same vendor,
then the newest. A `required-*` nothing satisfies means install, never settle: the shell hook
exports nothing rather than hand over a JDK the build itself will refuse.

A `suggested-*` nothing installed satisfies is different — it is only a floor, so jk falls
through to whatever else is available (`JAVA_HOME`, `GRAALVM_HOME`, `PATH`) as long as that
still clears the major. Nothing below the floor is accepted from any of them, and if nothing
anywhere clears it, jk installs.

Locks written before this shape (a bare `vendor` / `version` pair under `[jdk]`) are rejected
rather than guessed at — re-run `jk lock`. The lockfile's `version` stays `1` until JumpKick 1.0;
the shape changes in place, never by a new number.

## Pre-release pins

A lock that records an RC/M/beta is kept by `jk lock` when it still satisfies the declared
range. A platform BOM pin (including a pre-release line) is enforced on
managed GAs while the platform is active. Unpinned `latest` still prefers the newest
**stable** over a newer pre-release. Deliberate upgrades off a pre-release belong on
`jk update`.

## Lock-time trust

`jk lock` is the trust boundary. For each POM/artifact download JumpKick:

1. Streams bytes, computes SHA-256 locally, and stores a Maven-layout `*.jar` (Maven
   local repo when `[m2] integration` is on and the slot is empty or already equal).
2. Fetches the repository’s published sidecar (`.sha256`, else `.sha1`) and **fails closed**
   on mismatch.
3. Refuses to pin when no sidecar exists, unless the repository table says
   `allow-unverified = true`; the lock summary then counts those rows as `unverified (allowed)`.
4. Refuses a plaintext `http://` repository when the manifest is read, unless its table says
   `allow-insecure = true`; the summary then names it as `insecure (allowed)`. Neither key is
   accepted on `central`. See [Repositories](repositories.md#transport-and-checksum-trust).

After the lock exists, `jk sync` / builds enforce the **pinned hashes only** — they do not
re-check upstream sidecars. A digest mismatch against the lock is a cache miss / refetch,
not a silent accept. `jk repo refresh <coord>` re-fetches a coordinate on purpose.

GPG/Sigstore for *your* publishes: [Publish](publish.md). First-write-wins and
`--offline` never networking are the store policy.

## What else the lock pins

Not everything a build depends on is a classpath entry. Alongside `[[artifact]]` the lock
carries the resolved Kotlin/Scala compiler versions, `[[plugin]]` and `[[sdk]]` rows, and:

```toml
[native]
metadata-repository = "1.1.4"
checksum = "sha256:…"
```

— the GraalVM reachability-metadata repository release, resolved from `[native]
metadata-repository` in `jk.toml`. It is not an `[[artifact]]` row: it is on no classpath
and in no scope. It *is* an input to `native-image`, so it is pinned like one. See
[Native images](native.md#the-graalvm-metadata-repository).

A `[[plugin]]` row normally pins the plugin jar by `checksum`: a jar that is fetched later and
disagrees with it is refused. A plugin the workspace builds itself — a module whose
coordinate is the plugin's, such as jk's own tree building `cc.jumpkick:jk-guards-junit` for
its guard suites — is pinned by `path` to that module instead, with no digest. Its identity is
its source (the module's manifest is already inside `manifests-sha256`), and it is verified by
being built from the workspace, so the row is the same whichever jar happens to be installed or
staged and `jk lock` on a clean checkout rewrites nothing. Third-party plugins and rule packs
keep their digest.

A first-party plugin that ships inside jk (`cc.jumpkick:jk-minified`, `jk-spring-boot`,
`jk-micronaut`, …) is pinned by `coordinate` and `version` alone while that version is a
pre-release — a `0.x` or any qualifier/snapshot. The bytes published under a pre-release version
change with every rebuild, so a digest would fix one moment of it and fail every committed lock
on the next side-load, while the version already says which jk the project builds with. The
trade is explicit: at a pre-release version the pin trusts the jk install (or the official
repository) to serve that version's jar, exactly as it trusts the jk binary itself; a stable
release is immutable, and its row carries the digest like any other plugin.

## Related

[Dependencies](dependencies.md) · [Platforms](platforms.md) · [Repositories](repositories.md)
