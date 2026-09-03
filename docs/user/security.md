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
| **Plugin workers** | Forked processes for compilers, tests, and first-party plugins. Same user. |
| **`jk run` / `jk tool` / `jkx`** | Runs your app or an installed tool on the host, as you. |
| **`jk/` or `.jk/` scripts** | Project-local generate/prep. Treated as code you trust. |
| **HTTP / MCP** | Default bind **`127.0.0.1`**. Every `/api/*` and `/mcp` call needs a bearer token, including loopback. Static dashboard shell stays open (CSP). On-disk `web-root` is sandboxed. |
| **Credentials** | `~/.jk/creds` is a separate root from cache, store, and state. POSIX writes are owner-only. `jk self nuke` does not delete creds. |
| **Releases** | Remote installers, wrappers, engine fetch, and `jk self update` verify RSA/SHA-256 over the exact `SHA256SUMS` bytes, then the selected artifact hash, before installation. |
| **Lockfile** | Checksums are law. `jk audit` queries OSV for the locked graph. |
| **Plugins** | No public marketplace. A private jar needs a `sha256`. MCP `jk_publish` is always a dry-run so tokens stay on the CLI. |

`[http] host` / `JK_HTTP_HOST` can bind off loopback (`0.0.0.0`). Token gating still applies.
Do not do that on a shared network unless you intend the token-gated API to be reachable
there.

Details: [Engine](engine.md), [MCP](mcp.md), [Web](web.md), [Install](install.md),
[Cache](cache.md), [Publish](publish.md), [Plugins](plugins.md). Contributor HTTP and
signing: [HTTP](../contributors/http.md), [Releases](../contributors/releases.md).

## In scope

- Bypass of release / self-update signature checks
- Tokenless access to `/api/*` or `/mcp`
- Path escape on tool install/uninstall or launcher names
- Credential files readable by other users on POSIX
- Plugin code loading into the engine JVM
- Anything that lets a project you did not intend to trust run code as you *without* you
  invoking `jk run`, `jk tool`, a worker, or a `jk/` / `.jk/` script

## Out of scope

- Bugs in *your* project (compile errors, failing tests, your app's CVEs)
- Known CVEs in locked dependencies — run `jk audit`
- Local denial of service against an engine you already run
- Binding HTTP off loopback and then sharing the dashboard token
- Issues that require you to already be the user running `jk`
