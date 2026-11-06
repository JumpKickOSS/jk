# Config, chrome, and environment

Global CLI prefs live in **`[config]`** of `~/.jk/config.toml` and/or project
`jk.toml`. Precedence for most knobs: **flag > env > project > machine**.

```toml
# ~/.jk/config.toml or project jk.toml
[config]
color = "auto"          # auto | always | never
offline = false
quiet = false
verbose = false
no-progress = false
no-ansi = false         # ASCII-only; implies no-progress
force-ansi = false      # emit ANSI even under TERM=dumb / CI
no-osc = false
notify = "auto"         # auto | always | never
build-output = false    # live-plan process-output peek (Ctrl-O)
force = false
# dir = "/path"         # optional default -C
```

| Setting | CLI | Env |
|---------|-----|-----|
| `color` | `--color` | `JK_COLOR`, `NO_COLOR` |
| `no-progress` | `--no-progress` | `JK_NO_PROGRESS` |
| `no-ansi` | `--no-ansi` | `JK_NO_ANSI` |
| `force-ansi` | — | `JK_FORCE_ANSI` |
| `no-osc` | `--no-osc` | `JK_NO_OSC` |
| `notify` | `--notify` / `--no-notify` | `JK_NOTIFY` |
| `build-output` | — | `JK_BUILD_OUTPUT` |
| `quiet` / `verbose` / `offline` / `force` | `-q` / `-v` / `--offline` / `-F` | `JK_QUIET` / `JK_VERBOSE` / `JK_OFFLINE` / `JK_FORCE` |
| agent report | `--agent` | `JK_AGENT=1` ( `JK_AGENT=0` keeps the human report) |

**`--agent`:** commands that report a run (`build`, `test`, `run`, `lock`, …) print the
verdict on stdout and skip progress, colour, and OSC. Shapes are in [MCP](mcp.md#verdict).
The same text is selected when stdout is not a terminal and the process carries a variable a
coding-agent CLI sets on commands it spawns. A pipe or a CI log without that variable keeps
today's output. `-O jsonl` stays the live event stream unless `--agent` or `JK_AGENT=1`
is set.

**`notify`:** `auto` (default) sends an OSC desktop notification when a build’s ETA **or**
elapsed time is ≥ 1 minute; `always`/`true` always; `never`/`false` never.
`--no-progress` and `--no-osc` also suppress notifications.

**`force-ansi`:** ANSI is otherwise suppressed by `TERM=dumb` or a truthy `CI`, whichever the
host sets. `force-ansi` outranks both, so output can be pinned to ANSI where the environment
would strip it. `no-ansi` still wins when both are set — turning ANSI off is the safer
direction to honor.

Under `--no-ansi`, chrome lines start with `jk: ` so they stay distinct from compiler/test
output. Agents should use [machine output](machine-output.md), not scrape prose.

Interactive questions (the `jk new` form, `jk jdk install`, `jk activate`) still work under `--no-ansi` on a
terminal: instead of arrow keys and live highlighting they ask one question per line. Text
questions show their default in brackets and take it on an empty line; choices print as a
numbered list and take a number, an id, or an empty line for the default; multi-picks take
several numbers (`1 3` or `1,3`), `all`, or `none`. Answers can be piped on stdin. Without a
terminal at all nothing is asked and the command's flags apply.

## A config.toml that does not parse

`~/.jk/config.toml` is never read as empty because of a syntax error: that would drop its
mirrors, repositories, proxy and credentials, and send every request around them. Every command
that resolves, fetches, downloads or authenticates fails instead, naming the file, line and
column, and exits 2, the configuration exit code. That covers `[repositories]` (with
`mirror-of`), `[mirrors]`, `[network]`, `[node]`,
`[templates]` and `[image]`. Local preferences (`nerd-font`, `[engine]`, `[cache]`, `[history]`,
`[http]`) still fall back to their defaults, so `jk --version`, `jk help`, `jk engine stop`
and `jk doctor` keep working. `jk doctor` reports the file as a failed `config` check with the
position. The engine reads the file again when it changes, so the command after the fix
succeeds without a restart.

`[plugins]` pins are code that runs, so they are read strictly too: a file that does not parse
fails every command that would load a user plugin (`jk build`, `jk test`, `jk run` and every
other command that reads a `jk.toml`) rather than running the shipped plugin in place of the one
you pinned. Commands that load no user plugin (`jk --version`, `jk help`, `jk doctor`, `jk engine`)
are unaffected, and the engine still starts. A plugin command that jk cannot look up because the
file does not parse names the file, line and column instead of showing the help.

A `path` pin under `[plugins]` follows the file while the engine runs: add, edit or remove one —
or fix a file that did not parse when the engine started — and the next command uses it, with no
`jk engine stop`. The pinned jar is followed too: rebuild it in place and the next command checks
it again. A pin whose jar is missing or whose `sha256` disagrees with the jar fails every command
that reads a `jk.toml`, naming the pin, and the plugin registered before the change stays until the
pin is fixed.

An engine command that reads nothing the file governs (no `jk.toml`, no resolve, no download)
still succeeds, and prints one warning after its result naming the file, line and column, so you
hear of the break before the next command that needs it. The dashboard and MCP see the same
note as a `warn` event.

## Nerd Font glyphs

Detected per launch — `nerd-font` defaults to `"auto"`. Pin only to override:

```bash
jk self setup-terminal -e
jk self setup-terminal                  # write nerd-font = "auto"
jk self setup-terminal -m on        # every PUA glyph
jk self setup-terminal -m off       # no PUA glyphs
jk self setup-terminal -m wedge     # powerline triangles only
jk self setup-terminal -m pill      # half-circle pill caps only
```

`auto` grants both axes on Ghostty, kitty, WezTerm, and Windows Terminal, including inside tmux,
screen and zellij. On iTerm2, VS Code, and Zed it follows the configured font. On Alacritty (at
least the wedge, which it draws itself), Konsole, GNOME Terminal and other VTE terminals, foot, Rio,
Warp, Tabby, and Hyper, an installed Nerd Font is enough, because those terminals fall back to any
installed font for a missing glyph. An unidentified terminal gets the powerline triangles when a Nerd
Font is installed. Nothing over SSH, under `CI`, or on macOS Terminal.app, which draws no powerline
glyph of its own and stores its font where jk cannot read it.

When `auto` is not enough, confirm what you see:

```bash
jk self setup-terminal        # shows the glyphs, asks, remembers the answer for this terminal
jk self setup-terminal -e     # why auto concluded what it did, and the state of that answer
jk self setup-terminal -f     # forget the remembered answer
```

The answer is saved in `~/.jk/terminal-caps.toml` for that terminal (and, over SSH, that client).
jk notices when the terminal's version, font, config file, or the installed fonts change, ignores
the answer, and prints one line telling you to run the command again. Over SSH, run it on the
server to confirm what your client shows, or forward a pin with `SendEnv`/`SetEnv JK_NERD_FONT`.

Env `JK_NERD_FONT` takes all five values; host-wide `NERD_FONT` takes booleans only.

Contributor TUI rules: [TUI](../contributors/tui.md).

## Self-update

Root-level `self-update = "auto" | "ask" | "never"` in `~/.jk/config.toml` (env
`JK_SELF_UPDATE`) decides whether jk installs new releases on its own, offers them at a terminal,
or leaves itself alone. Unset asks at a terminal and updates everywhere else; before 1.0 updates
are compulsory. Details: [Install](install.md#updates).

## Engine

Machine-scoped `[engine]` keys and `JK_ENGINE_*` process env: [Engine](engine.md#configuration).
They are not project-overridable.

## HTTP and MCP

```toml
[http]
# enabled = false          # default is on (loopback, token-gated /api)

[mcp]
# enabled = false          # 404 /mcp only; dashboard stays
# max-event-streams = 16
# max-sessions = 256       # loaded MCP connections; initialize is a 503 only when all are busy
# session-expiry = "24h"   # a session with no activity this long ends and forgets its connection
# tools = "loop"           # tools/list: "loop" (six tools) or "all"; a client can also pass extended=true
```

`JK_HTTP_ENABLED=false`, `JK_MCP_ENABLED=false`, `JK_MCP_MAX_SESSIONS=64`, `JK_MCP_SESSION_EXPIRY=8h`,
`JK_MCP_TOOLS=all`. `session-expiry` covers every session (MCP, BSP, shell, dashboard), so it
applies with `[http] enabled = false` or `[mcp] enabled = false` too. Details: [MCP](mcp.md),
[Web](web.md).

## Network

Corporate networks that only reach the internet through a proxy configure it once:

```toml
[network]
proxy = "http://proxy.corp:3128"          # every request; user:password@ for Basic
https-proxy = "http://proxy.corp:3129"    # https targets only, when they differ
no-proxy = ["nexus.corp", ".internal.corp", "10.0.0.5:8081"]
```

Without a `[network]` table, an active `<proxy>` in Maven's `~/.m2/settings.xml` decides for
**every request** — artifacts, JDK and tool distributions, forge APIs, release checks — for the
protocol it names (`https` for https targets, `http` for http ones, as Maven matches them), its
username and password sent as Basic and its `nonProxyHosts` going direct — see
[Repositories § Maven `settings.xml`](repositories.md#maven-settingsxml-mirrors-proxies-profiles).
A proxy that admits only the artifact host lists the other hosts in `nonProxyHosts`, or you write
a `[network]` table. Failing both, the shell's
`https_proxy` / `HTTPS_PROXY` (https targets),
`http_proxy` / `HTTP_PROXY` (http targets) and `no_proxy` / `NO_PROXY` decide — lower case wins
when both are set, and both `no-proxy` lists apply. A proxy URL is
`http://[user:password@]host[:port]` (a bare `host:port` is http); https targets tunnel through it
with `CONNECT`, and a `user:password@` is sent to the proxy as Basic (only to the proxy — a
redirect to a host that goes direct never carries it). Whichever source wrote it — `[network]`,
a `settings.xml` `<proxy>` (expanded or decrypted) or the shell — the password is masked in jk's
output, `-o jsonl` and the build history, like a repository credential. A `no-proxy` entry is
`*`, a host, a `.suffix` (a bare suffix covers its subdomains too), or `host:port` for one port.
Loopback targets always go direct.

Every download jk makes — Maven Central and your repositories, JDK and tool distributions, the
engine jar, release checks — goes through `Http`, so one `[network]` table or one set of shell
variables covers them all, and so does a settings.xml proxy. The decision is
made per request: `[network]` is read once per `jk` command and re-read when the file changes
(an edit is seen by the next command), and the six proxy variables ride
each request from the shell running `jk`, so exporting new ones in a terminal is enough — the
engine falls back to the values of the shell that spawned it only for a request that carries
none. Every worker a build forks — compilers, plugin workers, test JVMs — is handed the same six
variables, the request's values over the engine's, so a test that downloads goes the same way the
engine does ([Build § Worker environment](build.md#worker-environment-env)). A plugin step whose
own Maven resolver fetches outside the lock (Quarkus's build-time closure) is handed each
repository with the proxy jk chose for its URL, `[network]` and the shell included, so it does not
fall back to reading `settings.xml` alone. The `git` that clones the template catalogs
(`[templates] official` and `[templates.sources]`) and fetches git dependencies (`{ git = … }`),
over http or https, runs with the proxy jk chose for the remote's URL as its `http.proxy`, and
with git's proxy off for a host jk sends direct; with no proxy configured for jk, git's own configuration decides. The value reaches git
through its environment, never its command line. JGit, used when no `git` is installed, takes the
same route and answers a proxy's Basic challenge with the URL's `user:password@`. git needs to be 2.31
or later to be handed a proxy; an older git is refused by name rather than sent direct, for the
template catalogs and git dependencies alike (a git dependency can use the built-in git instead,
`JK_GIT_BACKEND=jgit`). A credential in a
proxy URL is never printed; an unusable value is reported
by the name that set it (`ignoring https_proxy: …`) and the request goes direct. For a proxy that
wants Basic on an https `CONNECT`, jk clears the JDK's `jdk.http.auth.tunneling.disabledSchemes`
in its own processes unless you set that property yourself. `--offline` still refuses every
request before any proxy is consulted.

## Install target

Where `jk install` puts a project's thin jar and POM when neither `--destination` nor the project's own
`[install] destination` names a place:

```toml
# ~/.jk/config.toml
[install]
destination = "jk"      # default "m2": the Maven local repository, ~/.m2/repository
```

`"jk"` is jk's private `repos/jk-local`, for a machine that keeps `~/.m2` clean. The first to
answer: `--destination`, the project's `jk.toml`, `JK_INSTALL_DESTINATION`, this file, then `m2`. An unknown
word is skipped. See [Install](packaging.md#jk-install-project).

## Tool mirrors

The distributions jk provisions for itself come from their public origins unless `[mirrors]` points
a tool elsewhere. `[mirrors]` covers tool downloads only; a Maven repository mirror (Artifactory
or Nexus answering for Central and the rest) is a `[repositories.<id>]` table with `mirror-of` —
see [Repositories § Mirror or source](repositories.md#a-corporate-repository-mirror-or-source).
Each mirror is laid out as the origin it replaces:

```toml
# ~/.jk/config.toml
[mirrors]
node   = "https://nexus.corp/repository/nodejs-dist/"        # as https://nodejs.org/dist/
kotlin = "https://nexus.corp/repository/kotlin-releases/"    # as https://github.com/JetBrains/kotlin/releases/download/
gradle = "${NEXUS}/repository/gradle-distributions/"         # as https://services.gradle.org/distributions/; ${VAR} reads your shell
maven  = "https://nexus.corp/repository/maven-central/"      # a Maven repository root holding org/apache/maven/apache-maven/
jdk    = "https://nexus.corp/repository/jdk/"                # every JDK host under its own prefix, below
libraries = "https://nexus.corp/repository/jk-libraries/"    # holds libraries.toml, the library registry
```

Each is, first to answer: `JK_<TOOL>_DIST_MIRROR` (`JK_NODE_DIST_MIRROR`, `JK_KOTLIN_DIST_MIRROR`,
…), this table, a Maven `settings.xml` `<mirror>` whose `mirrorOf` names the tool (`nodejs`,
`kotlin`, `gradle`, `jk-libraries`; for the Maven distribution `central` or `*`, as Maven itself reads them), then
the public origin. A Gradle wrapper's own `distributionUrl` is used as written. Credentials come
from the chain below, under the mirror's `host[:port]` (or a `settings.xml` mirror's `id`).

The Apache Maven distribution lives in Central, in a Maven repository layout, so a native
[`mirror-of`](repositories.md#a-corporate-repository-mirror-or-source) table that stands in for
`central` (`["*"]`, `["central"]`, `["external:*"]`, …) serves it too. Its order is
`JK_MAVEN_DIST_MIRROR`, `[mirrors] maven`, the first matching `mirror-of` table, then a
`settings.xml` mirror. It authenticates with the table's own credential, under its id. Node,
Kotlin, Gradle and JDK downloads are not Maven repositories, so a `mirror-of` table never serves
them; they keep `[mirrors]`.

A `[mirrors]` value, and a `[node]` registry or scope, may use any
[secret reference](repositories.md#secret-references): `${VAR}`, `${env:VAR}`, `${file:…}` or
`${cmd:…}`.

A JDK comes from more than one host: the catalog feed is on `download.jetbrains.com` and each
archive it names is on its vendor's host (`github.com` for Temurin, `cdn.azul.com`, …). A `jdk`
mirror (`JK_JDK_DIST_MIRROR`, or a `settings.xml` mirror whose `mirrorOf` names `jdk`) therefore
serves each host under a path prefix: `https://host/path` is fetched from `<mirror>/host/path`, so
the feed is `<mirror>/download.jetbrains.com/jdk/feed/v1/jdks.json`. In Nexus or Artifactory that is
one raw/generic proxy per host, grouped under one URL. The feed and its checksums are used as
published, so every archive is verified against the catalog's sha256 whichever host served it.

The library registry is the file behind `jk.toml`'s short library names (`jk library update`
reads it, and `jk lock` and the engine refresh it every 12 hours). Its public copy is
`https://raw.githubusercontent.com/JumpKickOSS/jk-libraries/refs/heads/main/libraries.toml`;
a `libraries` mirror (`JK_LIBRARIES_DIST_MIRROR`) is the directory that holds a copy, so jk
reads `<mirror>libraries.toml`.

## Hosts jk contacts

Every host jk dials on its own, and how to point it somewhere else. A host marked optional is
asked quietly: when it does not answer, jk carries on with what it has and says nothing, so a
firewall that allows only your mirrors costs you that feature's freshness, not a failure.

| Host | What for | Where else it can come from | When it does not answer |
|---|---|---|---|
| `repo.maven.apache.org`, `maven-central.storage-download.googleapis.com` | Maven Central (the second host when Central rate-limits) | a [`mirror-of`](repositories.md#a-corporate-repository-mirror-or-source) table, or a `settings.xml` `<mirror>` | the resolve fails, naming the repository or its mirror |
| `dl.google.com` | Google's Maven repository | the same | the same |
| `jumpkick.build` (`/repo/…`) | jk's own repository: plugins, and the engine's worker jars when the store lacks them | the same (it is the `jumpkick` repository) | the same |
| `jumpkick.build` (`/releases/…`) | `jk self update` and the engine jar of an install that has none | `JK_RELEASES_URL`; `self-update = "never"` stops the checks | the update or install fails |
| `raw.githubusercontent.com` | the library registry | `[mirrors] libraries` | optional: the copy on disk, else the one built into jk; asked again 10 minutes later at the earliest, and the engine log says so once |
| `download.jetbrains.com` | the JDK catalog | `[mirrors] jdk` | optional: the catalog on disk; `jk new` lists the JDKs you have installed, and provisioning a JDK needs a catalog |
| the JDK vendors' hosts (`github.com`, `cdn.azul.com`, …) | JDK archives the catalog names | `[mirrors] jdk` | provisioning that JDK fails |
| `github.com` (git) | the official template catalog | `[templates] official` | optional: the catalog on disk |
| `nodejs.org`, `registry.npmjs.org` | Node and its packages, for a project that uses them | `[mirrors] node`, [`[node]`](#node-network) | the build that needs them fails |
| `github.com/JetBrains/kotlin`, `services.gradle.org` | the Kotlin compiler and Gradle distributions, when a project uses them | `[mirrors] kotlin`, `[mirrors] gradle` | the build that needs them fails |

A repository a project or a POM declares is asked at its own URL unless a `mirror-of` table
matches it. A `[network] proxy` carries all of the above, the template catalog's git included.

## Node network

Packages and the pnpm, Yarn and bun tarballs come from `https://registry.npmjs.org/` unless you
point them elsewhere; the Node distribution itself is `[mirrors] node` ([Tool mirrors](#tool-mirrors)):

```toml
# ~/.jk/config.toml
[node]
registry = "${NEXUS}/repository/npm-all/"                    # ${VAR} reads your shell

[node.scopes]
"@acme" = "https://nexus.corp/repository/npm-acme/"          # one scope's own registry
```

The registry is, first to answer: `JK_NODE_REGISTRY`, this file, a Maven `settings.xml` `<mirror>`
whose `mirrorOf` names `npm`, then the public default. A wildcard `mirrorOf` is a Maven mirror and
never stands in for npm.

**Credentials** never go in this file. Each origin's comes from the
[repository credential chain](repositories.md#credentials) under the origin's `host[:port]`:
`JK_REPO_<HOST>_TOKEN` (or `_USERNAME` + `_PASSWORD`), `jk repo login <host>`, or a settings.xml
`<server>` of that id. An origin taken from a settings.xml `<mirror>` uses the mirror's `<id>`, so
the `<server>` beside it authenticates it, as under Maven.

**The package managers** get all of it for each step jk runs. jk writes a user config for the
one run (`target/node/jk-npmrc-*`, owner-only, deleted after the step) holding your own
`~/.npmrc`, then the registry, the scopes and each origin's token. bun reads no npm user config, so
it gets the same registry, scopes and credentials as a global `.bunfig.toml` in a jk-owned global
config directory for the step (`target/node/jk-bun-*`, owner-only, deleted after); your own global
bunfig is not read during that step, and the project's `bunfig.toml` still is. Yarn Berry gets
`YARN_NPM_REGISTRY_SERVER`, `YARN_NPM_AUTH_TOKEN` / `YARN_NPM_AUTH_IDENT` with
`YARN_NPM_ALWAYS_AUTH` (Berry sends no credential on reads otherwise), and every plain-http
registry's host in `YARN_UNSAFE_HTTP_WHITELIST`. Berry reads a scope map only from a `.yarnrc.yml`,
merging the project's with the one in its home folder, so with scopes set the step runs Berry with a
jk-owned home (`target/node/jk-yarn-*`, deleted after): its `.yarnrc.yml` is your own
`~/.yarnrc.yml` with jk's `npmScopes` added (a scope yours already names is left to it), and every
other entry links to your real home, so git, ssh and lifecycle scripts find what they would. The
proxy jk itself
uses ([Network](#network)) is handed over as `npm_config_proxy` / `npm_config_https_proxy` /
`npm_config_noproxy`, `YARN_HTTP_PROXY` / `YARN_HTTPS_PROXY`, and `HTTP_PROXY` / `HTTPS_PROXY` /
`NO_PROXY`. The project's own `.npmrc`, `bunfig.toml` and `.yarnrc.yml` are never edited and still apply. The
registry and scope map are part of the install's cache key; a credential never is, and never
appears in output or `jk-results.md`.

## Credentials

`[credentials] store` in `~/.jk/config.toml` says where `jk repo login` and `jk auth login` keep a
secret: `"keychain"` (the OS keychain, or the login fails), `"file"` (the owner-only record under
`~/.jk/creds/`), or `"auto"`, the default (the keychain when one answers, else the file; on a CI
server, in a container, or with no keychain on the machine, the file without a word).
`JK_CREDENTIALS_STORE` overrides it. See
[Repositories § Where a login keeps its secret](repositories.md#where-a-login-keeps-its-secret).

## Other env

Every boolean jk reads — from a `JK_*` variable, from `CI`, or from a quoted value in
`jk.toml` — accepts the same set, trimmed and case-insensitive:

| true | false |
|---|---|
| `1` `true` `yes` `on` | `0` `false` `no` `off` |

Anything else means "unset", so the next layer down decides; a malformed value is never a
hard failure. There is one reader behind all of it, so `CI=yes` and `CI=1` mean exactly what
`CI=true` means everywhere.

Install / dirs: [Install](install.md). Format: [Format](format.md). Cache budgets:
[Cache](cache.md). `.env` layering: `jk env` on the install page.

```bash
jk doctor                 # host health (engine, dirs, JDKs, lock, ~/.m2 checksums, current/login shell, plugin workers)
jk doctor -v              # plus each worker's launch classpath, entry by entry
```
