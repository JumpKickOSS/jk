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
| **Store** (`JK_STORE_DIR`) | Maven-layout `repos/` + `.jk` memos (deps, workers), `libs.global.toml`, cloned Giter8 catalogs under `templates/`. Maven local repo is the primary jar store when `[m2] integration` is on (default). | `jk storage nuke` (does **not** delete `~/.m2`) |
| **Project `target/`** | This checkout’s outputs | `jk clean` |

`jk clean` does **not** by itself force a full recompute: unchanged inputs restore from
the action cache. `--force` also invalidates this project’s action-cache entries.

Everything under a cache root — including its `sha256/` blob pool — is cache tier:
cleaned with `jk cache clean` and wiped by `jk cache nuke`.

## Hygiene

```bash
jk cache clean                    # first knob for safe space reclaim
jk storage clean                  # leaked download temps
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
| `jk cache usage` — action cache | `[cache] max-cache-size-gb` / `JK_MAX_CACHE_SIZE_GB` | **4** GiB (8 on `CI=1`) |
| `jk cache usage` — incremental state | `[cache] incremental-max-size-gb` / `JK_INCREMENTAL_MAX_SIZE_GB` | **512** MiB |

```toml
# ~/.config/jk/config.toml
[cache]
max-cache-size-gb = 4
incremental-max-size-gb = 0.5
```

`0` or negative means **unset** (use the default). On volumes with **< 10 GiB** total
capacity, the unset default becomes **40% of free space** — half of an 80% margin, with the
other half left for the artifact store, which has no budget and is never pruned. Explicit
sizes are never disk-clamped. The incremental default shrinks with a disk-clamped cache
budget (to an eighth of it), so a small volume does not reserve half a gigabyte for
analysis files.

### Three tiers, three shelf lives

`actions/` holds three kinds of thing that fail differently when they go, so each gets its
own window and its own denominator:

| Tier | What it is | Window | Bounded by |
|------|------------|--------|------------|
| **Action** | key records naming CAS outputs, plus those outputs | 30 days | `max-cache-size-gb` |
| **Memo** | key records naming *no* output — a `run-tests` green stamp, where the record **is** the result | 90 days | a count cap (**20,000**), never bytes |
| **Incremental** | Zinc analysis under `actions/incremental-java`, `actions/incremental-kotlin` | 7 days | `incremental-max-size-gb` |

Each tier gets an **unconditional window pass** — stale entries go whether or not you are
near the budget — and then a budget pass that only runs when the tier is over. Splitting the
denominators is what makes that possible: under one budget for all of `actions/`, a large
workspace’s Zinc state could push the total over the line and the prune would evict every
action key and still be over.

A memo is a few hundred bytes, so evicting one to reclaim space buys a rounding error and
costs a whole test run. That is why the byte budget never takes one — only its window and
its count cap do.

### What goes first

Within a tier, **superseded entries** go first: a key that is neither its task’s current
pointer nor in its generation list can never be hit again, so it is free to take. After
that, **oldest last use**.

Last *use*, not last write: a cache hit re-stamps the key record (coarsened to an hour, so
a build that looks the same entry up several times pays one write). A module you rebuild
hourly therefore leaves dead keys that sort old, while a module you never touch but always
hit sorts young and survives — the reverse of ranking by store time.

Blob timestamps are deliberately never consulted. The CAS is write-once, so a blob’s mtime
is when those bytes were *first* seen; an old blob under a young key means “still current”,
and ranking on it would evict exactly the wrong entries.

Deleting an entry removes its action key, its task pointer, and every blob no surviving key
still references. Entries and blobs touched in the last hour are never evicted, so a cache
under constant write pressure can sit above its budget until the machine goes quiet.

Class-C outputs (native images, OCI tarballs, fat/minified jars) keep at most **2**
generations of action keys (**1** for OCI). There is no separate Class-C size share and no
Class-C TTL — a replaced generation is simply superseded, which is already the front of the
queue.

### Cadence

`[cache] auto-prune` and `prune-interval-days` (default **7**) drive the engine’s
idle-boundary pass. The interval tightens to **daily** when the previous prune finished with
the action tier still at 90% or more of its budget: a cache that full will be over again
long before the week is out. Pressure tightens the cadence rather than bypassing it —
bypassing would re-fire on every idle boundary, since a prune that ends under pressure ends
under pressure however often it runs.

Under budget and inside every window there is nothing to evict, so `jk cache clean`
reclaims only leaked temps, unreferenced blobs, whatever the small derived tiers have aged
out of their own windows and caps, and anything under the cache root that jk no longer
writes — it can legitimately report “Nothing to clean up.” on a large cache.

## What is never pruned

- **The artifact store** (`JK_STORE_DIR`): Maven-layout `repos/`, worker jars and promoted
  blobs grow without limit. No budget, no eviction, no reachability sweep. `jk storage
  clean` reclaims only leaked `.put-` download temps. `jk storage
  nuke` is the only way to shrink it on purpose.
- **The Maven local repository** (`~/.m2/repository`): jk does not own it and never deletes
  from it. `jk storage usage` reports its size for information only.
- **Managed JDKs**: `jk jdk uninstall` removes them; nothing else does.

MCP: `jk_disk` (`clean`/`nuke` require `confirm=true`).
