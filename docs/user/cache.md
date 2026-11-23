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
| **Cache** (`JK_CACHE_DIR`) | Action index + cache CAS (classes, tests, jars, natives, OCI, stamps, the [code graph](graph.md)'s fragments), and compiled build-logic scripts under `kts/` and `groovy-scripts/` | `jk cache nuke` |
| **Store** (`JK_STORE_DIR`) | Maven-layout `repos/<origin>/` + `.jk` memos (deps, workers) — one tree per repository *origin*, never per name ([Repositories](repositories.md#store-layout-one-tree-per-origin)); `libs.global.toml`, cloned Giter8 catalogs under `templates/`, provisioned build tools under `tools/` (Kotlin, Maven, Gradle, the build-logic `.kts` host). A digest-matching file already in the Maven local repo is used where it is for Maven Central and your `settings.xml` repositories, with only its `.jk` memo here; under `[m2] integration = "share"` their downloads go to the Maven local repo too ([Repositories](repositories.md#sharing-with-maven)). | `jk storage nuke` (does **not** delete `~/.m2`) |
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

`target/` is JumpKick's. A successful build records, beside the module's outputs, the inputs they
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

Across machines the same keys hold wherever the outputs are the same ([CI](ci.md#sharing-a-cache-across-checkouts-and-agents)),
with one limit: a checkout without a committed `jk-lock.toml` takes its project id from its git
remote, or outside git from its absolute path, so two machines share its keys only when it has a
lock. A record that leaves the machine keeps its outputs and its module-relative inputs, and the
inputs under JumpKick's own store, cache, state, JDKs or home and the Maven local repository,
which a record names relative to that root (`store:repos/central/…`, `m2:org/…`,
`state:tmp/unpacked/…`), so another machine can tell which dependency moved; an input named by an
absolute path anywhere else (a jar declared by absolute path, a JDK under `/usr/lib/jvm`, a file in
your home directory) and what the step said as it ran,
which names sources by absolute path, stay local. Of the environment, only the names of the
variables a key read leave the machine (`env:DB_PIN=-`, a suite's `x:test-env:CI` valued `-`): no
value, and no digest of one, since a digest of a short secret can be guessed; the key itself, hashed
here from the values, is unchanged. A suite's record names even a `[test] env` literal by its digest,
and a forwarded variable the shell lacks as `unset`. Every record also says when it was made, by
which JumpKick version, on which host (`linux-x86_64`, `macos-aarch64`, ...), how many bytes each
output holds, and how long the step that made it worked. The host is a statement about the record,
not part of its key: class files and jars are the same wherever they are made.

Compile and test keys name the module's Java level (`java:21`), not the JDK that ran, so hosts on
different JDKs compute the same keys; each record names the JDK that made it (`TOOLCHAIN
toolchain:jdk:eclipse-adoptium/25.0.5+11-LTS/…`: vendor, version with build number and the content
of its compiler, never its OS, architecture or install path). One key can hold a record per JDK, and
a reader takes the closest one its `[cache] jdk-match` accepts: by default any vendor and patch of
the major it builds on ([JDK](jdk.md#which-cached-results-a-build-takes)). A hit made on another JDK
says which; when the records here were all made on another major, the build says so once with the two
ways out, and `jk explain` gives that as the reason the step will run. A step's local stamp, which
decides whether its own outputs on disk are current, still names the exact JDK. AOT caches, jlink
images and native binaries keep the exact build in their keys, and a jlink image and a native binary
are for one platform: their keys and records also name it (`TARGET linux-x86_64-gnu`). A guard
lane's verdict names what the lane read (facts, text, manifests, rules, baseline) and, for a module
lane, the Java major of the JDK the engine runs on, so a verdict made on macOS with JDK 25.0.2 serves
Linux on 25.0.5 under the default `jdk-match`; only the verdict of a module's `src/guard` suite, code
that may skip itself on one operating system, also names the OS. A remote
cache keeps one record per key, JDK and target, written once. A test JVM runs in a fixed locale and time zone (`en-US` and `UTC`
unless the module's `[test] locale` or `[test] timezone` says otherwise,
[Test](test.md#the-test-jvms-locale-and-time-zone-test-locale-test-timezone)), so the shell's
`LANG`, `LC_*` and `TZ` key no suite; only `inherit` keys them. A hit on a machine with no history of the step (a fresh checkout, a record
another machine made) counts what it saved from that time, so `jk history show`'s saved figure and
its coverage include it.

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

## Why did the second build run something? (`JK_KEY_AUDIT`)

The second build of a tree nothing changed should run nothing. When it runs a step again, the
step's key depended on something the first build wrote. `JK_KEY_AUDIT=warn jk build` takes the key
of every step again once the build has succeeded and warns (`key-moved`) for each one that moved,
naming the step and the file or setting that moved with its old and new value;
`JK_KEY_AUDIT=strict` fails the build instead. It costs one more key derivation per step, so it is
off by default. Report what it names: it is a JumpKick defect, or a plugin's.

## Why did a step run? (the run's history)

Every step that consulted the action cache records what it did in the run's history: `local-hit`,
`remote-hit` or `miss`, the action key, the bytes it restored and stored, and for a miss one reason
from a closed set. `jk history show <id>` prints it after the step (`· ran: inputs changed
(src/main/java/A.java)`), and `GET /api/history` (one run: `?id=<run id>`) carries it on each
step as `cache`, for a workspace build's module steps as for a single module's.

| Reason | Meaning |
|--------|---------|
| `no-record` | No earlier record of the step: a first build, or after the cache was cleaned |
| `inputs-changed` | A source, dependency or setting moved; `changed` names up to five of them |
| `toolchain-changed` | Only the compiler, the JDK or JumpKick changed; `changed` says `jk` when another build of JumpKick made the last record |
| `key-changed` | The inputs the last record names are the same; something else the key hashes moved |
| `outputs-gone` | A record under this very key was there, its outputs were not |
| `record-refused` | A record was refused as untrusted |
| `remote-unreachable` | The build's remote cache stopped answering |
| `forced` | `--force`, `--redo` or `--rebuild`, or a whole record was run anyway |

A suite's green or red marker names what its stamp hashed (the test sources, the main classes,
the lock, the runtime classpath, the module's Java level and the suite's other tokens, environment variables
by name only), so a suite that runs again says which of those moved, like any other step.

`jk explain` says the same before the build: a jar, a fat jar or a guard lane whose last result
on this machine another build of JumpKick made forecasts as `repackage · jk changed` or `guards · jk
changed`, since their keys name the JumpKick code that made them. A suite whose last green run another
build of JumpKick keyed says which part of it moved: `run tests · jk changed (version)`, `(key
format)` when the release changed how keys are formed, `(suite code)` when the code that
launches tests changed, or `(test orchestrator)` when the code that picks which suites run or
judges their outcome changed; a
release can move several at once.

The run's byte totals keep traffic with a remote build cache (`cacheUp`, `cacheDown`) apart from
repository downloads and uploads (`remoteUp`, `remoteDown`).

## Shared cache

A team can share one action cache through a cache server. CI builds a commit and stores its
results there; a developer's machine or another runner building the same commit restores them
instead of running the steps. JumpKick speaks the JumpKick cache protocol, version 0, over HTTPS.

Point JumpKick at the server in your own settings. A project's `jk.toml` cannot name one: it is
committed and read by every checkout, forks included, and the server is where a build sends its
keys and its credential.

```toml
# ~/.jk/config.toml
[cache]
remote = "https://cache.example.com"
remote-write = true          # optional; see below
```

`JK_CACHE_REMOTE=https://cache.example.com` does the same for one shell (`off` turns it off), and
`JK_CACHE_REMOTE_WRITE` overrides `remote-write`. Plain `http` is taken only for a server on this
machine.

The credential is the [repository credential](repositories.md#credentials) for the server's
`host[:port]`: `jk repo login cache.example.com -U https://cache.example.com` on your machine (kept in
the keychain when there is one), `JK_REPO_CACHE_EXAMPLE_COM_TOKEN` in CI. It is sent to that origin
only, never to the storage the server hands a large blob's transfer to, and never printed.

**Reading.** When the local cache misses, the build asks the server, for many steps in one request,
and restores what it holds. Each blob is checked against its digest as it lands, so a damaged or
altered blob is a miss, and what was restored is kept in the local cache for the next build. Every
record is read as untrusted, as a local one is, and judged by your own JDK policy (`[cache]
jdk-match`, a `jdk` table with `required = true`): the server picks the record your policy would,
and JumpKick checks it again. A suite keyed by files it was found to read is found too on a host
that never ran it ([Tests](test.md)). `jk explain` forecasts the hits the build would take.

**Writing.** A build that writes uploads each result it can share (no red suite, no step of a kind
it does not know) behind the build, its blobs before its record, and waits up to two minutes at
its end for the uploads to land. CI writes by default (`CI` set) and a developer's machine reads;
`remote-write` says otherwise. A pull or merge request's CI run never writes, whatever it sets (a
GitHub `pull_request` or `pull_request_target` event, a GitLab merge-request pipeline, a Buildkite or
CircleCI pull request): its code is its author's. The server has the last word: a credential it
lets write nothing sends nothing.

**Failures.** The server never fails a build. One that does not answer within 10 seconds, refuses
the credential or speaks another protocol version is off for the rest of the build after one
warning, and the build goes on locally (`remote-unreachable` above). An upload that fails is that
record's alone: one the server cannot take now (`503 unavailable`, `429`, another 5xx, no answer) is
tried again behind the build, up to four tries half a second, two and eight seconds apart and 64
retries per build; a record it refuses (`invalid`, `too_large`) is skipped; a refusal of this
credential's writes (`forbidden`, `read_only`), or three uploads in a row that fail every try, stop
the build's uploads. Whatever was not stored is said once at the end, `jk: remote cache cache.example.com: 1
record and 3 blobs not stored (1 503 from the service after 4 tries)`, and counted in the run's history
(`cacheRecordsNotStored`, `cacheBlobsNotStored`). `--offline` leaves it alone.

What leaves the machine is what a record that leaves the machine holds (see [What is what](#what-is-what)),
the blobs its outputs name, the action keys a build asks about, its platform (`macos-aarch64`) and the
JDK it uses. A key seed never leaves it.

### Results that read your environment

A test suite's result is keyed on the values of the variables it read
([Tests](test.md#environment-variables-a-suite-reads)), each HMAC'd, never digested plainly, so a
key never shows a value and gives no way to test a guess. On your machine the HMAC's secret is one
of your JumpKick home, so that key matches there alone. Such a result is shared only under a **key
seed** every client of the cache holds: the seed the server hands its authenticated clients, or
one your team sets, which wins. With no cache server, your team's seed also shares it through a
cache directory a CI runner restores ([below](#a-ci-runners-own-cache-with-no-cache-server)).
Without a seed, a result that read your environment stays on the machine that ran it, `[env]
scope` or not. A result whose reads were all unset keys no value and is
shared with no seed.

```toml
# ~/.jk/config.toml
[cache]
key-seed-file = "~/team-cache-seed"   # a file holding your team's seed, at least 16 characters
```

`JK_CACHE_KEY_SEED=<seed>` does the same for one shell (a CI secret, say from `openssl rand -base64
32`); `off` takes no seed, the issued one included. Never put the seed in `jk.toml` or the
repository. JumpKick never hands `JK_CACHE_KEY_SEED` to a test, a compiler or any other worker
(`[env] inherit` included), nor to any other program the engine starts (npm, `git`, the AOT trainer),
even when the shell that started the engine exported it; keys nothing on it; and never prints it,
logs it or sends it anywhere. It is one of [JumpKick's own credentials](security.md#jumpkicks-own-credentials),
with the cache's token (`JK_REPO_<HOST>_TOKEN`), but unlike the others it can't be forwarded: a `jk.toml`
that names it (in `[env]`, `[test] env`, a `${…}` value or a plugin table's `env:`) is refused,
naming the place. The seed is scoped to the cache URL, so point
every machine at the same URL. JumpKick keeps an issued seed in its home, readable by you alone, one
copy per cache and credential (named by a digest under your home's secret, never by the token), and
asks for it again after an hour, so a seed the server rotates reaches every machine within the hour
and a machine that changes to a credential the server hands less asks again at once; rotating makes the shared results
that read the environment miss once, and nothing else. A server that issues none answers 404, and
then only a team's seed shares. A server may also hand its seed only to credentials that write and
answer 404 to one that may only read: a fork's CI build may be given no seed, and then it says
nothing, runs the suites whose results read the environment, and keeps those results to itself. A seed that cannot be had (the server refuses the credential or
does not answer) leaves the build going, says once `remote cache cache.example.com did not hand
out its key seed (…)` (`remote-cache-key-seed`), and keeps such results on this machine for that
build.

**Every other step that reads your environment is keyed the same way.** A compile, KSP or jlink
step of a module whose `[env]` forwards a name from your shell, a plugin step or
packager whose plugin table names `env:NAME` (a signing password, a keystore path), a packager's
signing secrets, any step of a module with `[env] inherit = true`, and a node build (`NODE_ENV`,
`CI` and the frameworks' public prefixes such as `VITE_` and `NEXT_PUBLIC_`) take each such value
HMAC'd under your home's secret here and under the seed when shared, never by plain digest, and
the run's memo under `target/` holds the same. A value the manifest writes itself (`vars = [{ TZ =
"UTC" }]`, a profile's or a suite's `env`) is the checkout's, not your shell's, and keys by its
digest. So, without a seed, these results stay on the machine that made them, as a
suite's do. Most compiles read no environment and still share with no seed. The steps you are
likely to notice are release builds whose signing step names `env:`, and node builds on CI runners,
which all set `CI`: they share between machines only once every machine sets the same team seed.

**Sharing scoped results through a cache that issues no seed.** A value a suite reads under
`[env] scope` (or a declared `[env]` / `[test] env` name forwarded from your shell) is keyed like
any other value, so it shares only under a seed: set the same one on every machine that writes or
reads:

```bash
export JK_CACHE_KEY_SEED="$TEAM_CACHE_SEED"   # CI; on a laptop, [cache] key-seed-file
```

Credentials share too. Under a seed, a value whose name reads as a credential (`TOKEN`, `SECRET`,
`KEY`, `PASSWORD`, `PASSWD`, `PASSPHRASE`, `CREDENTIAL`) keys the shared form like any other, so a
CI runner whose environment carries a token still shares its results. To keep those results on the
machine instead:

```toml
# ~/.jk/config.toml, or JK_CACHE_CREDENTIAL_ENV=local for one shell
[cache]
credential-env = "local"
```

What leaves the machine for such a result is one combined key, never a value, never a digest of
one variable, never the names behind a listing; the names of the variables it read travel with its
read list. Whoever holds the seed and a checkout of the repository can test guesses of a weak value
against that key: every client the cache hands the seed to (read-only ones too, unless the server
withholds it from them), and the server when it issued the seed. A team seed keeps the server blind. [Security](security.md#what-a-shared-cache-learns-of-your-environment)
says the same.

#### A CI runner's own cache, with no cache server

With your team's seed set (`JK_CACHE_KEY_SEED` or `[cache] key-seed-file`) and no shared cache
(no `JK_CACHE_REMOTE` or `[cache] remote`, or `--offline`), JumpKick keeps a result that read your
environment in your cache directory twice: under its own key, and beside it under the key the seed
gives, in the form it would send a cache server (no value, no digest of one). A runner that
restores `~/.jk/cache` with the CI system's own cache (`actions/cache`, say) and sets the same seed
replays it without running the suite; a runner with no seed, another seed or another value runs it.
That takes:

- the same seed in every run that saves or restores the cache directory, from a CI secret;
- a committed `jk-lock.toml`: without one the project's id comes from the checkout's path, so a
  runner whose checkout lies elsewhere keys every result apart and replays none of them, silently,
  as with a cache server;
- `~/.jk/cache` alone. The names of the variables a suite read (never their values) are kept there
  too, under `projects/`, so a fresh runner keys the suite as the run before it did. Never cache
  `~/.jk/state`: it holds your home's secret and the credentials JumpKick keeps.

Such a replay reads as a `remote-hit` in `jk history`: the result was made on another machine.
Without a cache server the seed is derived under a fixed word, not a URL, so it keys alike on every
runner whatever its paths, home or user, and apart from the keys it gives for any cache server. An
issued seed never keys these copies, and with a cache server named, the server alone shares. `off`,
a seed under 16 characters and `credential-env = "local"` do here what they do with a server, and a
suite that talks to another host is still never replayed.

A restored cache directory is trusted as it comes, every record in it, so restore one only from
runs you trust: on GitHub a pull request's run cannot save into the default branch's cache, but
other CI systems differ. The seed keeps a run without it from making a record that a run with it
would take for its own values; it does not prove a record's contents, and whoever holds the seed
and the archive can test guesses of a short value against its key.

## Hygiene

```bash
jk cache clean                    # first knob for safe space reclaim
jk storage clean                  # leaked download temps, unused JVM startup caches
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
| `--desktop` | The notifications' name and icon: the Start-menu shortcut or desktop entry, and `~/.jk/lib/desktop` — same as `jk self desktop --remove` ([Config](config.md#decisions)) | |

Always kept, whichever targets you name: `~/.jk/bin` (PATH launchers), `~/.jk/lib` (the live
engine and installed app jars; only `--desktop` takes its `desktop/` icons), `~/.jk/creds` (forge and per-repo credentials — `jk repo
logout` removes those), `~/.jk/changes` (the change journal, which records the nuke before it
starts) and the managed JDKs. Those are roots of their own, so no target's delete reaches them.

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
build that reads it fails until it is deleted and fetched again. JumpKick never replaces or removes a file in `~/.m2` that it did not download: a slot that holds
other bytes than the lock pins is passed over and the artifact is downloaded into the store, or,
under `[m2] integration = "share"`, fails the fetch and names the file; a file JumpKick fetched
into `~/.m2` that has since been damaged is replaced by temp and rename.

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
other half left for the artifact store, collected by its own windows (below). Explicit
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

Within a tier, **oldest last use** goes first, and nothing else ranks. A key the task pointer
no longer names is not dead: a revert, a branch switch or a sibling worktree names it again,
and the pointer only says which checkout stored last.

Last *use*, not last write: a cache hit marks the key record used. Restoring a build's outputs
and a graph question reading a stored [graph](graph.md) fragment both count. The mark is
refreshed at most **once per half grace** (every 30 minutes, half the hour below), so a hit
costs a stat and at most one hit in that half hour costs a write, and a record in use always
looks younger than the grace that protects it from the prune. A module you rebuild hourly therefore leaves dead keys that sort old,
while a module you never touch but always hit sorts young and survives — the reverse of
ranking by store time.

Graph fragments share the action tier and its order with build outputs; they are not taken
first. They are small, so evicting them would free little, and each one lost costs a
re-extraction the next graph question pays for.

Blob timestamps are deliberately never consulted. The CAS is write-once, so a blob’s mtime
is when those bytes were *first* seen; an old blob under a young key means “still current”,
and ranking on it would evict exactly the wrong entries.

Deleting an entry removes its action key, its task pointer, and every blob no surviving key
still references. Entries and blobs touched in the last hour (the grace) are never evicted, so a
cache under constant write pressure can sit above its budget until the machine goes quiet.

Another engine sharing the cache root holds none of this engine's locks, so its prune can still
take a blob a restore is about to read or is reading. That restore is a cache miss: the step runs
again, and none of the record's outputs is left half laid. A blob unlinked while the copy holds
it open is read to the end; a copy that fails or comes up short fails its digest check.

### Heavy outputs

A heavy output is what a native-image, image, fat-jar or minified-jar step stores, whatever its
size, and any other cached blob of **32 MiB** or more (a jlink image, a large node bundle). They are expensive to make and to hold alike, and rarely asked for again after a day,
so they get a tier of their own. A heavy output stays cached **12 hours after its last use** (a
restore renews it), then goes whatever room is left; together they hold at most **2 GiB**, or 4%
of a small volume, least recently used going first.

```toml
# ~/.jk/config.toml
[cache]
heavy-ttl = "12h"   # 0 keeps heavy outputs out of the cache; never more than 24h
```

`JK_HEAVY_TTL` overrides the file. On CI (`CI` set) the default is `0`: a short-lived runner gains
nothing from them, and a long-lived one is where a full disk does the most harm. A project can keep
its own heavy outputs out of the cache with `[build] cache-heavy-outputs = false` in its
`jk.toml`; it cannot lengthen the window, which is the machine's. Either setting means a heavy
step runs on every build that schedules its module, and `jk explain` names it beside the step:
`relink · not cached: [cache] heavy-ttl = 0`. The build says it once, not once per step:
`heavy outputs not cached: [cache] heavy-ttl = 0`.

Class-C outputs (native images, OCI tarballs, fat/minified jars) keep at most **1**
generation of action keys **per checkout**: two worktrees of one project on
different branches each keep their own, so neither evicts the other's image on an alternate
build. There is no separate Class-C size share and no Class-C TTL — a generation trimmed off
every checkout's list goes at once, and the rest take their turn by last use.

### Cadence

`[cache] auto-prune` and `prune-interval-days` (default **7**) drive the engine’s
idle-boundary pass, and a low-priority background collector asks every hour whether a pass is
due, running it when no build is live (a build that arrives makes it yield at its next safe
point). The interval tightens to **hourly** when the previous prune finished with the action
tier still at 90% or more of its budget, so a tier over its budget is back under it within the
hour. Pressure tightens the cadence rather than bypassing it — bypassing would re-fire on every
idle boundary, since a prune that ends under pressure ends under pressure however often it
runs.

### Hit rates

`jk cache usage --stats` (and the Admin page's action-cache panel) says how often each tier paid
for itself: **hits** where the cache saved work (a restore, a replayed green test marker),
**stores** where it did not (every store follows work it could not save), the hit rate
`hits / (hits + stores)`, and the bytes each moved, for the action, heavy and memo tiers. Lookups
are not counted as misses, since `jk explain` and the forecast ask without running anything. The
totals live in `cache-stats.toml` in the state directory and survive engine restarts.

### The disk guard

JumpKick does not fill a disk. A volume is **critical** when less than **2 GiB** is free, or
less than **10%** of it and less than **10 GiB**: 20 GiB free on a 250 GiB laptop disk is room,
not an emergency. On a critical volume no heavy output (32 MiB or more: a native image, a fat jar,
a jlink image, a large node bundle) goes into the cache: it stays where it was built, the build
warns once that the disk is nearly full, and the step runs again on the next build. `jk explain`
gives the disk as the reason a native image, fat jar or jlink image will run. A high-priority thread in the engine looks
at the cache, store and state volumes every 15 seconds, and at once when a write was refused; on
a critical volume it collects the artifact store at emergency windows on the spot (safe beside a
build, since nothing younger than an hour is touched) and prunes the cache now, or at the build
boundary when a build is live. If the state volume is still critical after that, it deletes the JVM startup caches the
engine and its running workers do not map, least recently used first, until the volume is back
over the line or none
are left ([below](#jvm-startup-caches)), and records no new one until it is. It logs when the
volume goes critical, what the reclaim removed, and when it recovers.

Under budget and inside every window there is nothing to evict, so `jk cache clean`
reclaims only leaked temps, unreferenced blobs, whatever the small derived tiers have aged
out of their own windows and caps, and anything under the cache root that JumpKick no longer
writes — it can legitimately report “Nothing to clean up.” on a large cache.

## Beyond the action cache

- **The artifact store** (`JK_STORE_DIR`) is collected by the same idle pass, each entry on a
  countdown from its last use. How lately comes from the entry's own stamp, how often from an
  access ledger (`.access/` beside what it counts: a bounded journal folded into a snapshot):
  an entry used on 2, 4, 8, 16 days gets 1.5×, 2×, 2.5×, 3× its window (capped lower for natives
  and large jars), and every window shrinks as the volume fills (to a tenth under 5% or 10 GiB
  free).
  - *Blobs* in `sha256/`: an install (`install.sh`, `jk install`, `jk self update`) shelves the
    native client, the engine jar and the first-party worker jars here. The store keeps the
    current install and the one before it, for a rollback: a blob is pinned by the current or
    previous shelf manifest (`jk-shelf.toml`, `jk-shelf.previous.toml`), the engine pointer's
    `engine-sha256` and `previous-engine-sha256`, or the client on `PATH`. Every install runs this
    pass as it lands, so the install two back goes at once rather than at the next idle pass.
    Unpinned, an installed jar (an engine jar, or a worker jar whose classes are all
    first-party) goes once it is an hour old; a native binary 12 hours after its last use, the
    newest one kept; jars over 50 MB a week; other jars three weeks; anything else a month. The
    blobs together are held to the smaller of 10 GiB and 8% of the volume, the least life left
    going first.
  - *Tool installs* under `tools/`: Node and the package managers keep the install used most
    recently for a month and every other version for two days; Maven, Gradle, Kotlin and the
    compiler closures JumpKick keeps beside them (Scala, KSP, the Kotlin build tools, Groovy, Dokka, the
    script host) keep the newest 90 days and the others 14; an archive unpacked for a step (an IDE distribution)
    lives in `state/tmp/unpacked/` and goes a week after its last use. Closure directories under
    `tools/plugin-tools/` — a plugin tool's runtime closure, or the repository of a closure a
    plugin resolved for the lock ([Lockfile](lockfile.md#closures-a-plugin-resolves)) — are links
    over the store: each goes a month after its own last use and is built again from the store
    when next needed; a staging directory a killed run left there goes after a day.
  - *Repository trees* JumpKick holds itself (`repos/<id>` for a repository no `settings.xml` names):
    30 days, or a week for a file of 50 MB or more. The first-party `jk-local` and `jumpkick`
    trees are left to installs.

  `jk storage clean` reclaims leaked `.put-` download temps (an hour old or more: a younger one
  may be a download still writing) and legacy `repos/<name>` trees;
  `jk storage nuke` removes everything on purpose.
- **Test sandboxes** (`test-homes`, `test-store`): a green `jk test` deletes its module's
  sandbox as it finishes; a red one keeps it for two days to look at. The shared download pool
  drops a unit unused for a week and is held to 4 GiB, about twice what JumpKick's own heaviest test
  tiers fetch (8 GiB on CI).
- **Container images** live once. A tarball is recorded by size, mtime and sha256 and a daemon
  image by the ID Docker or Podman reported, in a small record in the action cache — never as a
  second copy. `jk image` skips the build when the tarball is unchanged or the daemon still
  holds the image under that ID, and builds again when either is gone. The base-image layers
  `jk image` pulls live in the cache root's `jib-layers/` (not Jib's own default,
  `~/.cache/google-cloud-tools-java`, which nothing bounds and other Jib tools may still use): they
  go on the heavy window from when they were pulled, and the tree resets past 2 GiB. The JRE an AOT
  image build extracts from its base image (`base-jre/`, 50–200 MB each, two at most) goes on the
  heavy window from its last use. An image's kept layers (`image-layers/`: a copy of the release jars
  or of a Quarkus app's `lib/` and their compressed layer, or the compressed layer alone of a node
  image's `node_modules`; one per image, four at most) go on the same window from the last image that shipped them.
- **The Maven local repository** (`~/.m2/repository`): JumpKick downloads into it only under
  `[m2] integration = "share"`, and never removes from it on its own
  account. `jk cache clean --m2` (MCP: `disk` `action=m2-clean`) removes files unused for
  `--older-than` days (default `[m2] clean-after-days`, 60) that JumpKick's own memo proves it
  downloaded and nothing has changed since, and keeps what the current project's lockfile pins;
  `--dry-run` lists them. A build server that wants `~/.m2` bounded sets `[m2] auto-clean =
  true` (or `JK_M2_AUTO_CLEAN=true`) and the engine does the same at its idle boundary.
- **Managed JDKs**: `jk jdk uninstall` removes them; nothing else does.
- <a id="jvm-startup-caches"></a>**JVM startup caches** (`~/.jk/state/aot/`, `~/.jk/state/engine-aot/`):
  the JEP 514 caches the compiler, formatter, graph and script workers map (20–50 MB each, one per
  tool, JDK and worker classpath or `-J` flags) and the engine's own (about 75 MB). Each file's
  name carries the JumpKick version that recorded it. They go in two ways:
  - **A new JumpKick version**: the first engine of another version deletes every cache an older
    (or newer) one recorded, all of them at once.
  - **The disk guard**: on a critical volume, the ones the engine and its running workers do not
    map, least recently used first ([The disk guard](#the-disk-guard)).

  Nothing else removes them: a released JumpKick keeps a cache for as long as it is installed,
  used or not. A `-SNAPSHOT` JumpKick (one you built from a checkout, or CI's) records a new set
  each time its workers are rebuilt, so there every cache not used for 12 hours goes as well,
  checked when the engine starts, when a cache is recorded and on the engine's 12-hour
  maintenance cycle. `jk storage usage` shows them on their own row and `jk storage clean` deletes
  every one the engine's running workers and the engine itself do not map; the next build that
  needs one records it again in the background (about 4 seconds). None of these deletes a cache
  your engine or its running workers map, on any system. A second engine of the same home is
  spared by the 12-hour rule, since starting a worker marks its cache as used, but `jk storage
  clean` and the disk guard can delete a cache it maps (on Windows, where one engine cannot see
  another's workers). That costs nothing but time: a running process keeps the cache it mapped,
  and the next worker that needs it records it again. A half-written recording a stopped engine
  left behind goes a few minutes later, in any of these passes.
- **Scratch under `state/tmp/`** is not tied to an install: the archive a step unpacked (`unpacked/`, an IDE
  distribution) goes a week after its last use, whatever JumpKick version is installed, since it
  belongs to the project. Worker GC logs (`worker-gc/`) go when their worker exits, or
  a day later if it was killed; the engine startup cache's recording scratch goes when the next
  recording starts; anything else under `state/tmp/` goes a week after it was written.

MCP: `disk` (`clean`/`nuke` require `confirm=true`).
