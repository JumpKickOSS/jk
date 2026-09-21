# CI

JumpKick is lockfile-first: a cold runner with a committed `jk-lock.toml` is correct.
Caching only speeds the runner up.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.jk/cache
      ~/.jk/store
      ~/.m2/repository
      **/target/.jk
    key: jk-${{ runner.os }}-${{ hashFiles('jk-lock.toml') }}
    restore-keys: |
      jk-${{ runner.os }}-
- run: jk test
```

| Path | What it holds | Safe to restore? |
|------|----------------|------------------|
| Platform **cache** | Action index + cache CAS | **Yes** |
| Platform **store** | `repos/` + `.jk` memos | **Yes** |
| Maven **local repo** | Third-party jars when `[m2] integration` is on; a digest-matching file is copied into the store instead of downloaded | **Yes** — a warm-fetch win |
| Shared **JDKs** | Managed JDKs | Yes if jobs share the same pin / OS |
| `target/.jk/` | Project-local engine state, including **preflight memos** | **Yes** with the workspace |
| Platform **state** runs | Run history + `jk-results.md` + `details.jsonl` | Optional |
| `jk-lock.toml` | Resolved coords | **Commit** (not a cache) |

**Do not cache** engine sockets / live process state across machines.

Preflight dirty memos use **source content hashes** by default (CI-safe). Opt into faster
path/size/mtime fingerprints with `JK_PREFLIGHT_MEMO_MTIME=1` if you accept that tradeoff.

After restoring cache, a normal `jk build` should hit action cache for unchanged modules.

## Sharing a cache across checkouts and agents

An action key names content and a project-relative output, never the checkout: the sources by
hash, the compile classpath by ABI, compiler argv with paths spelled module-relative, and a task
tag made of the lock's `project-id` plus the output's workspace-relative path. Two checkouts of
one commit therefore compute one key set, whatever their absolute paths, and the second restores
every compile, package and test the first produced. Keys never collide across projects (the
project id is in the tag) or across modules of one workspace (the output path is).

| Root | Share across branches | Share across concurrent agents | Why |
|------|----------------------|-------------------------------|-----|
| `JK_CACHE_DIR` | yes | yes | content-addressed records and blobs; every write is an atomic replace |
| `JK_STORE_DIR` | yes | yes | checksum-verified artifacts; one origin, one tree |
| `JK_STATE_DIR` | yes | yes | run history, metrics and the run-number counter fold under file locks; every run names its checkout |
| `~/.jk/test-homes` | per checkout | per checkout | keyed by the module's real path |
| `target/` | never | never | the checkout's outputs, its freshness stamps and its build slot |

What a checkout changed is what it recomputes: an edited source, a moved dependency ABI, a new
lock digest or a different option is a different key, so a branch that diverges from its
neighbours rebuilds exactly its divergence and shares the rest. An incremental compiler's
analysis stays with the checkout whose absolute paths it holds; a fresh checkout compiles from
the restored classes on its first miss.

A self-hosted runner that serves several agents at once gives each agent its own `JK_HOME` (one
resident engine, one set of sockets) and points every agent at one cache and one store:

```yaml
env:
  JK_HOME: ${{ runner.temp }}/jk-home-${{ github.run_id }}
  JK_CACHE_DIR: /var/lib/jk/cache
  JK_STORE_DIR: /var/lib/jk/store
```

Two engines that meet on one checkout do not interleave: the build holds `target/.jk/build.lock`
for its lifetime and the second engine is refused with the running build's number. The property
is pinned by the engine's `SharedCacheAcrossCheckoutsE2eTest`, which builds one workspace from two
checkouts through one cache.

## Installing jk on a runner

Pin the release, and keep the pin in one file so a new release is one edit:

```yaml
- name: Install jk
  run: |
    set -euo pipefail
    curl -fsSL https://jumpkick.build/install.sh | JK_VERSION="$(tr -d '[:space:]' < .jk/ci-bootstrap-version)" bash
    echo "$HOME/.jk/bin" >> "$GITHUB_PATH"
```

`JK_VERSION` names the release; without it the installer follows the signed `latest` pointer, which
moves under your builds. `JK_HOME` puts the whole installation somewhere other than `~/.jk` —
`${{ github.workspace }}/.jk-home` keeps it inside the checkout and out of any restored cache. The
installer verifies the release signature and checksum before it writes anything, and the `CI`
variable every hosted runner sets keeps it non-interactive. To move to a new release, change the
one line in `.jk/ci-bootstrap-version` and let the pipeline prove it. jk's own repository works
this way, with a guard that refuses a version spelled in a workflow
([self-host](../contributors/self-host.md#the-bootstrap-pin)).

## Typical job

```bash
# PR / push: the cheap share-the-commit bar (unit + integration if it exists).
jk test -j0 -w0
jk test --suite integration -j0 -w0   # skip if the tree has no integration suite
# or a subset:
jk test --modules 'api,worker'
# jk test --affected / --affected-since=HEAD~2  # ranked list only; does not run
```

Do **not** make the PR job `jk test --all`. That is the nightly / release job.
Do **not** clear `[test] exclude-tags` on CI just because `CI` is set — that pulls
`slow` / `network` / `bench` into a gate that should stay cheap and deterministic.
[Test](test.md) · [Why](why.md#test-rungs-the-execute-moat).

Nightly:

```bash
jk test --all -j0 -w0          # every suite; still keep bench out of a red gate
```

Format gate: `jk format --check`. Outdated deps: `jk outdated --output json` is a non-empty
array exactly when a dependency can move (exit code is always 0 on success). Archive `target/jk-results.md` and
`target/jk-profile.json` as artifacts.

Vulnerabilities: `jk audit --severity HIGH --output json | tee target/jk-audit.jsonl` under
`set -o pipefail` — the exit status is the verdict, the JSON lines are the artifact to annotate
from, and `[audit] ignore` with a reason is the only accepted silence ([Publish](publish.md#audit)).

MCP: `jk_config apply_preset=ci`. Selective prepare/run: [Workspaces](workspaces.md#selective-ci-plan).

## Guards

A project with `jk-guards.toml` ([Guards](guards.md)) runs its house rules inside `jk build` /
`jk test`; `jk guard` runs every lane and exits non-zero on any red. Each run leaves `target/jk-guards.sarif`, which
GitHub code scanning reads unchanged:

```yaml
- run: jk guard --no-ansi
- uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: target/jk-guards.sarif
    category: jk-guard
```

`jk guard --output sarif` prints the same document; `--output json` streams one `guard` event per
violation — [Machine output](machine-output.md#guards). Baselined sites arrive as suppressed
results with the baseline's reason, so the code-scanning view shows what the team accepted and why.

## Workflow hygiene

Pin every action to a full commit SHA with its release tag in a trailing comment
(`uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0`) and open every
workflow with `permissions: contents: read`, elevating one scope at a time under the job that
writes; jk's own repository holds its workflows to both with the `workflow-pins-permissions`
guard under `jk guard` and with `scripts/check-workflows.sh` in CI's workflow-lint job, judged
over one fixture, and lets Dependabot move the pins.

## Related

[Cache](cache.md) · [Test](test.md) · [Install](install.md) · [Machine output](machine-output.md)
