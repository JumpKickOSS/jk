# Cache and storage

JumpKick splits **rebuildable** action-cache bytes from **downloaded artifacts**.
Correctness does **not** depend on local caches — a cold machine with a valid
`jk-lock.toml` always rebuilds. Caching only speeds downloads and action hits.

Paths: [Install](install.md). CI restore: [CI](ci.md).

```bash
jk cache usage | dir | clean | nuke
jk storage usage | dir | clean | nuke
jk clean              # project target/ only
jk self nuke          # jk-owned product dirs (not PATH, not JDKs)
```

## What is what

| Tier | Holds | Wipe |
|------|--------|------|
| **Cache** (`JK_CACHE_DIR`) | Action index + cache CAS (classes, tests, jars, natives, OCI, stamps) | `jk cache nuke` |
| **Store** (`JK_STORE_DIR`) | Maven-layout `repos/` + `.jk` memos (deps, workers). Maven local repo is the primary jar store when `[m2] integration` is on (default). | `jk storage nuke` (does **not** delete `~/.m2`) |
| **Project `target/`** | This checkout’s outputs | `jk clean` |

`jk clean` does **not** by itself force a full recompute: unchanged inputs restore from
the action cache. `--force` also invalidates this project’s action-cache entries.

Everything under a cache root — including its `sha256/` blob pool — is cache tier:
cleaned with `jk cache clean` (including all Class-C heavy outputs) and wiped by
`jk cache nuke`.

## Hygiene

```bash
jk cache clean                    # first knob for safe space reclaim
jk storage clean                  # orphan deps / old run logs
jk cache nuke -y                  # wipe rebuildable action cache
jk storage nuke                   # wipe downloaded artifacts (confirms)
jk self nuke                      # all targets (default); confirms first
jk self nuke --cache --state -y
```

| `jk self nuke` flag | Deletes | Keeps |
|---------------------|---------|-------|
| `--all` | Every target below (default when none named) | — |
| `--cache` | Same as `jk cache nuke` | Artifact store, PATH, JDKs |
| `--data` | The product data root (`JK_DATA_DIR`, default `~/.local/share/jk`): the artifact store via `jk storage nuke`, plus every other child of that root (`versions/`, `completions/`, …) | Active engine version (`<data>/lib`), forge/repo credentials, PATH, JDKs |
| `--state` | Engine sockets, AOT, builds, scratch tmp | — |
| `--config` | User config | — |

`--store` is still accepted as a hidden alias for `--data`, but it now wipes the whole
data root rather than just the store subtree. Under the `JK_HOME` umbrella the data root is
`$JK_HOME/data`, so `--data` covers `$JK_HOME/data/*` (store included) and leaves the other
four roots to `--cache`, `--state`, and `--config`.

Does **not** delete `jk` / `jkx` on PATH, managed JDKs, or forge/repo credentials
(`jk repo logout` removes those).

## Budgets

| Report | Cap | Default |
|--------|-----|---------|
| `jk cache usage` | `[cache] max-cache-size-gb` / `JK_MAX_CACHE_SIZE_GB` | **4** GiB (8 on `CI=1`) |
| `jk storage usage` | `[cache] max-store-size-gb` / `JK_MAX_STORE_SIZE_GB` | **6** GiB (12 on `CI=1`) |

```toml
# ~/.config/jk/config.toml
[cache]
max-cache-size-gb = 4
max-store-size-gb = 6
```

`0` or negative means **unset** (use the default). On volumes with **< 10 GiB** total
capacity, unset defaults are clamped so cache + store claim at most 80% of free space.
Explicit sizes are never disk-clamped.

The cache tier is rebuildable, so scheduled hygiene size-evicts it to its budget (Class-C
heavy outputs first: native, OCI, fat/minified jars). **`jk cache clean`** drops all
Class-C immediately, plus stale keys and temps, while keeping modular compile/test cache.

Class-C extra policy: **50%** of the cache budget, **3-day** unused TTL, **2 generations**
of native binaries / fat jars, **1 generation** of OCI images.

The store’s 6 GiB default budgets **jk-owned** bytes (`repos/`, workers). It does not
include the Maven local repository. `jk storage usage` reports Maven local size as a
separate, unbudgeted line. `jk storage clean` never evicts reachable store files or
`~/.m2`; it only reclaims garbage (`.put-` temps, expired run logs).

MCP: `jk_disk` (`clean`/`nuke` require `confirm=true`).
