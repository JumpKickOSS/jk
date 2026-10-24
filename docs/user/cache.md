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
| **Store** (`JK_STORE_DIR`) | Maven-layout `repos/<origin>/` + `.jk` memos (deps, workers) — one tree per repository *origin*, never per name ([Repositories](repositories.md#store-layout-one-tree-per-origin)); `libs.global.toml`, cloned Giter8 catalogs under `templates/`, provisioned build tools under `tools/` (Kotlin, Maven, Gradle, the build-logic `.kts` host). With `[m2] integration` on (default) Maven Central's and your `settings.xml` repositories' artifacts live in the Maven local repo instead (one copy, shared with Maven), with only their `.jk` memos here; a digest-matching file already there is used where it is. | `jk storage nuke` (does **not** delete `~/.m2`) |
| **Project `target/`** | This checkout’s outputs | `jk clean` |

`jk clean` does **not** force a full recompute: unchanged inputs restore from the action
cache, which every checkout of the project shares and `jk clean` (with or without `-F`) leaves
alone. To run every step anyway, `jk build --force` (or `--redo`) skips reading the cache and
overwrites the records it produces, so no other checkout loses anything. Removing records is
`jk cache clean` / `jk cache nuke`.

A record is written only after every output it names is in the cache, and a step that names an
output it did not write is not recorded at all, so a build that runs out of disk part-way leaves
no record behind. A restore is a hit only when it lays down every output the record names, each
checked against its digest; a blob that is missing or wrong, or an output the disk refuses, makes
it a miss — what it had written is removed and the step runs for real.

`target/` is jk's. A successful build records, beside the module's outputs, the inputs they
came from and the digest of every class file and jar it left there. The next build checks them
(one stat per untouched file) and puts right a class replaced, a class added that no compile
produced, or a jar from another build, from the action cache, before anything is packaged or
tested. An IDE that compiles on its own should write somewhere else (IntelliJ's own `out/`
directory, not `target/classes`), or the next `jk build` takes its classes out again.

One rule decides where a byte lives. **Content** (an action key and its outputs, a dependency's
ABI, a fetched jar) is shared by every checkout and every project: it is a function of its
inputs, so a second checkout of the same commit hits the first's records. **Checkout** state
(`target/`, freshness stamps, incremental compiler analyses, test sandboxes) is keyed by the
checkout's real path and never shared. **Project** state (run history, metrics, the run-number
sequence) is shared by every checkout of one project through the lock's `project-id`, and every
row names the checkout it came from. [CI](ci.md#sharing-a-cache-across-checkouts-and-agents)
lists which roots a runner may therefore share.

Everything under a cache root — including its `sha256/` blob pool — is cache tier:
cleaned with `jk cache clean` and wiped by `jk cache nuke`. A nuke removes the root
directory too: `jk cache nuke` and `jk self nuke -c` are `rm -rf $JK_CACHE_DIR`,
not an emptied skeleton. The next build recreates what it needs.

## Verifying the cache (`--verify-cache`)

`jk build --verify-cache` (or `JK_VERIFY_CACHE=1`) checks the cache instead of trusting it. Every
step runs for real, as under `--redo`, and two claims are held to what it produces:

- **Each record.** A step's fresh outputs must be the ones the record under its key already holds.
  A difference fails that step, naming the file and both digests: the record is poisoned, or the
  step is not reproducible.
- **The preflight.** Every module a plain build would have skipped must come out of the fresh run
  with the outputs it already had. A difference fails the build, naming the module and the file:
  the plain build would have shipped a stale artifact.

`--verify-sample N` checks N of the skippable modules, chosen at random, and leaves the rest as a
plain build would, so a nightly run can bound its cost. A verifying build leaves the cache as a
`--redo` does: it writes nothing it did not just produce.

## Hygiene

```bash
jk cache clean                    # first knob for safe space reclaim
jk storage clean                  # leaked download temps
jk storage clean -w        # also drop installed plugin workers (see below)
jk cache nuke -y                  # wipe rebuildable action cache
jk storage nuke                   # wipe downloaded artifacts (confirms)
jk self nuke                      # all targets (default); confirms first
jk self nuke -c -s -y
```

One target per root, no aliases:

| `jk self nuke` flag | Deletes | |
|---------------------|---------|---|
| `--all` | Every target below (default when none named) | |
| `--cache` | `~/.jk/cache` — same as `jk cache nuke` | |
| `--store` | `~/.jk/store`, whole-tree: `repos/`, `tools/`, `templates/`, `completions/`, `android-sdk/` | |
| `--state` | `~/.jk/state` — engine sockets, builds, scratch tmp | |
| `--config` | `~/.jk/config.toml` and the per-app `~/.jk/config/` tree | |

Always kept, whichever targets you name: `~/.jk/bin` (PATH launchers), `~/.jk/lib` (the live
engine and installed app jars), `~/.jk/creds` (forge and per-repo credentials — `jk repo
logout` removes those) and the managed JDKs. Those are roots of their own, so no target's
delete reaches them.

### Plugin workers

Every plugin with a code layer (`jk image`, the test runner, the compilers, …) runs in a forked
worker whose classpath the engine rebuilds at launch from the worker's Maven POM — the jar's
sibling under `repos/jk-local` (installed from a checkout by `jk install`),
else `repos/jumpkick` (fetched from jumpkick.build). Nothing about that classpath is persisted
between builds: the engine memoises it for its own lifetime and otherwise walks the POM again,
so the same store yields the same classpath every time.

`jk storage usage` and `jk doctor` print one `repo:` line per repository store — the name a
project used, the origin that filled it, and the `repos/<origin-id>` tree — so a cache holding
another origin's bytes is one line apart from the symptom.

`jk doctor` prints one `worker:` line per installed worker — where its jar came from, how many
dependencies its POM declares, how many entries the rebuilt classpath has — and `jk doctor -v`
lists those entries. When a worker runs on the wrong jar (a Guava flavour, a stale POM from an
older checkout shadowing the published one), `jk storage clean -w` drops every installed
worker — jar, POM and memo, all versions, from every store repo — and forgets the memoised
classpaths; the next build fetches the published plugin again and rebuilds from its POM. The
closure jars stay: they are shared with project resolution and are re-walked, not re-downloaded.

`jk doctor`'s `m2:` line hashes the Maven local repository's copy of every POM and jar the
current project's `jk-lock.toml` names and compares it with the `.sha1` Maven wrote beside it. A
mismatch is a warning naming the file: something rewrote it after Maven verified it, and every
build that reads it fails until it is deleted and fetched again. jk never replaces or removes a file in `~/.m2` that it did not download: a slot that holds
other bytes than the lock pins fails the fetch and names the file, and a file jk fetched that
has since been damaged is replaced by temp and rename.

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
generations of action keys (**1** for OCI) **per checkout**: two worktrees of one project on
different branches each keep their own, so neither evicts the other's image on an alternate
build. There is no separate Class-C size share and no Class-C TTL — a replaced generation is
simply superseded, which is already the front of the queue.

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

## Beyond the action cache

- **The artifact store** (`JK_STORE_DIR`) is collected by the same idle pass, each entry on a
  countdown from its last use. How lately comes from the entry's own stamp, how often from an
  access ledger (`.access/` beside what it counts: a bounded journal folded into a snapshot):
  an entry used on 2, 4, 8, 16 days gets 1.5×, 2×, 2.5×, 3× its window (capped lower for natives
  and large jars), and every window shrinks as the volume fills (to a tenth under 5% or 10 GiB
  free).
  - *Blobs* in `sha256/`: pinned by an installed engine's shelf manifest or the client on `PATH`,
    otherwise native binaries three days (the newest two always kept), jars over 50 MB a week,
    other jars three weeks, anything else a month. The blobs together are held to the smaller of
    10 GiB and 8% of the volume, the least life left going first.
  - *Tool installs* under `tools/`: Node and the package managers keep the install used most
    recently for a month and every other version for two days; Maven, Gradle and Kotlin keep
    the newest 90 days and the others 14; an archive unpacked for a step (an IDE distribution)
    lives in `state/tmp/unpacked/` and goes a week after its last use.
  - *Repository trees* jk holds itself (`repos/<id>` for a repository no `settings.xml` names):
    30 days, or a week for a file of 50 MB or more. The first-party `jk-local` and `jumpkick`
    trees are left to installs.

  `jk storage clean` reclaims leaked `.put-` download temps and legacy `repos/<name>` trees;
  `jk storage nuke` removes everything on purpose.
- **Test sandboxes** (`test-homes`, `test-store`): a green `jk test` deletes its module's
  sandbox as it finishes; a red one keeps it for two days to look at. The shared download pool
  drops a unit unused for a week and is held to 2 GiB (8 GiB on CI).
- **Container images** live once. A tarball is recorded by size, mtime and sha256 and a daemon
  image by the ID Docker or Podman reported, in a small record in the action cache — never as a
  second copy. `jk image` skips the build when the tarball is unchanged or the daemon still
  holds the image under that ID, and builds again when either is gone.
- **The Maven local repository** (`~/.m2/repository`): jk never removes from it on its own
  account. `jk cache clean --m2` (MCP: `disk` `action=m2-clean`) removes files unused for
  `--older-than` days (default `[m2] clean-after-days`, 60) that jk's own memo proves jk
  downloaded and nothing has changed since, and keeps what the current project's lockfile pins;
  `--dry-run` lists them. A build server that wants `~/.m2` bounded sets `[m2] auto-clean =
  true` (or `JK_M2_AUTO_CLEAN=true`) and the engine does the same at its idle boundary.
- **Managed JDKs**: `jk jdk uninstall` removes them; nothing else does.

MCP: `disk` (`clean`/`nuke` require `confirm=true`).
