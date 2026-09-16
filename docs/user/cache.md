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
| **Cache** (`JK_CACHE_DIR`) | Action index + cache CAS (classes, tests, jars, natives, OCI, stamps), and compiled build-logic `.kts` under `kts/` | `jk cache nuke` |
| **Store** (`JK_STORE_DIR`) | Maven-layout `repos/<origin>/` + `.jk` memos (deps, workers) — one tree per repository *origin*, never per name ([Repositories](repositories.md#store-layout-one-tree-per-origin)); `libs.global.toml`, cloned Giter8 catalogs under `templates/`, provisioned build tools under `tools/` (Kotlin, Maven, Gradle, the build-logic `.kts` host). With `[m2] integration` on (default) a fetched jar is also written through to the Maven local repo, and a digest-matching file already there is copied in instead of downloaded; the store is what a build reads. | `jk storage nuke` (does **not** delete `~/.m2`) |
| **Project `target/`** | This checkout’s outputs | `jk clean` |

`jk clean` does **not** by itself force a full recompute: unchanged inputs restore from
the action cache. `--force` also invalidates this project’s action-cache entries.

Everything under a cache root — including its `sha256/` blob pool — is cache tier:
cleaned with `jk cache clean` and wiped by `jk cache nuke`. A nuke removes the root
directory too: `jk cache nuke` and `jk self nuke --cache` are `rm -rf $JK_CACHE_DIR`,
not an emptied skeleton. The next build recreates what it needs.

## Hygiene

```bash
jk cache clean                    # first knob for safe space reclaim
jk storage clean                  # leaked download temps
jk storage clean --workers        # also drop installed plugin workers (see below)
jk cache nuke -y                  # wipe rebuildable action cache
jk storage nuke                   # wipe downloaded artifacts (confirms)
jk self nuke                      # all targets (default); confirms first
jk self nuke --cache --state -y
```

One target per root, no aliases:

| `jk self nuke` flag | Deletes | |
|---------------------|---------|---|
| `--all` | Every target below (default when none named) | |
| `--cache` | `~/.jk/cache` — same as `jk cache nuke` | |
| `--store` | `~/.jk/store`, whole-tree: `repos/`, `tools/`, `templates/`, `completions/`, `android-sdk/` | |
| `--state` | `~/.jk/state` — engine sockets, AOT, builds, scratch tmp | |
| `--config` | `~/.jk/config.toml` and the per-app `~/.jk/config/` tree | |

Always kept, whichever targets you name: `~/.jk/bin` (PATH launchers), `~/.jk/lib` (the live
engine and installed app jars), `~/.jk/creds` (forge and per-repo credentials — `jk repo
logout` removes those) and the managed JDKs. Those are roots of their own, so no target's
delete reaches them.

### Plugin workers

Every plugin with a code layer (`jk image`, the test runner, the compilers, …) runs in a forked
worker whose classpath the engine rebuilds at launch from the worker's Maven POM — the jar's
sibling under `repos/jk-local` (installed from a checkout by `jk install` / `installLocal`),
else `repos/jumpkick` (fetched from jumpkick.build). Nothing about that classpath is persisted
between builds: the engine memoises it for its own lifetime and otherwise walks the POM again,
so the same store yields the same classpath every time.

`jk storage usage` and `jk doctor` print one `repo:` line per repository store — the name a
project used, the origin that filled it, and the `repos/<origin-id>` tree — so a cache holding
another origin's bytes is one line apart from the symptom.

`jk doctor` prints one `worker:` line per installed worker — where its jar came from, how many
dependencies its POM declares, how many entries the rebuilt classpath has — and `jk doctor -v`
lists those entries. When a worker runs on the wrong jar (a Guava flavour, a stale POM from an
older checkout shadowing the published one), `jk storage clean --workers` drops every installed
worker — jar, POM and memo, all versions, from every store repo — and forgets the memoised
classpaths; the next build fetches the published plugin again and rebuilds from its POM. The
closure jars stay: they are shared with project resolution and are re-walked, not re-downloaded.

## Budgets

| Report | Cap | Default |
|--------|-----|---------|
| `jk cache usage` — action cache | `[cache] max-cache-size-gb` / `JK_MAX_CACHE_SIZE_GB` | **4** GiB (8 on `CI=1`) |
| `jk cache usage` — incremental state | `[cache] incremental-max-size-gb` / `JK_INCREMENTAL_MAX_SIZE_GB` | **512** MiB |

```toml
# ~/.jk/config.toml
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
  clean` reclaims only leaked `.put-` download temps and legacy `repos/<name>` trees that
  predate origin keying (nothing reads them; `jk storage usage` flags them). `jk storage
  nuke` is the only way to shrink it on purpose.
- **The Maven local repository** (`~/.m2/repository`): jk does not own it and never deletes
  from it. `jk storage usage` reports its size for information only.
- **Managed JDKs**: `jk jdk uninstall` removes them; nothing else does.

MCP: `jk_disk` (`clean`/`nuke` require `confirm=true`).
