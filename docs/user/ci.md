# CI

JumpKick is lockfile-first: a cold runner with a committed `jk-lock.toml` is correct.
Caching only speeds the runner up.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.cache/jk
      ~/.local/share/jk/store
      **/target/.jk
    key: jk-${{ runner.os }}-${{ hashFiles('jk-lock.toml') }}
    restore-keys: |
      jk-${{ runner.os }}-
- run: jk test
  env:
    JK_AOT_TRAIN: "off"
```

| Path | What it holds | Safe to restore? |
|------|----------------|------------------|
| Platform **cache** | Action index + cache CAS | **Yes** |
| Platform **store** | Artifact CAS + `repos/` | **Yes** — primary warm-build win |
| Shared **JDKs** | Managed JDKs | Yes if jobs share the same pin / OS |
| `target/.jk/` | Project-local engine state, including **preflight memos** | **Yes** with the workspace |
| Platform **state** runs | Run history + `jk-results.md` + `details.jsonl` | Optional |
| `jk-lock.toml` | Resolved coords | **Commit** (not a cache) |

**Do not cache** engine sockets / live process state across machines.

Preflight dirty memos use **source content hashes** by default (CI-safe). Opt into faster
path/size/mtime fingerprints with `JK_PREFLIGHT_MEMO_MTIME=1` if you accept that tradeoff.

After restoring cache, a normal `jk build` should hit action cache for unchanged modules.

## Typical job

```bash
export JK_AOT_TRAIN=off
jk test -j0 -w0
# or a subset:
jk test --modules 'api,worker'
jk test --affected-since=origin/main
```

Format gate: `jk format --check`. Outdated deps: parse `jk outdated --output json`
(exit code is always 0 on success). Archive `target/jk-results.md` and
`target/jk-profile.json` as artifacts.

MCP: `jk_config apply_preset=ci`. Selective prepare/run: [Workspaces](workspaces.md#selective-ci-plan).

## Related

[Cache](cache.md) · [Test](test.md) · [Install](install.md) · [Machine output](machine-output.md)
