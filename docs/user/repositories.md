# Repositories and auth

```bash
jk auth login                  # GitHub / GitLab / Gitea / Bitbucket
jk repo login | logout | search | refresh
```

Credentials: env, OS keychain, or Maven `settings.xml`. Prefer `auth = "env:TOKEN"` over
secrets in TOML. Corporate mirrors, forge package registries, S3/MinIO, and GCS are
supported. After lock, a digest-matching file in the Maven local repository is used in
place; otherwise `JK_STORE_DIR/repos/<name>/`. Set `[m2] integration = false` to keep
third-party jars only under the jk store. `jk install` writes the Maven local repo
when `[m2] install` is on (default); `[m2] install = false` or `JK_M2_INSTALL=false`
keeps those artifacts in `repos/local`. First-party workers always live in `repos/local`.

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

## Related

[Lockfile](lockfile.md) · [Publish](publish.md) · [Dependencies](dependencies.md)
