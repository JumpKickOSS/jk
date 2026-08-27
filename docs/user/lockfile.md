# Lockfile

`jk-lock.toml` is **canonical**. Commit it. Day-to-day you should not think about it: any
command that needs a current lock (build, explain, status, tree, sync, export, ide, …)
auto-refreshes when the lock is missing or out of sync with manifests.

`jk build` **does not re-resolve**. That is the product.

```bash
jk lock          # resolve → write jk-lock.toml
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
| `jk lock` | Resolve and write the lock. Metadata warm within 24h TTL (local first) |
| `jk sync` | Materialize cache; `--offline-prepare` for offline CI |
| `jk outdated` | Current / Compatible / Latest table (exit 0 always on success) |
| `jk update` | Re-resolve on purpose; revalidates metadata |
| `jk build` | Uses the lock; does not re-resolve |
| `jk tree` / `jk why` | Inspect the graph offline |

Metadata indexes live under the store (`metadata/`, 24h TTL + ETag). Back-to-back `jk lock`
hits disk only; use `jk update` or `-F` when you need Central’s current version lists today.

Automatic refreshes (stale lock on `jk build`) are **conservative** — pinned versions stay
put. Only `jk lock` / `jk update` float to latest.

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
newer JDK is fine, an older one is not. `required-*` is a pin the project asked for, and only an
`=` in `jk.toml` writes one:

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

Locks written before this shape (a bare `vendor` / `version` pair, schema `version = 1`) are
rejected rather than guessed at — re-run `jk lock`.

## Pre-release pins

A lock that records an RC/M/beta is kept on conservative re-locks when it still satisfies
the declared range. A platform BOM pin (including a pre-release line) is enforced on
managed GAs while the platform is active. Unpinned `latest` still prefers the newest
**stable** over a newer pre-release. Deliberate upgrades off a pre-release belong on
`jk update`.

## Lock-time trust

`jk lock` is the trust boundary. For each POM/artifact download JumpKick:

1. Streams bytes, computes SHA-256 locally, and stores a Maven-layout `*.jar` (Maven
   local repo when `[m2] integration` is on and the slot is empty or already equal).
2. Fetches the repository’s published sidecar (`.sha256`, else `.sha1`) when present and
   **fails closed** on mismatch.
3. If no sidecar exists, pins TOFU-style and may report how many artifacts lacked a checksum.
4. Warns once per repository that still uses plaintext `http://`.

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

## Related

[Dependencies](dependencies.md) · [Platforms](platforms.md) · [Repositories](repositories.md)
