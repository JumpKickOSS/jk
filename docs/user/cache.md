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
cleaned with `jk cache clean` and wiped by `jk cache nuke`.

## Hygiene

```bash
jk cache clean                    # first knob for safe space reclaim
jk storage clean                  # leaked download temps / old run logs
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

```toml
# ~/.config/jk/config.toml
[cache]
max-cache-size-gb = 4
```

`0` or negative means **unset** (use the default). On volumes with **< 10 GiB** total
capacity, the unset default becomes **40% of free space** — half of an 80% margin, with the
other half left for the artifact store, which has no budget and is never pruned. Explicit
sizes are never disk-clamped.

The budget covers the **action cache**: the action index (`actions/`) plus the cache CAS
(`sha256/`). `jk cache clean` — and the engine’s idle-boundary hygiene — delete whole
action-cache entries **oldest file-modification-time first** until the total fits. Deleting
an entry removes its action key, its task pointer, and every blob no surviving key still
references.

That timestamp is when the entry was last **written**, not when it was last used — a cache
hit reads the entry without rewriting it. So a module you rarely change can be evicted
before one you rebuild every day. The next build re-runs that action and re-stores it with
a fresh timestamp, so the cost is one rebuild and it does not repeat for the same entry.

Entries modified in the last hour are never evicted, so a cache under constant write
pressure can sit above its budget until the machine goes quiet.

Class-C outputs (native images, OCI tarballs, fat/minified jars) keep at most **2**
generations of action keys (**1** for OCI); a replaced generation becomes an eviction
candidate immediately. There is no separate Class-C size share and no Class-C TTL — one
budget, one order.

Under budget there is nothing to evict, so `jk cache clean` reclaims only leaked temps,
expired format stamps, stale timings and unreferenced blobs — it can legitimately report
“Nothing to clean up.” on a cache that used to free gigabytes.

## What is never pruned

- **The artifact store** (`JK_STORE_DIR`): Maven-layout `repos/`, worker jars and promoted
  blobs grow without limit. No budget, no eviction, no reachability sweep. `jk storage
  clean` reclaims only leaked `.put-` download temps and expired run logs. `jk storage
  nuke` is the only way to shrink it on purpose.
- **The Maven local repository** (`~/.m2/repository`): jk does not own it and never deletes
  from it. `jk storage usage` reports its size for information only.
- **Managed JDKs**: `jk jdk uninstall` removes them; nothing else does.

MCP: `jk_disk` (`clean`/`nuke` require `confirm=true`).
