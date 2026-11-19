# Security

How to report a JumpKick vulnerability, and which surfaces are in scope.

JumpKick is **pre-1.0 (alpha)**. Report anyway. There is no published SLA and no bug bounty.

## Report a vulnerability

Use a **private GitHub security advisory**. Do not open a public issue, pull request, or
discussion.

https://github.com/JumpKickOSS/jk/security/advisories/new

Include:

- JumpKick version (`jk --version`)
- OS and how you installed (`install.sh` / `install.ps1` / this checkout)
- Steps to reproduce, expected vs actual, and impact

We will acknowledge the report. Do not disclose the issue publicly until it is fixed or we
agree a date.

## Trust boundaries

These are current facts, not a promise that they will never change.

| Surface | Boundary |
|---------|----------|
| **Client** | Native `jk` or the thin JVM launcher. Runs as you. Owns the terminal. Does not interpret plugin schemas. |
| **Engine** | Resident JVM. Resolve, plan, cache, HTTP/MCP. Same user. Does **not** classload plugin code. |
| **Engine socket** | POSIX: the engine socket is protected by the `0700` state directory; any process running as you can drive the engine. `~/.jk` and `~/.jk/state` are created owner-only and re-tightened on every `jk` run; `jk doctor` reports a mode that stayed loose. Windows: loopback TCP gated by an owner-only token; on NTFS, owner-only is an ACL that allows only you and inherits nothing from the parent, on the token file and on the directories JumpKick creates for its state, whatever `JK_HOME` or `JK_STATE_DIR` would otherwise inherit. Java cannot switch off inheritance on these ACLs, so pushing permissions down from a parent folder later can add inherited entries back. |
| **Plugin workers** | Forked processes for compilers, tests, and first-party plugins. Same user. They start from an environment allow-list, not the engine's environment, so a token exported in the shell that started the engine does not reach a compiler or plugin unless the module's `[env]` asks for it. JumpKick's own credentials reach none of them, nor any program the engine starts, unless the manifest names them ([below](#jumpkicks-own-credentials)). A test JVM also reads a variable of the shell running `jk` when a test looks it up (never in a strict module, `[env] inherit = false`), and its result is keyed on what it read, each value HMAC'd under a secret of your JumpKick home, or for a shared cache under the cache's key seed, never digested plainly ([below](#what-a-shared-cache-learns-of-your-environment)) — [Build](build.md#worker-environment-env), [Test](test.md#environment-variables-a-suite-reads), [Cache](cache.md#results-that-read-your-environment). |
| **`jk run` / `jk tool` / `jkx`** | Runs your app or an installed tool on the host, as you. |
| **`.jk/scripts/` scripts** | Project-local generate/prep. Treated as code you trust. |
| **HTTP / MCP** | Default bind **`127.0.0.1`**. Every `/api/*` and `/mcp` call needs a bearer token, including loopback. On Windows with WSL, port 8910 takes one front-door token (`~/.jk/state/engine/front-door.token`, the host engine's own token) whichever engine holds it; the front door forwards to another engine with that engine's own token, never its own, and `jk engine rotate-token` replaces both. Static dashboard shell stays open (CSP). The engine serves only the dashboard shipped in its jar; a directory is read only if you name one with `web-root` ([Web dashboard](web.md#serve-the-dashboard-from-a-directory)). |
| **Credentials** | `~/.jk/creds` is a separate root from cache, store, and state. Writes are owner-only (POSIX `0600`; on NTFS the same ACL as the engine token). `jk self nuke` does not delete creds. A repository credential follows a redirect only within the repository's own origin: a hop to another host, port or scheme is re-issued without `Authorization`, and https never redirects down to http. A credential keyed by repository name (`JK_REPO_<ID>_*`, `jk repo login`, `settings.xml`) is sent only to the origin the name is bound to by your own config, shell or login — never to a URL a project's `jk.toml` alone declares under that name ([details](repositories.md#credentials)). |
| **Releases** | Remote installers, wrappers, engine fetch, and `jk self update` verify RSA/SHA-256 over the exact `SHA256SUMS` bytes, then the selected artifact hash, before installation. A worker jar or POM the engine downloads from JumpKick's repository, or a mirror of it, must have the sha256 the release's `jk-repo-<version>.sha256` lists, a file `SHA256SUMS` vouches for: a mirror may serve the bytes but cannot change them. `[release] trusted-keys` in `~/.jk/config.toml` adds keys (comma-separated base64) to the built-in one. |
| **Lockfile** | Checksums are law. `jk audit` queries OSV for the locked graph. |
| **Repositories** | Lock-time fetches go over https and are checked against the repository's published `.sha256`/`.sha1`; a plaintext `http://` URL or a missing sidecar is refused unless the repository table says `allow-insecure` / `allow-unverified`, never on `central`. The lock summary counts what was allowed. [Repositories](repositories.md#transport-and-checksum-trust). |
| **Decisions** | A destructive operation outside the project asks a person first when the agent's host can ask. Any surface answers it, the first answer wins, and an accepted decision lets its operation proceed once. A courtesy, not a gate ([details](#decisions)). |
| **Plugins** | No public marketplace. A private jar needs a `sha256`. MCP `publish` is always a dry-run so tokens stay on the CLI. |

`[http] host` / `JK_HTTP_HOST` can bind off loopback (`0.0.0.0`). Token gating still applies.
Do not do that on a shared network unless you intend the token-gated API to be reachable
there.

Details: [Engine](engine.md), [MCP](mcp.md), [Web](web.md), [Install](install.md),
[Cache](cache.md), [Publish](publish.md), [Plugins](plugins.md). Contributor HTTP and
signing: [HTTP](../contributors/http.md), [Releases](../contributors/releases.md).

## JumpKick's own credentials

The credentials JumpKick reads for itself are JumpKick's: no test, compiler, plugin step or other
program it starts gets one from your shell unless the module's `jk.toml` names it.

| Variable | What JumpKick reads it for |
|----------|----------------------------|
| `JK_REPO_<ID>_TOKEN`, `JK_REPO_<ID>_USERNAME`, `JK_REPO_<ID>_PASSWORD`, an S3 repository's `JK_REPO_<ID>_ACCESS_KEY` and `JK_REPO_<ID>_SECRET_KEY`, any other `JK_REPO_<ID>_` name ending in `KEY`, `SECRET`, `PASSPHRASE` or `CREDENTIALS`, and each with `_FILE` | a repository's credential, a shared cache's token (`JK_REPO_<HOST>_TOKEN`), the Central Portal login ([Repositories](repositories.md#credentials)). `JK_REPO_<ID>_HOST` and other names, such as a region, are not credentials and follow the ordinary rules |
| `JK_GITHUB_TOKEN`, `JK_GITLAB_TOKEN`, `JK_GITEA_TOKEN`, `JK_BITBUCKET_TOKEN` | a forge's token for its package registry and git remotes |
| `JK_GPG_PASSPHRASE` | `jk publish --sign` |
| `JK_CACHE_KEY_SEED` | the shared cache's key seed ([below](#what-a-shared-cache-learns-of-your-environment)) |

A test that looks one up reads it unset, and a program the test starts doesn't get it.
`[env] inherit = true` and `[env] scope = ["*"]` don't hand it over either. A variable a test
looked up and found withheld keys nothing, so the result is shared as if the test had never looked.
Names other tools read too, such as `GITHUB_TOKEN`, `GH_TOKEN`, the `AWS_*` chain and `jk publish`'s
`PUBLISH_USER` and `PUBLISH_PASSWORD`, belong to your shell, not to JumpKick, and follow the
ordinary rules ([Test](test.md#environment-variables-a-suite-reads)).

**Naming one in `jk.toml` is your consent.** An integration test that publishes to a private
repository needs the token, and a manifest line hands it over:

```toml
[test]
env = ["JK_REPO_CORP_TOKEN"]      # the test JVMs, and the programs they start

[env]
optional = ["JK_REPO_CORP_TOKEN"] # every worker of the module: compilers, plugin steps, tests
```

A value forwarded this way is masked as `***` in output when its name looks like a credential, and
it keys the work like any other forwarded value. For a test result that means an HMAC under a
secret of your JumpKick home, or the cache's key seed for a shared result. For a compile or plugin
step it still means a plain digest (see the last point of the next section). The key seed is the
one exception to the consent rule: a `jk.toml` that names it is refused.

## What a shared cache learns of your environment

A test suite's result is keyed on the values of the variables it read. What may leave your machine
for such a result is one combined key: never a value, never a digest of one variable, never the
names behind a listing. The names of the variables a suite read travel with its read list.

- **No key seed** (none issued, none set): every such value is HMAC'd under a secret of your
  JumpKick home. The key matches on your machine alone and the result is never uploaded. Nobody
  but you can test a guess against it.
- **A key seed** (the one the server issues its authenticated clients, or one your team sets,
  which wins): the result is also keyed with each value HMAC'd under the seed and shared by that
  key, credential-looking names included unless you set `[cache] credential-env = "local"`.
  Whoever holds the seed and every other input of the key, which a checkout of the repository
  gives, can test guesses of a weak value against it: every client the server hands the seed to
  (read-only ones too, unless it withholds the seed from them, as from a fork's CI build), and the
  server when it issued the seed. A team-set seed keeps the server blind.
- The seed lives in your JumpKick home or your shell, never in the repository; JumpKick never hands
  it to a test, a compiler or any other worker (`[env] inherit` included) or to any other program
  its engine starts, even one started from a shell that exported it; refuses a `jk.toml` that names
  it; and never prints it, logs it, puts it in a key, a record, the history or `jk explain`, or
  sends it anywhere. An issued seed is kept per credential, so a credential the server hands no
  seed is never answered with another's. A new seed makes shared results that read the environment miss once.
- Whatever its key, a suite that talks to anything but this host is never replayed.
- Not yet covered: a value a module hands its compilers and plugin steps through `[env]` or a
  plugin table's `env:NAME` (rather than its tests) still enters those steps' keys by plain
  digest, and those keys may be shared. That includes a JumpKick credential forwarded this way.

[Cache § Results that read your environment](cache.md#results-that-read-your-environment) says how
to set a seed.

## Decisions

A [decision](mcp.md#decisions) is a courtesy, not a security gate. Your agent runs as you: it can
edit `jk.toml`, read `~/.jk/state/engine/<key>.http-token`, drive a browser or a terminal, so JumpKick
cannot stop it from approving its own change and does not try. Any surface accepts or declines any
decision: the host's elicitation, the dashboard (the bearer token is enough) and `jk decide`. The
bearer token gates `/api/*` against other users and other machines. Deep links carry the token as
`t=`, which the page stores and scrubs ([Web](web.md)); they are not meant to be shared.

## In scope

- Bypass of release / self-update signature checks
- Tokenless access to `/api/*` or `/mcp`
- Path escape on tool install/uninstall or launcher names
- Credential files readable by other users on POSIX
- The engine socket or its state directory reachable by another local user on POSIX
- Plugin code loading into the engine JVM
- Anything that lets a project you did not intend to trust run code as you *without* you
  invoking `jk run`, `jk tool`, a worker, or a `.jk/scripts/` script

## Out of scope

- Bugs in *your* project (compile errors, failing tests, your app's CVEs)
- Known CVEs in locked dependencies — run `jk audit`
- Local denial of service against an engine you already run
- Binding HTTP off loopback and then sharing the dashboard token
- Issues that require you to already be the user running `jk`
