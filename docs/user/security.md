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
| **Engine socket** | POSIX: the engine socket is protected by the `0700` state directory; any process running as you can drive the engine. `~/.jk` and `~/.jk/state` are created owner-only and re-tightened on every `jk` run; `jk doctor` reports a mode that stayed loose. Windows: loopback TCP gated by an owner-only token. |
| **Plugin workers** | Forked processes for compilers, tests, and first-party plugins. Same user. They start from an environment allow-list, not the engine's environment, so a token exported in the shell that started the engine does not reach a compiler or your test code unless the module's `[env]` asks for it — [Build](build.md#worker-environment-env). |
| **`jk run` / `jk tool` / `jkx`** | Runs your app or an installed tool on the host, as you. |
| **`.jk/scripts/` scripts** | Project-local generate/prep. Treated as code you trust. |
| **HTTP / MCP** | Default bind **`127.0.0.1`**. Every `/api/*` and `/mcp` call needs a bearer token, including loopback. Static dashboard shell stays open (CSP). The default on-disk `web-root` is sandboxed; one you set is served as the dashboard. |
| **Credentials** | `~/.jk/creds` is a separate root from cache, store, and state. POSIX writes are owner-only. `jk self nuke` does not delete creds. A repository credential follows a redirect only within the repository's own origin: a hop to another host, port or scheme is re-issued without `Authorization`, and https never redirects down to http. A credential keyed by repository name (`JK_REPO_<ID>_*`, `jk repo login`, `settings.xml`) is sent only to the origin the name is bound to by your own config, shell or login — never to a URL a project's `jk.toml` alone declares under that name ([details](repositories.md#credentials)). |
| **Releases** | Remote installers, wrappers, engine fetch, and `jk self update` verify RSA/SHA-256 over the exact `SHA256SUMS` bytes, then the selected artifact hash, before installation. |
| **Lockfile** | Checksums are law. `jk audit` queries OSV for the locked graph. |
| **Repositories** | Lock-time fetches go over https and are checked against the repository's published `.sha256`/`.sha1`; a plaintext `http://` URL or a missing sidecar is refused unless the repository table says `allow-insecure` / `allow-unverified`, never on `central`. The lock summary counts what was allowed. [Repositories](repositories.md#transport-and-checksum-trust). |
| **Decisions** | An operation that changes what the project trusts (an update across a minor or a major, a relock that adds a package, a dependency the deny policy refuses) waits for a person. It is accepted only by the agent's host (form elicitation, or the page it opened by URL elicitation, whose link alone carries a one-time key) or by `jk decide` at a terminal. The dashboard can decline it but never accept it: an agent can read the bearer token and drive a browser. Decision ids are 96 random bits, and an accepted decision lets its operation proceed once ([details](#decisions)). |
| **Plugins** | No public marketplace. A private jar needs a `sha256`. MCP `publish` is always a dry-run so tokens stay on the CLI. |

`[http] host` / `JK_HTTP_HOST` can bind off loopback (`0.0.0.0`). Token gating still applies.
Do not do that on a shared network unless you intend the token-gated API to be reachable
there.

Details: [Engine](engine.md), [MCP](mcp.md), [Web](web.md), [Install](install.md),
[Cache](cache.md), [Publish](publish.md), [Plugins](plugins.md). Contributor HTTP and
signing: [HTTP](../contributors/http.md), [Releases](../contributors/releases.md).

## Decisions

The bearer token gates `/api/*` against other users and other machines, not against the agent
working for you: it runs as you, can read `~/.jk/state/engine/<key>.http-token`, and can drive a
browser through a Playwright or Chrome DevTools MCP server. So a dashboard button is not proof that
a person decided. For a trust-changing [decision](mcp.md#decisions) the dashboard shows the question
and the command, `jk decide <id>`, and `POST /api/decisions/<id>` with the token alone is refused
(`403`) for an accept. It is accepted only by:

- the host's form elicitation, which the host renders to the user, not the model;
- the decision page opened from the host's URL elicitation, whose link carries a one-time key the
  engine puts in no tool result, notification or listing, and writes to disk only as a SHA-256 hash
  (decisions are kept in the state directory so a replacement engine takes them over);
- `jk decide` at a terminal.

Any surface may decline. A decision that does not change trust (a destructive clean or uninstall)
may be answered from the dashboard. Deep links carry the token as `t=`, which the page stores and
scrubs ([Web](web.md)); they are not meant to be shared.

## In scope

- Bypass of release / self-update signature checks
- Tokenless access to `/api/*` or `/mcp`
- Accepting a trust-changing decision from the dashboard, or with the bearer token alone
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
